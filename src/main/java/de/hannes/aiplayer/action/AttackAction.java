package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Combat;
import de.hannes.aiplayer.skill.Fighter;
import de.hannes.aiplayer.skill.Inv;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * {@code attack}: bekämpft ein Wesen, bis es tot ist, verschwindet oder das eigene Leben zu
 * niedrig wird. Schema: {@code schemas/commands/attack.schema.json}.
 */
public final class AttackAction extends Action {
	private static final double DROPS_RADIUS = 6;

	/** Ab hier ist ein fliegendes Ziel für das Schwert außer Reichweite (wie in farm_spawner). */
	private static final double MELEE_MAX_HEIGHT = 2.5;
	private static final double MELEE_MAX_DISTANCE = 6;

	private final Fighter fighter;
	/** Gegenwehr gegen einen anderen Angreifer im Nahkampf, solange er lebt und nah ist. */
	private Fighter guard;
	private final float minHealth;
	/** Bogen gegen Lohe/Ghast außer Reichweite. Drachenlauf 11: Der Bot lief einer Lohe 10 Blöcke unter ihm hinterher,
	 * ein Feuerball stieß ihn von der Brücke. */
	private ShootAction shooter;
	private boolean shootingFailed;
	/** Ticks, die der Bot einen Enderman schon anstarrt; -1 = noch nicht. */
	private int provokeTicks = -1;
	private static final int PROVOKE_TICKS = 160;
	/** So lange wartet der Bot, bis ein angelockter Enderman in Schlagweite ist. */
	private int comeTicks;
	private static final int COME_TICKS = 300;

	public AttackAction(LivingEntity target, float minHealth) {
		this.fighter = new Fighter(target);
		this.minHealth = minHealth;
	}

	/** Auf dem Weg zum Ziel graben und bauen. */
	public AttackAction allowDig() {
		fighter.allowDig();
		return this;
	}

	/** Nur graben, keine Blöcke setzen (Kampf im Nether). */
	public AttackAction allowDigOnly() {
		fighter.allowDig(false);
		return this;
	}

