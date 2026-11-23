package de.hannes.smartcraft.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.Combat;
import de.hannes.smartcraft.skill.Fighter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code defend}: bekämpft nacheinander alle feindlichen Mobs im Umkreis, bis keiner mehr da ist
 * oder das eigene Leben zu niedrig wird. Schema: {@code schemas/commands/defend.schema.json}.
 */
public final class DefendAction extends Action {
	private static final int RECHECK_INTERVAL_TICKS = 10;
	/** Ein Creeper so nah hat Vorrang vor dem aktuellen Gegner. */
	private static final double CREEPER_PRIORITY_DISTANCE = 6;
	/** So lange muss es ruhig sein, bevor der Bot die Gegend als sicher meldet. */
	private static final int CLEAR_TICKS = 30;
	private static final int MAX_LISTED = 5;

	private final double radius;
	private final float minHealth;
	private final List<String> killed = new ArrayList<>();
	private final Set<Integer> unreachable = new HashSet<>();
	private Fighter fighter;
	private int ticks;
	private int quietTicks;
	private int hits;
	private int crits;
	private boolean sawEnemy;

	public DefendAction(double radius, float minHealth) {
		this.radius = radius;
		this.minHealth = minHealth;
	}

	@Override
	public int timeoutTicks() {
		return 3600;
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
		ticks++;
		if (bot.getHealth() <= minHealth) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "health at or below min_health " + GameJson.round1(minHealth) + "; flee, eat or shelter");
			return Outcome.cancelled("health_low", data);
		}

		if (fighter != null && ticks % RECHECK_INTERVAL_TICKS == 0 && !(fighter.target() instanceof Creeper)) {
			// Ein Creeper neben dem Bot wartet nicht, bis der Zombie erledigt ist.
			for (LivingEntity enemy : Combat.hostiles(bot, CREEPER_PRIORITY_DISTANCE)) {
				if (enemy instanceof Creeper && !unreachable.contains(enemy.getId())) {
					switchTo(bot, enemy);
					break;
				}
			}
		}
		LivingEntity attacker = Combat.recentAttacker(bot);
		if (attacker != null && fighter != null && fighter.target() != attacker && !unreachable.contains(attacker.getId())) {
			switchTo(bot, attacker);
		}
		if (fighter != null && Combat.rangedWhileInWater(bot, fighter.target())) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "you are in water and the archer is out of reach; get out of the water and take cover (flee, goto behind a block)");
			return Outcome.failed("in_water", data);
		}
		if (fighter == null) {
			LivingEntity next = Combat.hostiles(bot, radius).stream()
					.filter(enemy -> !unreachable.contains(enemy.getId())).findFirst().orElse(null);
			if (next == null) {
				if (!sawEnemy || ++quietTicks >= CLEAR_TICKS) {
					return finish(bot);
				}
				return null;
			}
			quietTicks = 0;
			sawEnemy = true;
			fighter = new Fighter(next);
		}

		switch (fighter.tick(bot)) {
			case RUNNING -> {
			}
			case KILLED -> {
				killed.add(GameJson.entityType(fighter.target()));
				endFight(bot);
			}
			case LOST -> endFight(bot);
			case FAILED -> {
				unreachable.add(fighter.target().getId());
				endFight(bot);
			}
		}
		return null;
	}

	private Outcome finish(ServerPlayer bot) {
		JsonObject data = progress(bot);
		if (data.getAsJsonArray("remaining").isEmpty()) {
			data.addProperty("outcome", sawEnemy ? "area_clear" : "no_enemies");
			data.addProperty("drops_nearby", AttackAction.dropsNearby(bot));
			return Outcome.ok(data);
		}
		data.addProperty("hint", "no path to the remaining enemies: leave them alone and carry on with your goal "
				+ "(flying or shooting ones: shoot, or take cover); do not call defend again for them");
		return Outcome.failed("enemies_unreachable", data);
	}

	private void switchTo(ServerPlayer bot, LivingEntity enemy) {
		endFight(bot);
		fighter = new Fighter(enemy);
	}

	private void endFight(ServerPlayer bot) {
		hits += fighter.hits();
		crits += fighter.crits();
		fighter.release(bot);
		fighter = null;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (fighter != null) {
			fighter.release(bot);
		}
		Combat.halt(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		JsonArray killedJson = new JsonArray();
		killed.forEach(killedJson::add);
		data.add("killed", killedJson);
		JsonArray remaining = new JsonArray();
		Combat.hostiles(bot, radius).stream().limit(MAX_LISTED).forEach(enemy -> remaining.add(Combat.describe(enemy)));
		data.add("remaining", remaining);
		data.addProperty("hits", hits + (fighter != null ? fighter.hits() : 0));
		data.addProperty("crits", crits + (fighter != null ? fighter.crits() : 0));
		AttackAction.addFighterState(data, bot);
		return data;
	}
}
