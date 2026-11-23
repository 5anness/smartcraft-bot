package de.hannes.aiplayer.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.aiplayer.protocol.Priority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.List;

/**
 * Regelmäßiger Blick in die Umgebung des Bots: feindliche Mobs und Gefahren.
 * Gemeldet wird nur, wenn sich das Bild ändert. Schemas: {@code entity_nearby}, {@code danger}.
 */
final class SurroundingsScanner {
	private static final int MOB_SCAN_INTERVAL = 10;
	private static final int MOB_RADIUS = 16;
	private static final int MOB_MIN_EMIT_GAP = 20;
	private static final int MOB_LIST_LIMIT = 8;
	private static final int MOB_CLOSE = 6;
	/** So nah gilt ein Gegner immer als gefährlich, auch ohne Sichtlinie (Ecke, halbe Blöcke). */
	private static final int MOB_ADJACENT = 3;
	/** Creeper: dringend schon ab 12 Blöcken, ab 4 noch einmal (eigene Zone). */
	private static final int CREEPER_ALERT = 12;
	private static final int CREEPER_CLOSE = 4;

	private static final int DANGER_SCAN_INTERVAL = 5;
	private static final int DANGER_MIN_EMIT_GAP = 10;
	private static final int LAVA_RADIUS = 3;
	private static final int FIRE_RADIUS = 2;
	private static final int SAFE_DROP = 3;
	private static final int MAX_DROP_SCAN = 24;

	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private String lastMobs = "";
	private String lastDangers = "";
	private long lastMobEmit = Long.MIN_VALUE / 2;
	private long lastDangerEmit = Long.MIN_VALUE / 2;

	void reset() {
		lastMobs = "";
		lastDangers = "";
	}

	void tick(ServerPlayer bot, long tick, EventBus bus) {
		if (bot.isDeadOrDying() || bot.isSpectator()) {
			reset();
			return;
		}
		if (tick % DANGER_SCAN_INTERVAL == 0) {
			scanDangers(bot, tick, bus);
		}
		if (tick % MOB_SCAN_INTERVAL == 0) {
			scanMobs(bot, tick, bus);
		}
	}

	private void scanMobs(ServerPlayer bot, long tick, EventBus bus) {
		List<LivingEntity> mobs = bot.level().getEntitiesOfClass(LivingEntity.class,
				bot.getBoundingBox().inflate(MOB_RADIUS, MOB_RADIUS / 2.0, MOB_RADIUS),
				entity -> entity instanceof Enemy && entity.isAlive() && entity.distanceToSqr(bot) <= MOB_RADIUS * MOB_RADIUS);
		mobs.sort(Comparator.comparingDouble(bot::distanceToSqr));

		JsonArray list = new JsonArray();
		StringBuilder signature = new StringBuilder();
		boolean close = false;
		for (LivingEntity mob : mobs.subList(0, Math.min(mobs.size(), MOB_LIST_LIMIT))) {
			int dist = Math.round(bot.distanceTo(mob));
			// Friedliche Zombie-Piglins und Endermen, die ihn nicht verfolgen, machen das Event nicht dringend: Hannes'
			// Lauf 28.10.: Zombie-Piglins neben dem Weg hielten jeden goto an.
			boolean neutral = de.hannes.aiplayer.skill.Combat.isPeacefulPiglin(mob, bot)
					|| mob instanceof net.minecraft.world.entity.monster.EnderMan enderman && enderman.getTarget() != bot;
			// Creeper früher dringend (Drachenlauf 15: erst bei 2 Blöcken gemeldet, die KI kam nicht mehr dazu, tot).
			boolean creeper = mob instanceof net.minecraft.world.entity.monster.Creeper;
			// Dringend nur, wenn der Gegner an den Bot herankann: Er sieht ihn, hat ihn als Ziel oder steht direkt daneben.
			// Log-Auswertung 13.11.: entity_nearby hielt 1.866 Befehle an, oft wegen Mobs hinter Fels (Höhle neben dem Stollen);
			// in 17 % der Fälle gab die KI danach denselben Befehl noch einmal, defend endete 401-mal mit enemies_unreachable.
			boolean canReach = dist < MOB_ADJACENT || mob.hasLineOfSight(bot)
					|| mob instanceof net.minecraft.world.entity.Mob hunter && hunter.getTarget() == bot;
			close |= (dist < MOB_CLOSE || creeper && dist < CREEPER_ALERT) && !neutral && canReach;

			JsonObject json = new JsonObject();
			json.addProperty("id", mob.getId());
			json.addProperty("type", GameJson.entityType(mob));
			json.addProperty("dist", dist);
			json.addProperty("dir", Compass.towards(mob.getX() - bot.getX(), mob.getZ() - bot.getZ()));
			json.addProperty("dy", (int) Math.round(mob.getY() - bot.getY()));
			if (mob.isBaby()) {
				json.addProperty("baby", true);
			}
			if (neutral) {
				json.addProperty("neutral", true);
			}
			if (!canReach) {
				json.addProperty("behind_wall", true);
			}
			list.add(json);

			// Entfernungszonen statt genauer Entfernung, sonst gäbe jeder Schritt eines Mobs ein Event.
			// Beim Creeper eine eigene Zone ganz nah, damit der Schritt von 12 auf 4 Blöcke noch einmal gemeldet wird.
			char zone = creeper && dist < CREEPER_CLOSE ? 'x' : creeper && dist < CREEPER_ALERT ? 'a'
					: dist < MOB_CLOSE ? 'a' : dist < 11 ? 'b' : 'c';
			// Kommt ein naher Gegner hinter der Wand hervor, ist das ein neues Bild (und jetzt dringend).
			signature.append(mob.getId()).append(zone).append(canReach || dist >= CREEPER_ALERT ? "" : "w").append(' ');
		}
		signature.append(mobs.size());

		String now = mobs.isEmpty() ? "" : signature.toString();
		if (now.equals(lastMobs) || tick - lastMobEmit < MOB_MIN_EMIT_GAP) {
			return;
		}
		lastMobs = now;
		lastMobEmit = tick;

		JsonObject data = new JsonObject();
		data.add("mobs", list);
		if (mobs.size() > MOB_LIST_LIMIT) {
			data.addProperty("more", mobs.size() - MOB_LIST_LIMIT);
		}
		bus.emit("entity_nearby", tick, close ? Priority.HIGH : Priority.NORMAL, data);
	}

