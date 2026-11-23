package de.hannes.aiplayer.reflex;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.protocol.Priority;
import de.hannes.aiplayer.skill.Combat;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

/**
 * Notwehr ohne laufende Aktion: Trifft ihn ein Gegner im Nahkampf, schlägt er mit der besten Waffe zurück, sobald der Schlag
 * aufgeladen ist. End-Lauf 25: zwei Endermen am Startplatz, zwölf Treffer zu je 7, während die KI noch ihren ersten Schritt
 * überlegte – niemand schlug zurück. Mit laufender Aktion entscheidet die Aktion (Kampf, Flucht, destroy_crystals).
 */
final class SelfDefense {
	private static final double REACH = 3.2;
	private static final int RECENT_TICKS = 30;

	private SelfDefense() {
	}

	static void tick(ServerPlayer bot, long tick, EventBus bus, boolean actionRunning) {
		if (actionRunning || bot.isUsingItem()) {
			return;
		}
		LivingEntity attacker = bot.getLastHurtByMob();
		if (attacker == null || !attacker.isAlive() || attacker instanceof EnderDragon
				|| bot.tickCount - bot.getLastHurtByMobTimestamp() > RECENT_TICKS
				|| attacker.distanceTo(bot) > REACH || Combat.isPeacefulPiglin(attacker, bot)
				|| bot.getAttackStrengthScale(0.5f) < 0.95f) {
			return;
		}
		Combat.holdBestWeapon(bot);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, attacker.getEyePosition());
		// Im Sprint kein Schwungschlag: Drachenlauf 43 schlug einen Enderman zurück, der Schwung traf einen Zombie-Piglin
		// daneben, die Gruppe erschlug ihn in 6 s (wie Fighter).
		boolean noSweep = Combat.peacefulPiglinNear(attacker, bot);
		if (noSweep) {
			bot.setSprinting(true);
		}
		bot.attack(attacker);
		bot.swing(InteractionHand.MAIN_HAND);
		if (noSweep) {
			bot.setSprinting(false);
		}
		JsonObject data = new JsonObject();
		data.addProperty("target", GameJson.entityType(attacker));
		data.addProperty("id", attacker.getId());
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		bus.emit("reflex_struck_back", tick, Priority.NORMAL, data);
	}
}