	@Override
	public int timeoutTicks() {
		return 1800;
	}

	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public boolean cancelOnDanger() {
		// Lava neben dem Kampfplatz meidet die Wegfindung ohnehin; im Nether brach sonst fast jeder Kampf ab.
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		// Reicht ein Schlag noch, wird zu Ende gekämpft. Perlen-Lauf 8: Abbruch bei 4,8 Leben, das Witherskelett hatte
		// noch 0,5 und erschlug ihn beim Essen.
		boolean oneHitLeft = fighter.target().getHealth() <= 4.0f && !outOfMelee(bot, fighter.target());
		if (bot.getHealth() <= minHealth && !fighter.target().isDeadOrDying() && !oneHitLeft) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "health at or below min_health " + GameJson.round1(minHealth) + "; flee, eat or shelter");
			return Outcome.cancelled("health_low", data);
		}
		// Wer ihn im Nahkampf trifft, kommt zuerst dran (wie defend und farm_spawner). Drachenlauf 55: attack auf eine Lohe,
		// daneben schlug ein Witherskelett viermal zu (je 8), kein eigener Treffer, tot.
		LivingEntity attacker = Combat.recentAttacker(bot);
		if (guard == null && attacker != null && attacker != fighter.target() && attacker.distanceTo(bot) <= 3.5
				&& !Combat.isPeacefulPiglin(attacker, bot)) {
			fighter.release(bot);
			guard = new de.hannes.aiplayer.skill.Fighter(attacker);
		}
		if (guard != null) {
			if (guard.target().isAlive() && guard.target().distanceTo(bot) <= 8 && guard.tick(bot) == de.hannes.aiplayer.skill.Fighter.State.RUNNING) {
				return null;
			}
			guard.release(bot);
			guard = null;
		}
		LivingEntity target = fighter.target();
		if (Combat.isPeacefulPiglin(target, bot) && fighter.hits() == 0) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "piglins and zombified piglins are peaceful until hit; one hit angers the whole group. Leave them alone");
			return Outcome.failed("would_anger_group", data);
		}
		if (Combat.rangedWhileInWater(bot, target)) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "you are in water and the archer is out of reach; get out of the water and take cover (flee, goto behind a block)");
			return Outcome.failed("in_water", data);
		}
		if (shooter != null) {
			Outcome shot = shooter.tick(bot);
			if (shot == null) {
				return null;
			}
			shooter.release(bot);
			shooter = null;
			if (target.isDeadOrDying() || !target.isAlive()) {
				JsonObject data = progress(bot);
				data.addProperty("outcome", "killed");
				data.addProperty("shot", true);
				data.addProperty("drops_nearby", dropsNearby(bot));
				return Outcome.ok(data);
			}
			if (shot.status() != de.hannes.aiplayer.protocol.Event.Status.OK) {
				shootingFailed = true;
			}
			return null;
		}
		if (!shootingFailed && (target instanceof net.minecraft.world.entity.monster.Blaze
				|| target instanceof net.minecraft.world.entity.monster.Ghast)
				&& Inv.findItem(bot, "minecraft:bow") >= 0
				&& (Inv.count(bot, "minecraft:arrow") > 0 || bot.getAbilities().instabuild) && outOfMelee(bot, target)) {
			fighter.release(bot);
			shooter = new ShootAction(String.valueOf(target.getId()), 5);
			return null;
		}
		// Endermen verfolgen klappt kaum (Vorsprünge, Teleport, no_path): ansehen, dann kommt er selbst. Perlen-Lauf 6.
		if (target instanceof net.minecraft.world.entity.monster.EnderMan enderman && enderman.getTarget() != bot
				&& provokeTicks < PROVOKE_TICKS && outOfMelee(bot, target) && bot.hasLineOfSight(enderman)) {
			if (provokeTicks < 0) {
				fighter.release(bot);
			}
			provokeTicks++;
			de.hannes.aiplayer.skill.PathFollower.release(bot);
			de.hannes.aiplayer.reflex.GazeGuard.allow(enderman);
			bot.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, enderman.getEyePosition());
			return null;
		}
		// Angelockt: Er kommt selbst (läuft oder teleportiert); hinterherlaufen scheitert oft an no_path.
		if (target instanceof net.minecraft.world.entity.monster.EnderMan enderman && enderman.getTarget() == bot
				&& outOfMelee(bot, target) && comeTicks < COME_TICKS) {
			if (comeTicks == 0) {
				fighter.release(bot);
			}
			comeTicks++;
			de.hannes.aiplayer.skill.PathFollower.release(bot);
			Combat.face(bot, enderman);
			return null;
		}
		return switch (fighter.tick(bot)) {
			case RUNNING -> null;
			case KILLED -> {
				JsonObject data = progress(bot);
				data.addProperty("outcome", "killed");
				data.addProperty("drops_nearby", dropsNearby(bot));
				yield Outcome.ok(data);
			}
			case LOST -> Outcome.failed("target_lost", progress(bot));
			case FAILED -> {
				JsonObject data = progress(bot);
				data.addProperty("hint", fighter.hint());
				yield Outcome.failed(fighter.failure(), data);
			}
		};
	}

	private static boolean outOfMelee(ServerPlayer bot, LivingEntity enemy) {
		double dx = enemy.getX() - bot.getX();
		double dz = enemy.getZ() - bot.getZ();
		return Math.abs(enemy.getY() - bot.getY()) > MELEE_MAX_HEIGHT || Math.sqrt(dx * dx + dz * dz) > MELEE_MAX_DISTANCE;
	}

	static int dropsNearby(ServerPlayer bot) {
		return bot.level().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(DROPS_RADIUS)).size();
	}

	/** Die Angaben, die jedes Kampfergebnis trägt: Leben, Hunger, Waffe mit Haltbarkeit. */
	static void addFighterState(JsonObject data, ServerPlayer bot) {
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.addProperty("food", bot.getFoodData().getFoodLevel());
		data.add("weapon", Inv.heldJson(bot));
	}

	@Override
	public void release(ServerPlayer bot) {
		if (shooter != null) {
			shooter.release(bot);
		}
		fighter.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.add("target", Combat.describe(fighter.target()));
		data.addProperty("hits", fighter.hits());
		data.addProperty("crits", fighter.crits());
		if (provokeTicks >= 0) {
			data.addProperty("provoked", fighter.target() instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == bot);
		}
		addFighterState(data, bot);
		return data;
	}
}
