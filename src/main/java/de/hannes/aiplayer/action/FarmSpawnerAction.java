package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Combat;
import de.hannes.aiplayer.skill.Fighter;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.ItemGatherer;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.tags.BlockTags;
import java.util.Map;

/**
 * {@code farm_spawner}: geht zum nächsten Spawner, tötet alles, was dort feindlich ist, sammelt die Beute ein und
 * bleibt am Spawner, bis genug vom Item im Inventar ist. Wunsch von Hannes: Am Spawner nicht nach jeder Lohe ein
 * neuer Modellschritt, sondern dort bleiben und farmen. Schema: {@code schemas/commands/farm_spawner.schema.json}.
 */
public final class FarmSpawnerAction extends Action {
	/** So nah bleibt der Bot am Spawner; Mobs erscheinen bis 4 Blöcke darum, der Spawner läuft bis 16 Blöcke. */
	private static final double STAY_DISTANCE = 3;
	/** Gegner bis hierher bekämpft er; weiter weg lockt ihn nichts vom Spawner fort. */
	private static final double FIGHT_RADIUS = 12;
	private static final double PICKUP_RADIUS = 8;
	private static final int PICKUP_TICKS = 200;
	/** Ein Gegner, der nicht zu treffen war, wird so lange übergangen. */
	private static final int SKIP_TICKS = 400;
	/** Abstände zum Spawner, die er nacheinander versucht, wenn der Weg scheitert. */
	private static final double[] STAND_OFF = {STAY_DISTANCE, 6, 10};
	private String lastPathProblem;
	private double walkStartDistance;
	private double walkBest;
	private int walkStale;
	private static final int WALK_STALE_TICKS = 300;
	/** So weit vom Spawner kämpft er höchstens. */
	private static final double LEASH = 16;
	private static final int SHOTS_PER_VOLLEY = 4;
	/** Lohen so hoch über ihm oder so weit weg erreicht das Schwert nicht: dann der Bogen. */
	private static final double MELEE_MAX_HEIGHT = 2.5;
	private static final double MELEE_MAX_DISTANCE = 6;
	/** Feuer am Boden löscht er bis hierhin (Reichweite des Arms), ein Block je so viele Ticks. */
	private static final double FIRE_REACH = 4.5;
	private static final int FIRE_INTERVAL_TICKS = 5;
	/** In ruhigen Momenten geht er zu Feuer bis so weit, um es zu löschen. */
	private static final int FIRE_WALK_RADIUS = 8;
	private PathFollower fireWalker;
	private int fireWalkTicks;

	private final String item;
	private final int count;
	private final float minHealth;
	/** Nach einer gescheiterten Hütte erst ab diesem Tick wieder einbauen (bis dahin wird gekämpft). */
	private int shelterRetryTick;
	private final int seconds;

	private BlockPos spawner;
	private PathFollower walker;
	private Fighter fighter;
	/** Lohen schweben meist außer Reichweite des Schwerts: Mit Bogen und Pfeilen schießt er vom Spawner aus. */
	private ShootAction shooter;
	private LivingEntity shotTarget;
	private ItemGatherer gatherer;
	private final Map<Integer, Integer> skipUntil = new HashMap<>();
	/** Lohen, an die er im Nahkampf nicht herankam: die nur noch mit dem Bogen. */
	private final Set<Integer> meleeFailed = new HashSet<>();
	private int firesOut;
	private boolean droppedJunk;
	private final Map<String, Integer> killed = new HashMap<>();
	private int startCount = -1;
	private int ticks;
	private int hits;
	private int pathFailures;
	/** Ticks, in denen Gegner da sind, er aber keinen bekämpfen kann. */
	private int idleWithEnemies;
	/** Nether-Lauf 5: Eingegraben 3 Blöcke unter dem Spawner löschte er 9 Minuten Feuer, die Lohen schwebten außer
	 * Reichweite über ihm. Nach so langer Untätigkeit stellt er sich dicht an den Spawner, auf dessen Höhe. */
	private static final int IDLE_REPOSITION_TICKS = 100;
	private boolean repositioning;

