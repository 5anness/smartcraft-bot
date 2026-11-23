package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Combat;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Kampf gegen den Enderdrachen mit EINEM Befehl (End-Lauf 29: 81 Pfeile auf den fliegenden Drachen, 0 Schaden; zwischen zwei
 * Modellschritten verging das Hocken auf dem Brunnen). Warten am Boden etwa {@link #WAIT_RADIUS} Blöcke vom Brunnen; hockt
 * er, zum Kopf laufen und mit dem Schwert zuschlagen (der Kopf nimmt vollen Schaden); vor dem Atem hilft der Reflex. Endet,
 * wenn der Drache tot ist, oder mit health_low.
 */
public final class FightDragonAction extends Action {
	/** Näher am Brunnen: Das Hocken dauert nur Sekunden (End-Lauf 33: in 10 Minuten nur 8 Treffer). */
	private static final double WAIT_RADIUS = 16;
	private static final double HEAD_REACH = 4.5;
	private static final float MIN_HEALTH = 6;
	// Graben erlaubt: Nach bed_cycle steht der Bot in seinem Käfig, ohne Graben fand kein Weg hinaus (Hannes' Lauf 03.11.:
	// Drache auf 9,4 Leben, josef stand minutenlang eingebaut).
	private static final Moves.Options DIG = new Moves.Options(true, false);

	private GotoAction walk;
	private boolean walkingToHead;
	private int hits;
	private float dragonStart = -1;
	private int ticks;

	@Override
	public int timeoutTicks() {
		return 20 * 60 * 10;
	}

	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ticks++;
		if (bot.level().dimension() != Level.END) {
			return Outcome.failed("not_in_end", progress(bot));
		}
		EnderDragon dragon = dragon(bot);
		if (dragon == null) {
			JsonObject data = progress(bot);
			data.addProperty("outcome", "dragon_dead");
			data.addProperty("hint", "the dragon is dead: the exit portal opened in the fountain");
			return Outcome.ok(data);
		}
		if (dragonStart < 0) {
			dragonStart = dragon.getHealth();
		}
		if (bot.getHealth() <= MIN_HEALTH) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "health low: eat, then fight_dragon again");
			return Outcome.cancelled("health_low", data);
		}
		// Erst zurückschlagen, wer ihn trifft (End-Lauf 32: 40 Enderman-Treffer während des Wartens, kein Gegenschlag).
		LivingEntity attacker = bot.getLastHurtByMob();
		if (attacker != null && attacker.isAlive() && !(attacker instanceof EnderDragon)
				&& bot.tickCount - bot.getLastHurtByMobTimestamp() < 60 && attacker.distanceTo(bot) < 8) {
			if (attacker.distanceTo(bot) > 3.2) {
				if (walk == null || walkingToHead) {
					stopWalk(bot);
					walk = new GotoAction(bot, new Pos(attacker.getBlockX(), attacker.getBlockY(), attacker.getBlockZ()), 2,
							DIG);
					walkingToHead = false;
				}
			} else {
				stopWalk(bot);
				Combat.holdBestWeapon(bot);
				bot.lookAt(EntityAnchorArgument.Anchor.EYES, attacker.getEyePosition());
				if (bot.getAttackStrengthScale(0.5f) >= 0.95f) {
					bot.attack(attacker);
					bot.swing(InteractionHand.MAIN_HAND);
				}
				return null;
			}
		}
		if (lastSwing != null) {
			if (dragon.getHealth() < lastSwing) {
				hits++;
			}
			lastSwing = null;
		}
		// Schon beim Landeanflug losgehen: vom Warteplatz zum Kopf sind es etwa 14 Blöcke, das Hocken ist kurz (End-Lauf 35: in
		// 7 Minuten ein einziger Schlag).
		var phaseNow = dragon.getPhaseManager().getCurrentPhase().getPhase();
		boolean sitting = dragon.getPhaseManager().getCurrentPhase().isSitting()
				|| phaseNow == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.LANDING_APPROACH
				|| phaseNow == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.LANDING;
		Entity head = dragon.getSubEntities()[0];
		// Spuckt er im Hocken Feuer, weg vom Brunnen (12 Blöcke) und erst danach wieder zuschlagen. End-Läufe 36 und 37: am
		// Kopf zwischen Brunnen und Portalrahmen eingeklemmt, vier Atemtreffer, tot.
		if (phaseNow == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.SITTING_FLAMING) {
			if (!dodging || walk == null) {
				stopWalk(bot);
				Vec3 dir = new Vec3(bot.getX(), 0, bot.getZ());
				dir = dir.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : dir.normalize();
				BlockPos spot = ground(bot, dir.x * 12, dir.z * 12);
				walk = new GotoAction(bot, new Pos(spot.getX(), spot.getY(), spot.getZ()), 2, DIG);
				dodging = true;
				walkingToHead = false;
			}
			Outcome done = walk.tick(bot);
			if (done != null) {
				walk.release(bot);
				walk = null;
			}
			return null;
		}
		if (dodging) {
			dodging = false;
			stopWalk(bot);
		}
		// Liegt am Kopf noch eine Säurewolke, nicht hinein (End-Lauf 38: nach dem Feuerspucken zurück zum Kopf, in die Wolke, tot).
		boolean cloudAtHead = !bot.level().getEntitiesOfClass(net.minecraft.world.entity.AreaEffectCloud.class,
				head.getBoundingBox().inflate(6, 4, 6), c -> c.isAlive() && c.getOwner() instanceof EnderDragon).isEmpty();
		if (sitting && cloudAtHead) {
			if (walkingToHead) {
				stopWalk(bot);
				walkingToHead = false;
			}
			return null;
		}
		if (sitting) {
			double reach = bot.getEyePosition().distanceTo(head.getBoundingBox().getCenter());
			if (reach <= HEAD_REACH) {
				stopWalk(bot);
				Combat.holdBestWeapon(bot);
				bot.lookAt(EntityAnchorArgument.Anchor.EYES, head.getBoundingBox().getCenter());
				// Kritischer Treffer wie ein Spieler: springen, im Fallen schlagen (1,5-facher Schaden). Die Kristalle heilen den
				// Drachen zwischen zwei Landungen, der Schaden muss in die kurzen Hock-Phasen passen.
				if (bot.getAttackStrengthScale(0.5f) >= 0.95f) {
					if (bot.onGround()) {
						bot.jumpFromGround();
					} else if (bot.getDeltaMovement().y < 0) {
						float before = dragon.getHealth();
						bot.attack(head);
						bot.swing(InteractionHand.MAIN_HAND);
						lastSwing = before;
						swings++;
					}
				}
				return null;
			}
			if (!walkingToHead || walk == null) {
				stopWalk(bot);
				// Neben den Kopf auf den Boden, von der Seite des Bots aus.
				// Im Landeanflug ist der Kopf noch weit oben: dann neben den Brunnen in der Mitte.
				boolean landed = dragon.getPhaseManager().getCurrentPhase().isSitting();
				double cx = landed ? head.getX() : 0;
				double cz = landed ? head.getZ() : 0;
				Vec3 dir = new Vec3(bot.getX() - cx, 0, bot.getZ() - cz);
				dir = dir.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : dir.normalize();
				BlockPos spot = ground(bot, cx + dir.x * (landed ? 2.5 : 5), cz + dir.z * (landed ? 2.5 : 5));
				walk = new GotoAction(bot, new Pos(spot.getX(), spot.getY(), spot.getZ()), 1.5, DIG);
				walkingToHead = true;
			}
		} else if (walkingToHead || walk == null) {
			// Warteplatz am Boden auf der Seite des Bots, außerhalb der Atem-Reichweite am Brunnen.
			stopWalk(bot);
			Vec3 dir = new Vec3(bot.getX(), 0, bot.getZ());
			dir = dir.lengthSqr() < 1 ? new Vec3(1, 0, 0) : dir.normalize();
			BlockPos spot = ground(bot, dir.x * WAIT_RADIUS, dir.z * WAIT_RADIUS);
			walk = new GotoAction(bot, new Pos(spot.getX(), spot.getY(), spot.getZ()), 3, DIG);
			walkingToHead = false;
		}
		if (walk != null) {
			Outcome done = walk.tick(bot);
			if (done != null) {
				walk.release(bot);
				// Am Warteplatz angekommen (oder gescheitert): stehen bleiben, später neu planen.
				walk = walkingToHead ? null : walk;
				if (!walkingToHead) {
					walk = null;
					waitTicks = 40;
				}
			}
		}
		if (waitTicks > 0) {
			waitTicks--;
			if (waitTicks == 0) {
				walk = null;
			}
		}
		return null;
	}

	private int waitTicks;
	private boolean dodging;
	private int swings;
	private Float lastSwing;

	private void stopWalk(ServerPlayer bot) {
		if (walk != null) {
			walk.release(bot);
			walk = null;
		}
		PathFollower.release(bot);
	}

	private static BlockPos ground(ServerPlayer bot, double x, double z) {
		return bot.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(x, 0, z));
	}

	private static EnderDragon dragon(ServerPlayer bot) {
		return bot.level().getEntitiesOfClass(EnderDragon.class, bot.getBoundingBox().inflate(300), LivingEntity::isAlive)
				.stream().findFirst().orElse(null);
	}

	@Override
	public void release(ServerPlayer bot) {
		stopWalk(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		EnderDragon dragon = dragon(bot);
		data.addProperty("dragon_health", dragon == null ? 0 : GameJson.round1(dragon.getHealth()));
		data.addProperty("hits", hits);
		data.addProperty("swings", swings);
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.add("pos", GameJson.pos(bot.blockPosition()));
		return data;
	}
}
