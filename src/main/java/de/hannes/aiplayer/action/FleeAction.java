package de.hannes.aiplayer.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Combat;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code flee}: läuft weg, bis der Abstand zu allen Bedrohungen erreicht ist. Den Weg sucht die
 * Wegfindung, er führt also nicht in Lava oder über Abgründe.
 * Schema: {@code schemas/commands/flee.schema.json}.
 */
public final class FleeAction extends Action {
	/** Ohne Angabe flieht der Bot vor allen Feinden in diesem Umkreis. */
	public static final double THREAT_RADIUS = 24;

	private static final int RECHECK_INTERVAL_TICKS = 10;
	/** Erst wenn sich die nächste Bedrohung so weit bewegt hat, wird der Weg neu gesucht. */
	private static final double REPLAN_DISTANCE = 3.0;
	private static final int MAX_PATH_FAILURES = 3;
	private static final int MIN_FOOD_TO_SPRINT = 7;
	private static final int MAX_LISTED = 5;

	private final Entity from;
	private final double wanted;
	private List<Entity> threats = List.of();
	private List<Vec3> planned = List.of();
	private PathFollower follower;
	private int ticks;
	private int pathFailures;
	private String hint;

	/** @param from eine bestimmte Bedrohung oder {@code null} für alle Feinde in der Nähe */
	public FleeAction(Entity from, double wanted) {
		this.from = from;
		this.wanted = wanted;
	}

	@Override
	public int timeoutTicks() {
		// Flucht und danach notfalls noch der Unterschlupf.
		return 1800;
	}

	/** Flucht ist die Antwort auf Gefahr, kein Grund sie abzubrechen. KI-Lauf 15.10.: Feuer unter den Füßen brach
	 * die Flucht vor Lohen nach 3 Ticks ab, der Bot blieb im Feuer stehen und verbrannte. */
	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	/** Gegenwehr auf der Flucht: Wer ihn zweimal trifft und dicht dran ist, ist schneller als er (Witherskelett, Lohe). */
	private de.hannes.aiplayer.skill.Fighter standFight;
	private int lastHurtStamp = -1;
	private int hitsTaken;
	private int foughtBack;
	private int standFightTicks;

	/**
	 * Letzter Ausweg, wenn kein Fluchtweg bleibt: einbauen oder eingraben wie {@code shelter}. Log-Auswertung 13.11.: 50 von
	 * 162 Toden geschahen während flee, 109-mal endete flee mit cornered; zwischen health_low und Tod lagen im Median 7 s,
	 * zu wenig für einen weiteren Modellschritt.
	 */
	private ShelterAction refuge;

	@Override
	public boolean allowsSelfDefense() {
		return refuge != null;
	}