	public FarmSpawnerAction(String item, int count, float minHealth, int seconds) {
		this.item = Inv.fullId(item);
		this.count = count;
		this.minHealth = minHealth;
		this.seconds = seconds;
	}

	@Override
	public int timeoutTicks() {
		// Das eigene Zeitlimit endet mit ok; das des Runners greift nur, falls etwas hängt.
		return seconds * 20 + 600;
	}

	@Override
	public boolean cancelOnDamage() {
		return false;
	}

	@Override
	public boolean cancelOnDanger() {
		// Lohen setzen mit Feuerbällen ständig Feuer: Das darf das Farmen nicht beenden.
		return false;
	}

	private static final int STALL_TICKS = 20 * 90;
	private int lastEdgeMove = -1000;
	private int lastProgress = -1;
	private int lastProgressTick;
	/** Hütte bei wenig Leben, bevor das Ergebnis an die KI geht. */
	private ShelterAction hideout;

	@Override
	public Outcome tick(ServerPlayer bot) {
		ticks++;
		if (spawner == null) {
			spawner = EncloseSpawnerAction.findSpawner(bot);
			if (spawner == null) {
				return Outcome.failed("no_spawner", progress(bot));
			}
			// Welches Wesen kommt heraus? Drachenlauf 87 stand dreimal 90 s an einem Spawner ohne eine Lohe (stalled): vermutlich
			// ein anderer Spawner (Bastionen haben Magmawürfel-Spawner).
			if (bot.level().getBlockEntity(spawner) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity block) {
				net.minecraft.world.entity.Entity shown = block.getSpawner().getOrCreateDisplayEntity(bot.level(), spawner);
				if (shown != null && item.equals("minecraft:blaze_rod")
						&& !(shown instanceof net.minecraft.world.entity.monster.Blaze)) {
					JsonObject data = progress(bot);
					data.addProperty("spawner_mob", GameJson.entityType(shown));
					data.addProperty("hint", "this spawner makes " + GameJson.entityType(shown).replace("minecraft:", "")
							+ ", not blazes: blaze spawners are inside nether fortresses (locate blaze_spawner)");
					return Outcome.failed("wrong_spawner", data);
				}
			}
			startCount = Inv.count(bot, item);
		}
		// Wenig Leben: selbst einbauen statt der KI die Flucht zu überlassen. Drachenläufe 41, 47, 57: Nach health_low floh die KI
		// über offene Fläche, Feuerbälle trafen sie im Rücken, tot (Prompt-Regel allein reichte nicht).
		if (hideout != null) {
			Outcome hid = hideout.tick(bot);
			if (hid == null) {
				return null;
			}
			hideout = null;
			JsonObject data = progress(bot);
			data.addProperty("shelter", hid.status().name().toLowerCase(java.util.Locale.ROOT));
			if (hid.data() != null && hid.data().has("exit")) {
				data.add("exit", hid.data().get("exit"));
			}
			data.addProperty("hint", "you are walled in (shelter): eat, wait until health 20, then mine the exit and farm_spawner again");
			return Outcome.cancelled("health_low", data);
		}
		if (bot.getHealth() <= minHealth) {
			if (fighter != null) {
				endFight(bot);
			}
			PathFollower.release(bot);
			hideout = new ShelterAction(true);
			return null;
		}
		// Volles Inventar: Beute bliebe liegen (Drachenlauf 6: 53 Lohen, 0 Ruten). Erst Netherrack wegwerfen.
		if (ticks % 20 == 1 && !hasRoom(bot)) {
			int slot = Inv.findItem(bot, "minecraft:netherrack");
			if (slot >= 0) {
				bot.drop(bot.getInventory().removeItemNoUpdate(slot), false, true);
				droppedJunk = true;
			} else {
				JsonObject data = progress(bot);
				data.addProperty("hint", "inventory full, the loot stays on the ground; drop blocks you do not need, then farm_spawner again");
				return Outcome.failed("inventory_full", data);
			}
		}
		if (Inv.count(bot, item) >= count) {
			JsonObject data = progress(bot);
			data.addProperty("outcome", "enough");
			return Outcome.ok(data);
		}
		// Stillstand: 90 s ohne Kill und ohne neue Beute. Drachenlauf 12: 10 Minuten am Spawner, Lohen 3 bis 5 Blöcke
		// entfernt, kein Schlag, kein Schuss, bis zur Zeitgrenze.
		int progressNow = Inv.count(bot, item) + killed.values().stream().mapToInt(Integer::intValue).sum();
		if (progressNow != lastProgress) {
			lastProgress = progressNow;
			lastProgressTick = ticks;
		}
		if (ticks - lastProgressTick > STALL_TICKS && fighter == null && shooter == null) {
			JsonObject data = progress(bot);
			// Fehlen nur Bausteine für den Weg zum Spawner, das klar sagen: Drachenlauf 89 stand 20 Minuten mit 28 Blöcken (unter der
			// Reserve) davor, bekam immer „stalled … cannot be reached“ und holte nie Netherrack.
			if (lastPathProblem != null && lastPathProblem.startsWith("no_blocks")) {
				data.addProperty("hint", "the way to the spawner needs building blocks and you are at the reserve: collect netherrack "
						+ "(count 64, allow_dig, without allow_place) nearby, then farm_spawner again");
				return Outcome.failed("no_blocks", data);
			}
			data.addProperty("hint", "no kill and no loot for 90 s: the blazes cannot be reached or seen from here. look, then goto a "
					+ "spot level with the spawner and open to it (dig if needed), then farm_spawner again");
			return Outcome.failed("stalled", data);
		}
				if (ticks >= seconds * 20 && fighter == null && shooter == null) {
			JsonObject data = progress(bot);
			data.addProperty("outcome", "time_up");
			return Outcome.ok(data);
		}

		// An einer Kante (eigene Brücke, Festungsrand) erst zum Spawner auf festen Boden: Drachenlauf 30 begann am Ende seiner
		// Brücke, zwei Feuerbälle stießen ihn 16 Blöcke tief in Lava. Höchstens alle 10 s ein Versuch.
		// Unter Beschuss an der Kante zuerst Geländer (wie goto/shoot). Drachenlauf 54 startete am Ende seiner Brücke zum
		// Spawner, der erste Feuerball warf ihn 25 Blöcke tief.
		if (ticks % 10 == 0 && bot.onGround() && de.hannes.aiplayer.skill.Fighter.nearDrop(bot)
				&& GotoAction.shooterInSight(bot) != null) {
			GotoAction.rail(bot);
		}
		if (walker == null && !repositioning && ticks - lastEdgeMove > 200 && bot.onGround()
				&& de.hannes.aiplayer.skill.Fighter.nearDrop(bot)) {
			lastEdgeMove = ticks;
			if (fighter != null) {
				endFight(bot);
			}
			repositioning = true;
			walker = new PathFollower(Goal.near(new Pos(spawner.getX(), spawner.getY(), spawner.getZ()), 1.5), farmMoves(bot));
		}
		// Feuer an den Füßen sofort, sonst nur in ruhigen Momenten: Es brennt ihn und versperrt die Beute.
		if (ticks % FIRE_INTERVAL_TICKS == 0) {
			putOutFire(bot, fighter == null && shooter == null ? FIRE_REACH : 1.5);
		}

		if (shooter != null) {
			Outcome shot = shooter.tick(bot);
			if (shot == null) {
				return null;
			}
			shooter.release(bot);
			shooter = null;
			if (!shotTarget.isAlive()) {
				killed.merge(GameJson.entityType(shotTarget), 1, Integer::sum);
				gatherer = new ItemGatherer(shotTarget.blockPosition(), PICKUP_RADIUS, PICKUP_TICKS);
			} else if (shot.status() != de.hannes.aiplayer.protocol.Event.Status.OK) {
				skipUntil.put(shotTarget.getId(), ticks + SKIP_TICKS / 4);
			}
			shotTarget = null;
			return null;
		}

		if (bunkerTick(bot)) {
			return null;
		}

		// Wer ihn gerade schlägt, kommt zuerst dran. Drachenlauf 8: Ein Witherskelett traf ihn viermal von hinten,
		// während er auf eine unerreichbare Lohe zielte, und er schlug nie zurück.
		LivingEntity attacker = Combat.recentAttacker(bot);
		if (attacker != null && (fighter == null || fighter.target() != attacker) && shooter == null) {
			if (fighter != null) {
				endFight(bot);
			}
			if (walker != null) {
				walker = null;
				PathFollower.release(bot);
			}
			fighter = new Fighter(attacker);
		}
		// Leine: Weiter als LEASH vom Spawner lässt er das Ziel ziehen. Drachenlauf 7: Er jagte einen Gegner 40 Blöcke
		// weit in eine Gruppe Witherskelette und starb.
		if (fighter != null && (fighter.target().distanceToSqr(Vec3.atCenterOf(spawner)) > LEASH * LEASH
				|| bot.distanceToSqr(Vec3.atCenterOf(spawner)) > LEASH * LEASH)) {
			skipUntil.put(fighter.target().getId(), ticks + SKIP_TICKS);
			endFight(bot);
			return null;
		}
		if (fighter != null) {
			switch (fighter.tick(bot)) {
				case RUNNING -> {
					return null;
				}
				case KILLED -> {
					killed.merge(GameJson.entityType(fighter.target()), 1, Integer::sum);
					endFight(bot);
					gatherer = new ItemGatherer(bot.blockPosition(), PICKUP_RADIUS, PICKUP_TICKS);
				}
				case LOST, FAILED -> {
					meleeFailed.add(fighter.target().getId());
					skipUntil.put(fighter.target().getId(), ticks + SKIP_TICKS);
					endFight(bot);
				}
			}
			return null;
		}

		LivingEntity enemy = Combat.hostiles(bot, FIGHT_RADIUS).stream()
				.filter(e -> skipUntil.getOrDefault(e.getId(), 0) < ticks
						|| e instanceof Blaze && meleeFailed.contains(e.getId()) && canShoot(bot))
				.filter(e -> e.distanceToSqr(Vec3.atCenterOf(spawner)) <= LEASH * LEASH)
				// Wen er nicht sieht, lässt er links liegen und geht weiter zum Spawner.
				// Nether-Lauf 3: Jede Lohe unter dem Festungsdach brach den Weg ab, er kam nie in den Spawnerraum.
				// Nur wen er sieht: Hinter Wänden scheiterte der Nahkampf, der Bogen sofort an der Sicht, und das im
				// Wechsel (Nether-Lauf 10: 5 Minuten am Spawner ohne Kampf und ohne Schritt). Wer ihn trifft, kommt
				// ohnehin oben über recentAttacker dran.
				.filter(bot::hasLineOfSight)
				.findFirst().orElse(null);
		idleWithEnemies = enemy == null && !Combat.hostiles(bot, FIGHT_RADIUS).isEmpty() ? idleWithEnemies + 1 : 0;
		if (enemy != null) {
			repositioning = false;
			if (walker != null) {
				walker = null;
				PathFollower.release(bot);
			}
			if (gatherer != null) {
				gatherer.release(bot);
				gatherer = null;
			}
			if (fireWalker != null) {
				fireWalker = null;
				PathFollower.release(bot);
			}
			if (enemy instanceof Blaze && canShoot(bot) && (meleeFailed.contains(enemy.getId()) || outOfMelee(bot, enemy))) {
				shotTarget = enemy;
				shooter = new ShootAction(String.valueOf(enemy.getId()), SHOTS_PER_VOLLEY);
				return null;
			}
			fighter = new Fighter(enemy);
			return null;
		}

		if (gatherer != null) {
			if (gatherer.tick(bot) == ItemGatherer.State.RUNNING) {
				return null;
			}
			gatherer.release(bot);
			gatherer = null;
		}

		// Untätig mit Gegnern ringsum: dicht an den Spawner, auf seine Höhe (vor dem Feuerlöschen, sonst käme er nie dazu).
		if (idleWithEnemies > IDLE_REPOSITION_TICKS && !repositioning) {
			repositioning = true;
			idleWithEnemies = 0;
			walker = new PathFollower(Goal.near(new Pos(spawner.getX(), spawner.getY(), spawner.getZ()), 1.5),
					farmMoves(bot));
		}
		if (repositioning && walker == null) {
			repositioning = false;
		}
		if (repositioning) {
			PathFollower.State state = walker.tick(bot);
			if (state == PathFollower.State.RUNNING) {
				return null;
			}
			walker = null;
			repositioning = false;
			PathFollower.release(bot);
			return null;
		}

		// Ruhig: Feuer in der Nähe löschen, dazu notfalls ein paar Schritte gehen.
		if (fireWalker != null) {
			if (fireWalker.tick(bot) == PathFollower.State.RUNNING && ++fireWalkTicks < 100) {
				return null;
			}
			fireWalker = null;
			PathFollower.release(bot);
			putOutFire(bot, FIRE_REACH);
			return null;
		}
		BlockPos fire = Combat.nearestFire(bot, FIRE_WALK_RADIUS);
		if (fire != null && Vec3.atCenterOf(fire).distanceTo(Vec3.atCenterOf(spawner)) <= FIGHT_RADIUS) {
			if (Vec3.atCenterOf(fire).distanceTo(bot.getEyePosition()) <= FIRE_REACH) {
				putOutFire(bot, FIRE_REACH);
			} else {
				fireWalker = new PathFollower(Goal.near(new Pos(fire.getX(), fire.getY(), fire.getZ()), 3), Moves.Options.NONE);
				fireWalkTicks = 0;
			}
			return null;
		}

		// Ruhig: zurück an den Spawner und dort warten. Klappt der Weg zweimal nicht, reicht ein Platz weiter weg (3, 6, 10 Blöcke).
		// Nether-Lauf 17: Vom Festungsdach 11 Blöcke über dem Spawner scheiterte der Weg dreimal in 3 Sekunden.
		double standOff = STAND_OFF[Math.min(pathFailures / 2, STAND_OFF.length - 1)];
		if (bot.position().distanceTo(Vec3.atCenterOf(spawner)) > standOff + 1) {
			if (walker == null) {
				// Zum Spawner darf er durch Festungswände: Drachenlauf 10 kam 7 Minuten nicht in den Spawnerraum.
				walker = new PathFollower(Goal.near(new Pos(spawner.getX(), spawner.getY(), spawner.getZ()), standOff),
						farmMoves(bot));
				walkStartDistance = bot.position().distanceTo(Vec3.atCenterOf(spawner));
				walkBest = walkStartDistance;
				walkStale = 0;
			}
			PathFollower.State state = walker.tick(bot);
			double now = bot.position().distanceTo(Vec3.atCenterOf(spawner));
			if (now < walkBest - 1) {
				walkBest = now;
				walkStale = 0;
			}
			// Hängt die Wegfindung (15 s ohne einen Block Annäherung), gilt der Versuch als gescheitert. Im Test vom
			// Festungsdach über einem geschlossenen Raum stand er sonst bis zum Zeitlimit still.
			if (state == PathFollower.State.RUNNING && ++walkStale > WALK_STALE_TICKS) {
				walker.abort(bot);
				state = PathFollower.State.FAILED;
			}
			if (state == PathFollower.State.RUNNING) {
				return null;
			}
			if (state != PathFollower.State.ARRIVED) {
				lastPathProblem = walkStale > WALK_STALE_TICKS ? "no progress" : walker.failure() + (walker.hint() != null ? " (" + walker.hint() + ")" : "");
			}
			walker = null;
			PathFollower.release(bot);
			// Nur ein Versuch ohne Annäherung zählt als Fehlschlag: Beim Durchgraben bricht die Suche oft ab und klappt danach.
			boolean progressed = bot.position().distanceTo(Vec3.atCenterOf(spawner)) < walkStartDistance - 1;
			if (state != PathFollower.State.ARRIVED && !progressed && ++pathFailures >= 2 * STAND_OFF.length) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "cannot reach the spawner: " + lastPathProblem);
				return Outcome.failed("out_of_reach", data);
			}
			return null;
		}
		Combat.halt(bot);
		return null;
	}

	/** Zum Spawner: durch Festungswände und mit gesundem Leben auch 5 Blöcke tief hinunter (kostet 2 Leben). */
	private static Moves.Options farmMoves(ServerPlayer bot) {
		return new Moves.Options(true, true, true, bot.getHealth() >= 14 ? 5 : Moves.MAX_DROP);
	}

	private boolean hasRoom(ServerPlayer bot) {
		Item wanted = BuiltInRegistries.ITEM.getValue(Identifier.parse(item));
		return Inv.hasRoomFor(bot, new ItemStack(wanted));
	}

	private static boolean outOfMelee(ServerPlayer bot, LivingEntity enemy) {
		double dx = enemy.getX() - bot.getX();
		double dz = enemy.getZ() - bot.getZ();
		return Math.abs(enemy.getY() - bot.getY()) > MELEE_MAX_HEIGHT || Math.sqrt(dx * dx + dz * dz) > MELEE_MAX_DISTANCE;
	}

	/** Löscht das nächste Feuer bis {@code reach} Blöcke vom Kopf, wie ein Spieler mit einem Schlag darauf. */
	private void putOutFire(ServerPlayer bot, double reach) {
		if (Combat.putOutNearestFire(bot, reach)) {
			firesOut++;
		}
	}

	private static boolean canShoot(ServerPlayer bot) {
		return Inv.findItem(bot, "minecraft:bow") >= 0
				&& (Inv.count(bot, "minecraft:arrow") > 0 || bot.getAbilities().instabuild);
	}

	// --- Geschützter Stand (Wunsch von Hannes, 10.11.2026): Am Lohenspawner starben in der Serie 9 Bots, meist an Dauerbrand.
	// Jetzt baut der Bot 3 bis 7 Blöcke vor dem Spawner Wände links, rechts und hinter sich (Fuß- und Kopfhöhe) und ein Dach;
	// vorn zum Spawner bleibt offen. Von dort schlägt er, was in Reichweite kommt, schießt auf sichtbare Lohen und hält sonst
	// den Schild nach vorn. Beute holt er nur, wenn kein Gegner bis 10 Blöcke da ist, danach zurück in den Stand.
	private static final int BUNKER_BLOCKS = 7;
	private BlockPos bunker;
	private final java.util.ArrayDeque<BlockPos> bunkerQueue = new java.util.ArrayDeque<>();
	private PathFollower bunkerWalker;
	private int bunkerFailures;

	/** @return true, wenn der Stand diesen Tick übernommen hat */
	private boolean bunkerTick(ServerPlayer bot) {
		if (bot.level().dimension() != net.minecraft.world.level.Level.NETHER || bunkerFailures >= 3) {
			return false;
		}
		Vec3 center = Vec3.atCenterOf(spawner);
		if (bunker == null) {
			double distance = bot.position().distanceTo(center);
			if (!bot.onGround() || distance < 3 || distance > 7 || walker != null || fighter != null
					|| Inv.countScaffold(bot) < PathFollower.BLOCK_RESERVE + BUNKER_BLOCKS
					|| Math.abs(bot.getBlockY() - spawner.getY()) > 3 || !seesSpawner(bot)) {
				return false;
			}
			bunker = bot.blockPosition().immutable();
			net.minecraft.core.Direction front = net.minecraft.core.Direction.getApproximateNearest(
					center.x - bot.getX(), 0, center.z - bot.getZ());
			for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				if (side != front) {
					bunkerQueue.add(bunker.relative(side));
					bunkerQueue.add(bunker.relative(side).above());
				}
			}
			bunkerQueue.add(bunker.above(2));
			PathFollower.release(bot);
			bunkerWalker = null;
		}
		var level = bot.level();
		if (!bunkerQueue.isEmpty()) {
			for (int i = 0; i < 2 && !bunkerQueue.isEmpty(); i++) {
				BlockPos cell = bunkerQueue.poll();
				if (!level.getBlockState(cell).canBeReplaced()) {
					continue;
				}
				int slot = Inv.findScaffold(bot);
				if (slot >= 0 && de.hannes.aiplayer.skill.BlockPlacer.hasSupport(level, cell)) {
					de.hannes.aiplayer.skill.BlockPlacer.place(bot, slot, cell);
				}
			}
			return true;
		}
		boolean enemiesNear = !Combat.hostiles(bot, 10).isEmpty();
		if (!bot.blockPosition().equals(bunker)) {
			if (gatherer != null || !enemiesNear && bunkerWalker == null) {
				return false;
			}
			if (bunkerWalker == null) {
				bunkerWalker = new PathFollower(Goal.near(new Pos(bunker.getX(), bunker.getY(), bunker.getZ()), 0.4),
						Moves.Options.NONE);
			}
			PathFollower.State state = bunkerWalker.tick(bot);
			if (state == PathFollower.State.FAILED) {
				bunkerWalker = null;
				bunker = null;
				bunkerFailures++;
				return false;
			}
			if (state == PathFollower.State.ARRIVED) {
				bunkerWalker = null;
			}
			return true;
		}
		bunkerWalker = null;
		if (fighter != null) {
			endFight(bot);
		}
		Combat.halt(bot);
		LivingEntity close = Combat.hostiles(bot, 3.5).stream().filter(bot::hasLineOfSight)
				.min(java.util.Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		if (close != null) {
			Combat.holdBestWeapon(bot);
			Combat.face(bot, close);
			if (bot.getAttackStrengthScale(0.5f) >= 0.95f) {
				Combat.lowerShield(bot);
				bot.attack(close);
				bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				hits++;
				if (!close.isAlive()) {
					killed.merge(GameJson.entityType(close), 1, Integer::sum);
				}
			} else if (Combat.hasShieldReady(bot)) {
				Combat.raiseShield(bot);
			}
			return true;
		}
		LivingEntity target = Combat.hostiles(bot, FIGHT_RADIUS).stream()
				.filter(e -> e instanceof Blaze && bot.hasLineOfSight(e) && skipUntil.getOrDefault(e.getId(), 0) < ticks)
				.min(java.util.Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
		if (target != null && canShoot(bot)) {
			shotTarget = target;
			shooter = new ShootAction(String.valueOf(target.getId()), SHOTS_PER_VOLLEY);
			return true;
		}
		if (!enemiesNear) {
			// Ruhig: Beute ringsum holen (danach zurück in den Stand), sonst warten.
			if (!level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
					bot.getBoundingBox().inflate(PICKUP_RADIUS)).isEmpty()) {
				gatherer = new ItemGatherer(bot.blockPosition(), PICKUP_RADIUS, PICKUP_TICKS);
				return false;
			}
		}
		Combat.face(bot, center.x, center.y, center.z);
		if (Combat.hasShieldReady(bot) && enemiesNear) {
			Combat.raiseShield(bot);
		}
		return true;
	}

	/** Freie Sicht von den Augen auf den Spawner (der erste getroffene Block ist der Spawner). */
	private boolean seesSpawner(ServerPlayer bot) {
		var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(bot.getEyePosition(), Vec3.atCenterOf(spawner),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(spawner);
	}

	private void endFight(ServerPlayer bot) {
		hits += fighter.hits();
		fighter.release(bot);
		fighter = null;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (fighter != null) {
			fighter.release(bot);
		}
		if (shooter != null) {
			shooter.release(bot);
		}
		if (gatherer != null) {
			gatherer.release(bot);
		}
		fireWalker = null;
		PathFollower.release(bot);
		Combat.halt(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (spawner != null) {
			data.add("spawner", GameJson.pos(spawner));
		}
		data.addProperty("item", item);
		data.addProperty("have", Inv.count(bot, item));
		if (pathFailures >= 2) {
			// Er kam nicht dicht an den Spawner: Beute fällt dann oft dorthin, wo er nicht hinkommt.
			data.addProperty("stand_off", STAND_OFF[Math.min(pathFailures / 2, STAND_OFF.length - 1)]);
			data.addProperty("path_problem", lastPathProblem);
		}
		data.addProperty("gained", startCount < 0 ? 0 : Math.max(0, Inv.count(bot, item) - startCount));
		JsonObject killedJson = new JsonObject();
		killed.forEach(killedJson::addProperty);
		data.add("killed", killedJson);
		data.addProperty("hits", hits + (fighter != null ? fighter.hits() : 0));
		data.addProperty("seconds", ticks / 20);
		data.addProperty("fires_out", firesOut);
		if (droppedJunk) {
			data.addProperty("dropped_netherrack", true);
		}
		AttackAction.addFighterState(data, bot);
		return data;
	}
}
