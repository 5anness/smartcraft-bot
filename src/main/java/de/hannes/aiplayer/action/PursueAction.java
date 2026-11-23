package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;

/**
 * {@code goto_entity} und {@code follow}: läuft einem Wesen hinterher. {@code goto_entity} endet,
 * sobald der Abstand erreicht ist; {@code follow} bleibt dran, bis die Zeit um ist oder ein
 * anderer Befehl kommt. Schemas: {@code schemas/commands/goto_entity.schema.json}, {@code follow.schema.json}.
 */
public final class PursueAction extends Action {
	public static final int SEARCH_RADIUS = 48;

	private static final int RETARGET_INTERVAL_TICKS = 10;
	private static final double RETARGET_DISTANCE = 2.0;
	private static final int NO_PATH_RETRY_TICKS = 40;
	private static final int MAX_NO_PATH_RETRIES = 5;

	private final Entity target;
	private final double distance;
	private final boolean keepFollowing;
	private final int durationTicks;

	private PathFollower follower;
	private int ticks;
	private int noPathRetries;
	private int waitTicks;

	/**
	 * @param distance      so nah will der Bot heran
	 * @param keepFollowing {@code true} für {@code follow}
	 * @param durationTicks nur für {@code follow}: danach endet die Aktion mit {@code ok}
	 */
	public PursueAction(Entity target, double distance, boolean keepFollowing, int durationTicks) {
		this.target = target;
		this.distance = distance;
		this.keepFollowing = keepFollowing;
		this.durationTicks = durationTicks;
	}

	/**
	 * Sucht das Ziel: zuerst ein Spieler mit diesem Namen, sonst das nächste lebende Wesen
	 * dieses Typs (z. B. {@code cow} oder {@code minecraft:cow}) im Umkreis.
	 */
	public static Entity findTarget(ServerPlayer bot, String selector) {
		for (ServerPlayer player : bot.level().players()) {
			if (player != bot && player.getGameProfile().name().equalsIgnoreCase(selector)) {
				return player;
			}
		}
		// Eine Wesen-ID aus entity_nearby (Perlen-Lauf 6: goto_entity "1627" fand nichts).
		if (selector.matches("\\d+")) {
			Entity entity = bot.level().getEntity(Integer.parseInt(selector));
			return entity != null && entity != bot && entity.isAlive() && entity.distanceTo(bot) <= SEARCH_RADIUS ? entity : null;
		}
		String type = selector.contains(":") ? selector : "minecraft:" + selector;
		return bot.level().getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(SEARCH_RADIUS),
						entity -> entity != bot && entity.isAlive() && !(entity instanceof Player)
								&& GameJson.entityType(entity).equals(type) && !shorn(entity))
				.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
	}

	/**
	 * Geschorenes Schaf: als Ziel übersprungen. Drachenlauf 14: goto_entity sheep führte eine Stunde lang zum selben
	 * Schaf, der Bot wartete jeweils 90 bis 120 s auf nachwachsende Wolle (zusammen 25 Minuten).
	 */
	public static boolean shorn(Entity entity) {
		return entity instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && sheep.isSheared();
	}

	/** Wie viele geschorene Schafe im Suchradius stehen (für den Hinweis bei entity_not_found). */
	public static int shornNearby(ServerPlayer bot) {
		return bot.level().getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(SEARCH_RADIUS),
				entity -> entity.isAlive() && shorn(entity)).size();
	}

	@Override
	public int timeoutTicks() {
		return keepFollowing ? durationTicks + 200 : 2400;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ticks++;
		if (!target.isAlive() || target.isRemoved() || target.level() != bot.level()
				|| bot.distanceTo(target) > SEARCH_RADIUS * 1.5) {
			return Outcome.failed("target_lost", progress(bot));
		}
		if (keepFollowing && ticks >= durationTicks) {
			return Outcome.ok(progress(bot));
		}

		if (bot.distanceTo(target) <= distance + 0.5) {
			if (!keepFollowing) {
				return Outcome.ok(progress(bot));
			}
			// Nah genug: stehen bleiben und das Ziel ansehen.
			PathFollower.release(bot);
			follower = null;
			lookAt(bot);
			return null;
		}

		if (waitTicks > 0) {
			waitTicks--;
			PathFollower.release(bot);
			return null;
		}

		Pos targetPos = new Pos(target.getBlockX(), Mth.floor(target.getY() + 0.2), target.getBlockZ());
		double tolerance = Math.max(1.0, distance);
		if (follower == null) {
			follower = new PathFollower(Goal.near(targetPos, tolerance), Moves.Options.NONE);
		} else if (ticks % RETARGET_INTERVAL_TICKS == 0 && follower.goal().anchor().distanceTo(targetPos) > RETARGET_DISTANCE) {
			follower.retarget(Goal.near(targetPos, tolerance));
		}

		switch (follower.tick(bot)) {
			case RUNNING -> {
			}
			// Am Zielfeld, aber das Wesen ist weitergelaufen: neu ansetzen.
			case ARRIVED -> follower = null;
			case FAILED -> {
				String failure = follower.failure();
				String hint = follower.hint();
				follower = null;
				// Ein Wesen in Bewegung ist oft kurz unerreichbar (springt, schwimmt); ein paar Mal neu versuchen.
				if (++noPathRetries > MAX_NO_PATH_RETRIES || !keepFollowing && noPathRetries > 2) {
					JsonObject data = progress(bot);
					data.addProperty("hint", hint);
					return Outcome.failed(failure, data);
				}
				waitTicks = NO_PATH_RETRY_TICKS;
			}
		}
		return null;
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		JsonObject entity = new JsonObject();
		entity.addProperty("id", target.getId());
		entity.addProperty("type", GameJson.entityType(target));
		if (target instanceof Player player) {
			entity.addProperty("name", player.getGameProfile().name());
		}
		entity.add("pos", GameJson.pos(target.blockPosition()));
		entity.addProperty("alive", target.isAlive());
		data.add("target", entity);
		data.addProperty("distance", GameJson.round1(bot.distanceTo(target)));
		if (keepFollowing) {
			data.addProperty("followed_seconds", ticks / 20);
		}
		return data;
	}

	private void lookAt(ServerPlayer bot) {
		float yaw = (float) (Mth.atan2(target.getZ() - bot.getZ(), target.getX() - bot.getX()) * Mth.RAD_TO_DEG) - 90.0f;
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
	}
}