	/** In die Ecke getrieben: Unterschlupf versuchen; ob er steht, sagt das Ergebnis ({@code outcome: sheltered}). */
	private Outcome tickRefuge(ServerPlayer bot) {
		Outcome built = refuge.tick(bot);
		if (built == null) {
			return null;
		}
		refuge.release(bot);
		JsonObject data = progress(bot);
		data.addProperty("hint", hint);
		data.add("shelter", built.data());
		if (built.status() == de.hannes.aiplayer.protocol.Event.Status.OK) {
			data.addProperty("outcome", "sheltered");
			data.addProperty("hint", "no way to flee (" + hint + "), so you are walled in now. eat and heal here; way out: see shelter.exit");
			return Outcome.ok(data);
		}
		data.addProperty("shelter_failed", String.valueOf(built.reason()));
		return Outcome.failed("cornered", data);
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (refuge != null) {
			return tickRefuge(bot);
		}
		// Drachenläufe 22 und 33: Auf der Flucht vor einem Witherskelett traf es ihn sechsmal von hinten (je 8), flee meldete
		// cornered, er starb. Holt der Verfolger ihn ein, schlägt er zurück, bis der Gegner tot oder weg ist, dann weiter.
		if (lastHurtStamp == -1) {
			lastHurtStamp = bot.getLastHurtByMobTimestamp();
		} else if (bot.getLastHurtByMobTimestamp() != lastHurtStamp) {
			lastHurtStamp = bot.getLastHurtByMobTimestamp();
			hitsTaken++;
		}
		if (standFight == null && hitsTaken >= 2) {
			LivingEntity attacker = Combat.recentAttacker(bot);
			if (attacker != null && attacker.distanceTo(bot) < 3.5) {
				if (follower != null) {
					follower.abort(bot);
					follower = null;
				}
				standFight = new de.hannes.aiplayer.skill.Fighter(attacker);
				standFightTicks = 0;
				foughtBack++;
			}
		}
		if (standFight != null) {
			de.hannes.aiplayer.skill.Fighter.State state = standFight.tick(bot);
			if (state == de.hannes.aiplayer.skill.Fighter.State.RUNNING && ++standFightTicks < 300) {
				return null;
			}
			standFight = null;
			hitsTaken = 0;
		}
		if (ticks++ % RECHECK_INTERVAL_TICKS == 0) {
			List<Entity> now = new ArrayList<>();
			if (from != null) {
				if (from.isAlive() && from.level() == bot.level()) {
					now.add(from);
				}
			} else {
				// Wer schon dabei war, bleibt Bedrohung, bis der Abstand stimmt, auch außerhalb des Suchradius.
				for (Entity threat : threats) {
					if (threat.isAlive() && threat.level() == bot.level() && bot.distanceTo(threat) < wanted + 8) {
						now.add(threat);
					}
				}
				for (LivingEntity hostile : Combat.hostiles(bot, THREAT_RADIUS)) {
					if (!now.contains(hostile)) {
						now.add(hostile);
					}
				}
			}
			boolean first = ticks == 1;
			threats = now;
			if (threats.isEmpty()) {
				return first ? Outcome.failed("no_threat", progress(bot)) : done(bot);
			}
			if (nearest(bot) >= wanted) {
				return done(bot);
			}
			if (follower == null) {
				plan();
			} else if (movedSincePlan()) {
				plan();
				follower.retarget(goal());
			}
		}

		if (follower == null) {
			follower = new PathFollower(goal(), Moves.Options.NONE);
		}
		switch (follower.tick(bot)) {
			case RUNNING -> {
				if (bot.zza > 0 && bot.getFoodData().getFoodLevel() >= MIN_FOOD_TO_SPRINT && !bot.isUsingItem()) {
					bot.setSprinting(true);
				}
			}
			case ARRIVED -> {
				if (threats.isEmpty() || nearest(bot) >= wanted) {
					return done(bot);
				}
				// Der Verfolger ist nachgerückt: von hier aus weiter.
				plan();
				follower = null;
			}
			case FAILED -> {
				hint = follower.failure() + ": " + follower.hint();
				follower = null;
				if (++pathFailures >= MAX_PATH_FAILURES) {
					PathFollower.release(bot);
					refuge = new ShelterAction(true);
				}
			}
		}
		return null;
	}

	private Outcome done(ServerPlayer bot) {
		JsonObject data = progress(bot);
		data.addProperty("outcome", "fled");
		return Outcome.ok(data);
	}

	private void plan() {
		List<Vec3> positions = new ArrayList<>();
		threats.forEach(threat -> positions.add(threat.position()));
		planned = positions;
	}

	private boolean movedSincePlan() {
		if (planned.size() != threats.size()) {
			return true;
		}
		for (int i = 0; i < planned.size(); i++) {
			if (planned.get(i).distanceTo(threats.get(i).position()) > REPLAN_DISTANCE) {
				return true;
			}
		}
		return false;
	}

	/** Ziel für die Wegsuche: jedes Feld, das von allen Bedrohungen weit genug weg ist. */
	private Goal goal() {
		List<Vec3> positions = planned;
		Vec3 first = positions.get(0);
		Pos anchor = new Pos((int) Math.floor(first.x), (int) Math.floor(first.y), (int) Math.floor(first.z));
		return new Goal() {
			@Override
			public double distance(double x, double y, double z) {
				double nearest = Double.POSITIVE_INFINITY;
				for (Vec3 position : positions) {
					nearest = Math.min(nearest, position.distanceTo(new Vec3(x, y, z)));
				}
				// Etwas Zugabe, damit der Abstand am Ziel auch dann stimmt, wenn der Verfolger nachgerückt ist.
				return Math.max(0, wanted + 1.0 - nearest);
			}

			@Override
			public Pos anchor() {
				return anchor;
			}
		};
	}

	private double nearest(ServerPlayer bot) {
		double nearest = Double.POSITIVE_INFINITY;
		for (Entity threat : threats) {
			nearest = Math.min(nearest, bot.distanceTo(threat));
		}
		return nearest;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (refuge != null) {
			refuge.release(bot);
		}
		if (follower != null) {
			follower.abort(bot);
		}
		PathFollower.release(bot);
		bot.setSprinting(false);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		JsonArray list = new JsonArray();
		threats.stream().sorted((a, b) -> Double.compare(bot.distanceToSqr(a), bot.distanceToSqr(b)))
				.limit(MAX_LISTED).forEach(threat -> list.add(Combat.describe(threat)));
		data.add("threats", list);
		if (foughtBack > 0) {
			data.addProperty("fought_back", foughtBack);
		}
		if (!threats.isEmpty()) {
			data.addProperty("distance", GameJson.round1(nearest(bot)));
		}
		data.addProperty("health", GameJson.round1(bot.getHealth()));
		data.addProperty("food", bot.getFoodData().getFoodLevel());
		return data;
	}
}
