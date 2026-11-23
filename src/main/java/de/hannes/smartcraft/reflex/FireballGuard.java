package de.hannes.smartcraft.reflex;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.perception.EventBus;
import de.hannes.smartcraft.protocol.Priority;
import de.hannes.smartcraft.perception.GameJson;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Schlägt Ghast-Feuerbälle zurück, sobald sie in Schlagweite auf den Bot zufliegen – so wehrt sie auch ein Spieler ab.
 * Kit-Lauf 2: Ein Feuerball stieß den Bot von Höhe 57 in einen Lavasee ohne Ufer.
 */
final class FireballGuard {
	private static final double SCAN_RANGE = 8;
	/** Etwas unter der Schlagweite eines Spielers (3), gemessen von den Augen zur Mitte des Feuerballs. */
	private static final double HIT_RANGE = 3.2;

	private FireballGuard() {
	}

	static void tick(ServerPlayer bot, long tick, EventBus bus) {
		List<LargeFireball> balls = bot.level().getEntitiesOfClass(LargeFireball.class,
				bot.getBoundingBox().inflate(SCAN_RANGE), Entity::isAlive);
		Vec3 eyes = bot.getEyePosition();
		for (LargeFireball ball : balls) {
			// Ein schon zurückgeschlagener Ball gehört dem Bot und fliegt von ihm weg.
			if (ball.getOwner() == bot) {
				continue;
			}
			Vec3 toBot = eyes.subtract(ball.position());
			double distance = toBot.length();
			if (distance > HIT_RANGE || ball.getDeltaMovement().dot(toBot) <= 0) {
				continue;
			}
			bot.lookAt(EntityAnchorArgument.Anchor.EYES, ball.position());
			// Kein Schwungschlag in friedliche Piglins daneben (Drachenlauf 43, siehe SelfDefense): im Sprint schlagen.
			boolean noSweep = !bot.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
					bot.getBoundingBox().inflate(4.0), e -> de.hannes.smartcraft.skill.Combat.isPeacefulPiglin(e, bot)).isEmpty();
			if (noSweep) {
				bot.setSprinting(true);
			}
			bot.attack(ball);
			bot.swing(InteractionHand.MAIN_HAND);
			if (noSweep) {
				bot.setSprinting(false);
			}
			JsonObject data = new JsonObject();
			data.addProperty("distance", GameJson.round1(distance));
			data.addProperty("health", GameJson.round1(bot.getHealth()));
			bus.emit("reflex_deflected_fireball", tick, Priority.NORMAL, data);
			return;
		}
	}
}
