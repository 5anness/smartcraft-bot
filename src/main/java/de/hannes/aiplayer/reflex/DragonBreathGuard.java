package de.hannes.aiplayer.reflex;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.protocol.Priority;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Raus aus dem Drachenatem. Eine Regel: Steht der Bot in einer Säurewolke des Drachen, oder hockt der Drache auf dem
 * Brunnen und der Bot steht näher als {@link #PERCH_SAFE} Blöcke, geht er mit der Wegfindung zum nächsten begehbaren
 * Platz außerhalb aller Wolken (beim Hocken auch außerhalb des Radius). Findet sich keiner, schiebt ihn ein Schub in die
 * freie Richtung, die am weitesten weg führt. Die laufende Aktion wartet so lange.
 * <p>
 * End-Läufe 1–65: Stehen bleiben, Graben, Pendeln am Rand und Flucht nur innerhalb einer Wolke (Lauf 65: zwischen zwei
 * Wolken am hockenden Drachen, fünfmal 100 Ticks ohne Ausweg) kosteten das Leben.
 */
final class DragonBreathGuard {
	/** Abstand zum Wolkenrand beim Eintreten. */
	private static final double MARGIN = 2.0;
	/** Beim Hinauslaufen weiter als beim Eintreten: sonst Pendeln am Rand (End-Lauf 20). */
	private static final double EXIT_MARGIN = 1.5;
	/** Wolken eines Feuerballs wachsen auf etwa 7 Blöcke (End-Lauf 5). */
	private static final double MIN_RADIUS = 7.0;
	/** Abstand zum Brunnen, solange der Drache dort hockt und Wolke um Wolke Richtung Bot spuckt. */
	private static final double PERCH_SAFE = 36;
	private static final int MAX_TICKS = 100;

	private int ticks;
	private Vec3 entered;
	private PathFollower escape;
	private boolean escapeFailed;

	/** @return {@code true}, solange der Reflex den Bot steuert */
	boolean tick(ServerPlayer bot, long tick, EventBus bus) {
		AreaEffectCloud cloud = cloudAt(bot, entered != null ? EXIT_MARGIN : 0);
		// Gerade vom Atem getroffen, aber knapp außerhalb des Randes: sofort weiter weg (End-Lauf 105: 4 × 6 Schaden in 3 s).
		if (cloud == null && bot.hurtTime > 0 && bot.getLastDamageSource() != null
				&& bot.getLastDamageSource().getEntity() instanceof EnderDragon
				&& bot.getLastDamageSource().is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC)) {
			cloud = cloudAt(bot, EXIT_MARGIN + 4);
		}
		// Hockt der Drache, nur aus Wolken fliehen, nicht pauschal vom Brunnen: bed_bomb muss zum Kopf.
		boolean perch = false;
		if (cloud == null || ticks > MAX_TICKS) {
			if (entered != null) {
				JsonObject data = new JsonObject();
				data.addProperty("ticks", ticks);
				data.addProperty("health", GameJson.round1(bot.getHealth()));
				data.add("from", GameJson.pos(BlockPos.containing(entered)));
				bus.emit("reflex_left_dragon_breath", tick, Priority.HIGH, data);
				bot.zza = 0;
				bot.setSprinting(false);
			}
			// Immer zurücksetzen, damit der Reflex sofort wieder greift (End-Lauf 52).
			ticks = 0;
			entered = null;
			escapeFailed = false;
			if (escape != null) {
				PathFollower.release(bot);
				escape = null;
			}
			if (digger != null) {
				digger.abort(bot);
				digger = null;
			}
			return false;
		}
		if (entered == null) {
			entered = bot.position();
			// Die Wegführung der unterbrochenen Aktion steuerte sonst dagegen (End-Lauf 42).
			PathFollower.release(bot);
		}
		ticks++;
		if (bot.isUsingItem()) {
			// Gespannter Bogen oder Essen bremst auf ein Fünftel (End-Läufe 40, 41).
			bot.stopUsingItem();
		}
		if (escape == null && (onPillar(bot) || enclosed(bot))) {
			escapeFailed = true;
		}
		if (escape == null && !escapeFailed) {
			BlockPos spot = escapeSpot(bot, perch);
			if (spot == null) {
				escapeFailed = true;
			} else {
				// Mit Graben: aus der eigenen Röhre am Turmfuß kam der Bot ohne nicht heraus (End-Lauf 85: 79 Ticks im Atem).
				escape = new PathFollower(Goal.near(new Pos(spot.getX(), spot.getY(), spot.getZ()), 1), new Moves.Options(true, false));
			}
		}
		if (escape != null) {
			PathFollower.State state = escape.tick(bot);
			if (state == PathFollower.State.RUNNING) {
				return true;
			}
			PathFollower.release(bot);
			escape = null;
			// Angekommen, aber noch in Gefahr (neue Wolke): neuen Platz suchen; gescheitert: Schub.
			escapeFailed = state == PathFollower.State.FAILED;
			return true;
		}
		// Kein Platz außerhalb der Wolken (oben auf dem eigenen Turm): Block unter den Füßen abbauen, unter die Wolke
		// (End-Lauf 75: auf dem Turm in Höhe 79 viermal 6 Schaden, tot).
		// Nicht am Brunnen: im eigenen Loch bleibt die Wolke liegen (End-Lauf 84: zweimal gegraben, tot).
		if ((onPillar(bot) || enclosed(bot)) && bot.getX() * bot.getX() + bot.getZ() * bot.getZ() > 12 * 12) {
			BlockPos below = bot.blockPosition().below();
			if (digger == null) {
				digger = new de.hannes.aiplayer.skill.BlockBreaker(below, 3);
			}
			bot.zza = 0;
			if (digger.tick(bot) != de.hannes.aiplayer.skill.BlockBreaker.State.RUNNING) {
				digger = null;
			}
			return true;
		}
		// Eingeschlossen nahe der Mitte (Käfig von bed_cycle, End-Lauf 98: 101 Ticks im Atem, tot): Wand nach außen aufbrechen,
		// erst Fuß-, dann Kopfhöhe, danach wieder mit der Wegfindung hinaus.
		if (enclosed(bot) || (digger != null && digOut)) {
			Direction out = Direction.getApproximateNearest(bot.getX(), 0, bot.getZ());
			BlockPos feetWall = bot.blockPosition().relative(out);
			BlockPos wall = empty(bot.level(), feetWall) ? feetWall.above() : feetWall;
			if (empty(bot.level(), wall)) {
				digger = null;
				digOut = false;
				escapeFailed = false;
				return true;
			}
			if (digger == null) {
				digger = new de.hannes.aiplayer.skill.BlockBreaker(wall, 3);
				digOut = true;
			}
			bot.zza = 0;
			if (digger.tick(bot) != de.hannes.aiplayer.skill.BlockBreaker.State.RUNNING) {
				digger = null;
			}
			return true;
		}
		push(bot, perch || cloud == null ? new Vec3(bot.getX(), 0, bot.getZ()) : bot.position().subtract(cloud.position()));
		return true;
	}

	private boolean digOut;

	private de.hannes.aiplayer.skill.BlockBreaker digger;

	/** Ringsum auf Fußhöhe zu (eigene Geländer-Röhre, End-Lauf 80: 3 Blöcke hoch in der Wolke eingeschlossen, tot). */
	private static boolean enclosed(ServerPlayer bot) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			if (empty(bot.level(), bot.blockPosition().relative(dir))) {
				return false;
			}
		}
		return true;
	}

	/** Steht auf einer schmalen Säule: neben ihm geht es in 2 Blöcken Abstand mindestens 4 Blöcke hinab. */
	private static boolean onPillar(ServerPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			boolean deep = true;
			for (int down = 1; down <= 4 && deep; down++) {
				deep = empty(level, feet.relative(dir, 2).below(down));
			}
			if (deep) {
				return true;
			}
		}
		return false;
	}

	/** Rückfall: Schub in die freie Richtung, die am ehesten {@code away} folgt (die Eingabe allein bewegt den Fake Player kaum). */
	private static void push(ServerPlayer bot, Vec3 away) {
		Vec3 dir = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
		double best = -2;
		double bx = dir.x;
		double bz = dir.z;
		for (int i = 0; i < 8; i++) {
			double cx = Math.cos(i * Math.PI / 4);
			double cz = Math.sin(i * Math.PI / 4);
			double score = cx * dir.x + cz * dir.z;
			if (score > best && free(bot, cx, cz)) {
				best = score;
				bx = cx;
				bz = cz;
			}
		}
		float yaw = (float) (Mth.atan2(bz, bx) * Mth.RAD_TO_DEG) - 90.0f;
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.zza = 0;
		bot.xxa = 0;
		if (bot.horizontalCollision) {
			bot.setJumping(true);
		}
		Vec3 motion = bot.getDeltaMovement();
		bot.setDeltaMovement(bx * 0.28, motion.y, bz * 0.28);
		bot.hurtMarked = true;
	}

	/** Nächster begehbarer Platz außerhalb aller Drachenwolken (beim Hocken auch außerhalb von {@link #PERCH_SAFE}). */
	private static BlockPos escapeSpot(ServerPlayer bot, boolean perch) {
		ServerLevel level = bot.level();
		List<AreaEffectCloud> clouds = level.getEntitiesOfClass(AreaEffectCloud.class, bot.getBoundingBox().inflate(32),
				c -> c.isAlive() && c.getOwner() instanceof EnderDragon);
		int maxR = perch ? 40 : 16;
		for (int r = 3; r <= maxR; r++) {
			BlockPos best = null;
			double bestDist = Double.MAX_VALUE;
			for (int i = 0; i < 24; i++) {
				double angle = i * Math.PI / 12;
				int x = Mth.floor(bot.getX() + Math.cos(angle) * r);
				int z = Mth.floor(bot.getZ() + Math.sin(angle) * r);
				if (perch && x * x + z * z < PERCH_SAFE * PERCH_SAFE) {
					continue;
				}
				BlockPos feet = standable(level, x, bot.getBlockY(), z);
				if (feet == null || inCloud(clouds, x + 0.5, z + 0.5)) {
					continue;
				}
				double d = bot.position().distanceToSqr(x + 0.5, feet.getY(), z + 0.5);
				if (d < bestDist) {
					bestDist = d;
					best = feet;
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	private static BlockPos standable(ServerLevel level, int x, int y, int z) {
		for (int dy = 2; dy >= -3; dy--) {
			BlockPos feet = new BlockPos(x, y + dy, z);
			if (level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP) && empty(level, feet)
					&& empty(level, feet.above())) {
				return feet;
			}
		}
		return null;
	}

	private static boolean inCloud(List<AreaEffectCloud> clouds, double x, double z) {
		for (AreaEffectCloud c : clouds) {
			double reach = Math.max(c.getRadius(), MIN_RADIUS) + MARGIN + EXIT_MARGIN;
			if ((x - c.getX()) * (x - c.getX()) + (z - c.getZ()) * (z - c.getZ()) < reach * reach) {
				return true;
			}
		}
		return false;
	}

	/** Zwei Felder in Richtung (cx, cz) frei auf Fuß- und Kopfhöhe (eine Stufe darf sein), mit Boden bis 3 darunter. */
	private static boolean free(ServerPlayer bot, double cx, double cz) {
		ServerLevel level = bot.level();
		for (int step = 1; step <= 2; step++) {
			BlockPos feet = BlockPos.containing(bot.getX() + cx * step, bot.getY(), bot.getZ() + cz * step);
			if (!empty(level, feet) && empty(level, feet.above()) && empty(level, feet.above(2))) {
				feet = feet.above();
			}
			if (!empty(level, feet) || !empty(level, feet.above())) {
				return false;
			}
			boolean ground = false;
			for (int down = 1; down <= 3 && !ground; down++) {
				ground = !empty(level, feet.below(down));
			}
			if (!ground) {
				return false;
			}
		}
		return true;
	}

	private static boolean empty(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	/** Drache hockt (nahe der Inselmitte, tief). */
	private static boolean dragonPerched(ServerPlayer bot) {
		for (EnderDragon dragon : bot.level().getEntitiesOfClass(EnderDragon.class, bot.getBoundingBox().inflate(200), Entity::isAlive)) {
			if (dragon.getX() * dragon.getX() + dragon.getZ() * dragon.getZ() < 100 && dragon.getY() < 80) {
				return true;
			}
		}
		return false;
	}

	private static boolean nearestCloud(ServerPlayer bot, double range) {
		return !bot.level().getEntitiesOfClass(AreaEffectCloud.class, bot.getBoundingBox().inflate(range),
				c -> c.isAlive() && c.getOwner() instanceof EnderDragon).isEmpty();
	}

	private static AreaEffectCloud cloudAt(ServerPlayer bot, double extra) {
		for (AreaEffectCloud cloud : bot.level().getEntitiesOfClass(AreaEffectCloud.class, bot.getBoundingBox().inflate(12),
				Entity::isAlive)) {
			if (!(cloud.getOwner() instanceof EnderDragon)) {
				continue;
			}
			double radius = Math.max(cloud.getRadius(), MIN_RADIUS) + MARGIN + extra;
			double dx = bot.getX() - cloud.getX();
			double dz = bot.getZ() - cloud.getZ();
			double dy = bot.getY() - cloud.getY();
			if (dx * dx + dz * dz <= radius * radius && dy > -2 && dy < 3) {
				return cloud;
			}
		}
		return null;
	}
}