	private void scanDangers(ServerPlayer bot, long tick, EventBus bus) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		JsonArray dangers = new JsonArray();
		StringBuilder signature = new StringBuilder();

		BlockPos lava = nearest(level, feet, LAVA_RADIUS, -2, 2, state -> state.getFluidState().is(FluidTags.LAVA));
		if (lava != null) {
			JsonObject json = located("lava", feet, lava);
			dangers.add(json);
			signature.append("lava").append(json.get("dir").getAsString()).append(json.get("dist").getAsInt()).append(' ');
		}

		BlockPos fire = nearest(level, feet, FIRE_RADIUS, -1, 2, state -> state.is(BlockTags.FIRE));
		if (fire != null || bot.isOnFire()) {
			JsonObject json = located("fire", feet, fire != null ? fire : feet);
			if (bot.isOnFire()) {
				json.addProperty("burning", true);
			}
			dangers.add(json);
			signature.append("fire").append(json.get("dir").getAsString()).append(bot.isOnFire()).append(' ');
		}

		if (bot.onGround()) {
			for (Direction dir : HORIZONTAL) {
				int depth = dropDepth(level, feet.relative(dir));
				if (depth > SAFE_DROP) {
					JsonObject json = new JsonObject();
					json.addProperty("kind", "drop");
					json.addProperty("dir", Compass.towards(dir.getStepX(), dir.getStepZ()));
					json.addProperty("depth", depth);
					dangers.add(json);
					signature.append("drop").append(dir.getName()).append(' ');
				}
			}
		}

		String now = signature.toString();
		if (now.equals(lastDangers) || tick - lastDangerEmit < DANGER_MIN_EMIT_GAP) {
			return;
		}
		lastDangers = now;
		lastDangerEmit = tick;

		JsonObject data = new JsonObject();
		data.add("dangers", dangers);
		bus.emit("danger", tick, dangers.isEmpty() ? Priority.NORMAL : Priority.HIGH, data);
	}

	private interface BlockTest {
		boolean matches(BlockState state);
	}

	private static BlockPos nearest(ServerLevel level, BlockPos center, int radius, int minDy, int maxDy, BlockTest test) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		int bestDistSqr = Integer.MAX_VALUE;
		for (int dy = minDy; dy <= maxDy; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					int distSqr = dx * dx + dy * dy + dz * dz;
					if (distSqr >= bestDistSqr) {
						continue;
					}
					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (test.matches(level.getBlockState(cursor))) {
						best = cursor.immutable();
						bestDistSqr = distSqr;
					}
				}
			}
		}
		return best;
	}

	private static JsonObject located(String kind, BlockPos feet, BlockPos target) {
		int dx = target.getX() - feet.getX();
		int dy = target.getY() - feet.getY();
		int dz = target.getZ() - feet.getZ();
		JsonObject json = new JsonObject();
		json.addProperty("kind", kind);
		json.addProperty("dir", Compass.towards(dx, dz));
		json.addProperty("dist", (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
		json.addProperty("dy", dy);
		return json;
	}

	/**
	 * Wie tief der Bot fiele, wenn er auf dieses Nachbarfeld träte. 0, wenn das Feld versperrt ist
	 * oder Boden hat. Wasser gilt als sichere Landung.
	 */
	private static int dropDepth(ServerLevel level, BlockPos neighbour) {
		if (level.getBlockState(neighbour).blocksMotion() || level.getBlockState(neighbour.above()).blocksMotion()) {
			return 0;
		}
		BlockPos.MutableBlockPos cursor = neighbour.mutable();
		for (int depth = 0; depth < MAX_DROP_SCAN; depth++) {
			cursor.move(Direction.DOWN);
			BlockState state = level.getBlockState(cursor);
			if (state.blocksMotion() || state.getFluidState().is(FluidTags.WATER)) {
				return depth;
			}
		}
		return MAX_DROP_SCAN;
	}
}
