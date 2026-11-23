package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.BlockBreaker;
import de.hannes.aiplayer.skill.BlockScan;
import de.hannes.aiplayer.skill.Fighter;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.ItemGatherer;
import de.hannes.aiplayer.skill.PathFollower;
import de.hannes.aiplayer.skill.PillarUp;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@code collect}: sucht Blöcke einer Art im Umkreis, läuft hin, baut sie ab und sammelt die
 * Beute ein, bis die gewünschte Anzahl erreicht ist. Schema: {@code schemas/commands/collect.schema.json}.
 *
 * <p>Hängt ein Block zu hoch (obere Stämme eines Baums), stellt sich der Bot darunter, baut sich
 * mit Blöcken aus dem Inventar hoch und baut seinen Turm am Ende wieder ab. Er bleibt also nicht
 * oben stehen.
 */
public final class CollectAction extends Action {
	/** Gerade wird ein Block in der Sichtlinie zum Ziel abgebaut (nur mit allow_dig). */
	private boolean clearingSight;
	private int sightClears;
	private static final long SCAN_NANOS_PER_TICK = 2_000_000;
	private static final double APPROACH_REACH = 4.2;
	private static final double GATHER_RADIUS = 4.5;
	private static final int GATHER_TICKS = 70;
	/**
	 * Mit Graben geht der Bot dicht an den Block heran: Aus der Ferne durch den Fels abgebaut, fiele die
	 * Beute in einen Hohlraum, an den er nicht herankommt.
	 */
	private static final double DIG_APPROACH_REACH = 2.5;
	private static final double DIG_BREAK_DISTANCE = 3.2;
	private static final int DIG_GATHER_TICKS = 240;
	/** So viel Zeit vor dem Zeitlimit hört er auf zu suchen, damit das Einsammeln am Ende noch drin ist. */
	private static final int FINISH_RESERVE_TICKS = 800;
	private static final double FINAL_GATHER_RADIUS = 6.0;
	private static final int FINAL_GATHER_TICKS = 500;
	private static final int MAX_PILLAR_HEIGHT = 8;
	/** Nach so vielen abgebauten Blöcken neu suchen: der Bot ist weitergezogen, die Welt hat sich geändert. */
	private static final int RESCAN_AFTER_MINED = 12;
	/** So viele unerreichbare Blöcke nacheinander, dann gibt er auf, statt jeden einzeln durchzuprobieren. */
	private static final int MAX_UNREACHABLE_STREAK = 8;
	/** Erz derselben Ader holt er auch über die Anzahl hinaus, solange es so nah liegt. */
	private static final double VEIN_MAX_DISTANCE = 6.0;

	private enum Phase {
		SCAN, CHOOSE, APPROACH, GO_BENEATH, PILLAR, BREAK, GATHER, DESCEND, FINAL_GATHER, HUNT, HUNT_GATHER
	}

	private final Predicate<BlockState> matches;
	private final String label;
	private final int requested;
	private final int radius;
	/** Was der Bot auf dem Weg zum Block an der Welt ändern darf, wenn es zu Fuß nicht geht. */
	private final Moves.Options edits;
	private boolean triedEdits;
	private int ticks;
	private boolean timeUp;
	private int dugOnWay;
	private int placedOnWay;

	private Phase phase = Phase.SCAN;
	private BlockPos anchor;
	private Map<String, Integer> before;
	private BlockScan scan;
	private List<BlockPos> candidates = new ArrayList<>();
	private final Set<BlockPos> skipped = new HashSet<>();
	private boolean everFound;
	private int mined;
	private int minedSinceScan;

	private BlockPos target;
	private PathFollower follower;
	private BlockBreaker breaker;
	/** Ein Block über dem Kopf, der beim Hochbauen im Weg ist (meist Laub). */
	private BlockBreaker clearing;
	private PillarUp pillar;
	private ItemGatherer gatherer;
	/** Eigene Turmblöcke, unterster zuerst. */
	private final Deque<BlockPos> ownPillar = new ArrayDeque<>();
	private BlockPos lastMined;
	/** Alle Abbaustellen; am Ende wird um jede herum noch einmal nach Liegengebliebenem gesucht. */
	private final List<BlockPos> minedSpots = new ArrayList<>();
	private ItemGatherer finalGatherer;
	private String failure;
	private String failureHint;
	private String pillarLeftBecause;
	/** Ob nach dem Abstieg Schluss ist oder weitergesammelt wird. */
	private boolean finishing;
	private int pillarLeftHeight;
	/** Erzblöcke, die an schon abgebaute grenzen: die Ader wird ganz abgebaut. */
	private final Set<BlockPos> vein = new HashSet<>();
	private int unreachableStreak;
	private boolean gaveUp;
	/** Der Block, der gerade abgebaut wird, wie er vorher war (danach ist dort Luft). */
	private BlockState lastState;
	/** Holz und Stein: nebenbei Tiere für Essen erlegen (Wunsch von Hannes). */
	private final boolean hunt;
	private Fighter hunter;
	private final Set<Integer> skippedAnimals = new HashSet<>();
	private int animalsKilled;
	private int nextHuntCheck;

	public CollectAction(Predicate<BlockState> matches, String label, int requested, int radius, Moves.Options edits) {
		this.edits = edits;
		this.matches = matches;
		this.label = label;
		this.requested = requested;
		this.radius = radius;
		this.hunt = matches.test(Blocks.OAK_LOG.defaultBlockState()) || matches.test(Blocks.SPRUCE_LOG.defaultBlockState())
				|| matches.test(Blocks.BIRCH_LOG.defaultBlockState()) || matches.test(Blocks.STONE.defaultBlockState())
				|| matches.test(Blocks.COBBLESTONE.defaultBlockState());
	}

	@Override
	public int timeoutTicks() {
		// Eine angefangene Erzader verlängert die Zeit: Jeder Block der Ader bekommt seine 400 Ticks. Höchstens
		// 4,5 Minuten, damit der Prompt-Cache der KI (5 Minuten) bis zum nächsten Modellaufruf nicht abläuft.
		return Math.min(5_400, 600 + Math.max(requested, mined + vein.size()) * 400);
	}

	/** Nether-Lauf 9: Auf einer Insel im Lavasee brach jedes collect netherrack sofort mit danger ab, und ohne
	 * Bausteine kam er nicht mehr weg. Die Wegfindung meidet Lava, der Abbau prüft auf nachlaufende Flüssigkeit. */
	@Override
	public boolean cancelOnDangerInNether() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		ticks++;
		if (anchor == null) {
			anchor = bot.blockPosition();
			before = Inv.counts(bot);
		}

		switch (phase) {
			case SCAN -> {
				PathFollower.release(bot);
				if (scan == null) {
					scan = new BlockScan(anchor, radius, matches);
				}
				if (scan.advance(level, SCAN_NANOS_PER_TICK)) {
					candidates = new ArrayList<>(scan.found());
					everFound |= !candidates.isEmpty();
					scan = null;
					minedSinceScan = 0;
					phase = Phase.CHOOSE;
				}
			}
			case CHOOSE -> {
				Vec3 from = bot.getEyePosition();
				vein.removeIf(pos -> skipped.contains(pos) || !matches.test(level.getBlockState(pos))
						|| from.distanceTo(Vec3.atCenterOf(pos)) > VEIN_MAX_DISTANCE);
				if (mined >= requested && vein.isEmpty()) {
					return startFinish(bot);
				}
				if (mined > 0 && ticks > timeoutTicks() - FINISH_RESERVE_TICKS) {
					timeUp = true;
					return startFinish(bot);
				}
				if (unreachableStreak >= MAX_UNREACHABLE_STREAK) {
					gaveUp = true;
					return startFinish(bot);
				}
				candidates.removeIf(pos -> skipped.contains(pos) || !matches.test(level.getBlockState(pos)));
				if (startHunt(bot)) {
					return null;
				}
				if (!vein.isEmpty()) {
					// Erst die angefangene Ader fertig machen.
					target = vein.stream()
							.min((a, b) -> Double.compare(from.distanceToSqr(Vec3.atCenterOf(a)), from.distanceToSqr(Vec3.atCenterOf(b))))
							.orElseThrow();
					follower = null;
					triedEdits = false;
					phase = !ownPillar.isEmpty() && !BlockBreaker.inReach(bot, target) ? Phase.DESCEND : Phase.APPROACH;
					finishing = false;
					return null;
				}
				if (candidates.isEmpty() || minedSinceScan >= RESCAN_AFTER_MINED) {
					if (minedSinceScan > 0) {
						phase = Phase.SCAN;
						return null;
					}
					return startFinish(bot);
				}
				Vec3 eyes = bot.getEyePosition();
				target = candidates.stream()
						.min((a, b) -> Double.compare(eyes.distanceToSqr(Vec3.atCenterOf(a)), eyes.distanceToSqr(Vec3.atCenterOf(b))))
						.orElseThrow();
				follower = null;
				triedEdits = false;
				// Vom Turm aus nicht erreichbar: erst den Turm abbauen, dann zu Fuß weiter.
				// Sonst liefe der Bot davon und ließe seine Blöcke stehen.
				if (!ownPillar.isEmpty() && !BlockBreaker.inReach(bot, target)) {
					finishing = false;
					phase = Phase.DESCEND;
					return null;
				}
				phase = Phase.APPROACH;
			}
			case APPROACH -> {
				if (closeEnough(bot) && isSettled(bot)) {
					noteEdits();
					startBreaking(bot);
					return null;
				}
				if (follower == null) {
					follower = new PathFollower(Goal.withinReach(toPos(target), approachReach()), Moves.Options.NONE);
				}
				switch (follower.tick(bot)) {
					case RUNNING -> {
					}
					case ARRIVED -> {
						noteEdits();
						startBreaking(bot);
					}
					case FAILED -> {
						noteEdits();
						if (edits.any() && !triedEdits) {
							// Zu Fuß geht es nicht: hingraben bzw. Lücken überbrücken, wenn erlaubt.
							triedEdits = true;
							follower = new PathFollower(Goal.withinReach(toPos(target), approachReach()), edits);
							return null;
						}
						// Vom Boden aus nicht zu erreichen: darunter stellen und hochbauen.
						follower = new PathFollower(Goal.beneath(toPos(target)), Moves.Options.NONE);
						phase = Phase.GO_BENEATH;
					}
				}
			}
			case GO_BENEATH -> {
				switch (follower.tick(bot)) {
					case RUNNING -> {
					}
					case ARRIVED -> {
						follower = null;
						phase = Phase.PILLAR;
					}
					case FAILED -> skipTarget();
				}
			}
			case PILLAR -> tickPillar(bot, level);
			case BREAK -> {
				PathFollower.release(bot);
				switch (breaker.tick(bot)) {
					case RUNNING -> {
					}
					case DONE -> {
						if (clearingSight) {
							clearingSight = false;
							breaker = new BlockBreaker(target);
							return null;
						}
						sightClears = 0;
						noteMined(level);
						breaker = null;
						// Vom Turm aus liegt die Beute tief unten; eingesammelt wird nach dem Abstieg.
						if (ownPillar.isEmpty()) {
							gatherer = new ItemGatherer(target, GATHER_RADIUS, edits.allowDig() ? DIG_GATHER_TICKS : GATHER_TICKS);
							phase = Phase.GATHER;
						} else {
							phase = Phase.CHOOSE;
						}
					}
					case FAILED -> {
						String reason = breaker.failure();
						String hint = breaker.hint();
						BlockPos blocker = breaker.blocker();
						breaker = null;
						// Mit allow_dig wie ein Spieler: den Block in der Sichtlinie zuerst abbauen, dann weiter am Ziel.
						if (reason.equals("no_line_of_sight") && edits.allowDig() && sightClears < 4
								&& BlockBreaker.precheck(bot, blocker) == null) {
							sightClears++;
							breaker = new BlockBreaker(blocker);
							clearingSight = true;
							return null;
						}
						if (reason.equals("wrong_tool") || reason.equals("unbreakable")) {
							// Gilt für jeden Block dieser Art: weiterzusuchen bringt nichts.
							failure = reason;
							failureHint = hint;
							return startFinish(bot);
						}
						skipTarget();
					}
				}
			}
			case GATHER -> {
				if (gatherer.tick(bot) == ItemGatherer.State.DONE) {
					if (gatherer.inventoryFull()) {
						failure = "inventory_full";
						return startFinish(bot);
					}
					phase = Phase.CHOOSE;
				}
			}
			case HUNT -> {
				Fighter.State state = hunter.tick(bot);
				if (state == Fighter.State.KILLED) {
					animalsKilled++;
					gatherer = new ItemGatherer(hunter.target().blockPosition(), HUNT_GATHER_RADIUS, GATHER_TICKS);
					hunter.release(bot);
					hunter = null;
					phase = Phase.HUNT_GATHER;
				} else if (state != Fighter.State.RUNNING) {
					skippedAnimals.add(hunter.target().getId());
					hunter.release(bot);
					hunter = null;
					phase = Phase.CHOOSE;
				}
			}
			case HUNT_GATHER -> {
				if (gatherer.tick(bot) == ItemGatherer.State.DONE) {
					phase = Phase.CHOOSE;
				}
			}
			case DESCEND -> tickDescend(bot);
			case FINAL_GATHER -> {
				if (gatherer.tick(bot) == ItemGatherer.State.DONE) {
					if (gatherer.inventoryFull() && failure == null) {
						failure = "inventory_full";
					}
					return result(bot);
				}
			}
		}
		return null;
	}

	private double approachReach() {
		return edits.allowDig() ? DIG_APPROACH_REACH : APPROACH_REACH;
	}

	private boolean closeEnough(ServerPlayer bot) {
		if (!BlockBreaker.inReach(bot, target)) {
			return false;
		}
		return !edits.allowDig() || bot.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= DIG_BREAK_DISTANCE;
	}

	/** Zählt mit, was der Bot auf dem Weg abgebaut und gesetzt hat. */
	private void noteEdits() {
		if (follower != null) {
			dugOnWay += follower.dugBlocks();
			placedOnWay += follower.placedBlocks().size();
			follower = null;
		}
	}

	private void startBreaking(ServerPlayer bot) {
		lastState = bot.level().getBlockState(target);
		PathFollower.release(bot);
		follower = null;
		breaker = new BlockBreaker(target);
		phase = Phase.BREAK;
	}

	/** Zählt den abgebauten Block und merkt sich bei Erz die angrenzenden Blöcke derselben Ader. */
	private void noteMined(ServerLevel level) {
		mined++;
		minedSinceScan++;
		if (lastState != null && lastState.is(net.minecraft.tags.BlockTags.LOGS)) {
			de.hannes.aiplayer.memory.Places.rememberArea(de.hannes.aiplayer.memory.Places.Kind.TREES, level, target, TREE_AREA);
		}
		unreachableStreak = 0;
		lastMined = target;
		minedSpots.add(target);
		candidates.remove(target);
		vein.remove(target);
		if (!isOre(level.getBlockState(target)) && !isOre(lastState)) {
			return;
		}
		for (BlockPos near : BlockPos.betweenClosed(target.offset(-1, -1, -1), target.offset(1, 1, 1))) {
			if (!skipped.contains(near) && matches.test(level.getBlockState(near))) {
				vein.add(near.immutable());
			}
		}
	}

	private static boolean isOre(BlockState state) {
		if (state == null) {
			return false;
		}
		String id = GameJson.blockId(state);
		return id.endsWith("_ore") || id.equals("minecraft:ancient_debris");
	}

	private void skipTarget() {
		unreachableStreak++;
		skipped.add(target);
		follower = null;
		phase = Phase.CHOOSE;
	}

	/** Unter dem Block: so oft springen und unterbauen, bis er in Reichweite ist. */
	private void tickPillar(ServerPlayer bot, ServerLevel level) {
		PathFollower.release(bot);
		if (clearing != null) {
			BlockBreaker.State clearingState = clearing.tick(bot);
			if (clearingState == BlockBreaker.State.RUNNING) {
				return;
			}
			boolean cleared = clearingState == BlockBreaker.State.DONE;
			if (cleared && clearing.pos().equals(target)) {
				noteMined(level);
				clearing = null;
				phase = Phase.CHOOSE;
				return;
			}
			clearing = null;
			if (!cleared) {
				skipTarget();
				return;
			}
		}
		if (pillar != null) {
			switch (pillar.tick(bot)) {
				case RUNNING -> {
				}
				case DONE -> {
					ownPillar.addLast(pillar.placed());
					pillar = null;
				}
				case FAILED -> {
					pillar = null;
					skipTarget();
				}
			}
			return;
		}

		if (!isSettled(bot)) {
			return;
		}
		if (BlockBreaker.inReach(bot, target)) {
			startBreaking(bot);
			return;
		}
		if (ownPillar.size() >= MAX_PILLAR_HEIGHT || Inv.findScaffold(bot) < 0 || target.getY() <= bot.getY()) {
			skipTarget();
			return;
		}
		// Zum Springen müssen die zwei Felder über dem Kopf frei sein; Laub und Ähnliches vorher weg.
		BlockPos above = BlockPos.containing(bot.getX(), bot.getY() + 2.2, bot.getZ());
		if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
			clearing = new BlockBreaker(above);
			return;
		}
		pillar = new PillarUp();
	}

	/** Baut den eigenen Turm von oben nach unten ab; der Bot sinkt dabei Block für Block zum Boden. */
	private void tickDescend(ServerPlayer bot) {
		PathFollower.release(bot);
		if (breaker != null) {
			switch (breaker.tick(bot)) {
				case RUNNING -> {
					return;
				}
				case DONE -> {
					ownPillar.removeLast();
					breaker = null;
				}
				// Lässt sich der Turm nicht sicher abbauen, bleibt der Rest stehen; das Ergebnis sagt es.
				case FAILED -> {
					pillarLeftBecause = breaker.failure();
					pillarLeftHeight = ownPillar.size();
					breaker = null;
					ownPillar.clear();
				}
			}
		}
		if (!isSettled(bot)) {
			return;
		}
		BlockPos below = BlockPos.containing(bot.getX(), bot.getY() - 0.5, bot.getZ());
		// Direkt nach dem Abbau steht der Bot einen Tick lang noch „auf“ dem Loch: warten, bis er gelandet ist.
		if (bot.level().getBlockState(below).getCollisionShape(bot.level(), below).isEmpty()) {
			return;
		}
		if (!ownPillar.isEmpty() && ownPillar.peekLast().equals(below)) {
			breaker = new BlockBreaker(below);
			return;
		}
		if (!ownPillar.isEmpty()) {
			// Der Bot steht nicht mehr auf seinem Turm; die restlichen Blöcke bleiben stehen.
			pillarLeftBecause = "left_pillar";
			pillarLeftHeight = ownPillar.size();
			ownPillar.clear();
		}
		// Unten angekommen: einsammeln, was vom Turm aus heruntergefallen ist.
		gatherer = finishing ? finalGatherer(bot) : new ItemGatherer(bot.blockPosition(), FINAL_GATHER_RADIUS, GATHER_TICKS * 2);
		phase = finishing ? Phase.FINAL_GATHER : Phase.GATHER;
	}

	private Outcome startFinish(ServerPlayer bot) {
		PathFollower.release(bot);
		follower = null;
		if (!ownPillar.isEmpty()) {
			finishing = true;
			phase = Phase.DESCEND;
			return null;
		}
		if (mined > 0 && failure == null) {
			gatherer = finalGatherer(bot);
			phase = Phase.FINAL_GATHER;
			return null;
		}
		return result(bot);
	}

	/** Sucht um den Standort und um jede Abbaustelle: Beute fällt auch dort hin, wo der Bot nie stand. */
	private ItemGatherer finalGatherer(ServerPlayer bot) {
		List<BlockPos> spots = new ArrayList<>(minedSpots);
		spots.add(bot.blockPosition());
		finalGatherer = new ItemGatherer(spots, FINAL_GATHER_RADIUS, FINAL_GATHER_TICKS);
		return finalGatherer;
	}

	/** Baumstellen näher als so viele Blöcke gelten als eine. */
	private static final int TREE_AREA = 32;

	private boolean lastStateIsLogSearch() {
		return matches.test(Blocks.OAK_LOG.defaultBlockState()) || matches.test(Blocks.SPRUCE_LOG.defaultBlockState())
				|| matches.test(Blocks.BIRCH_LOG.defaultBlockState());
	}

	/** Die nächsten gemerkten Baumstellen außerhalb des Suchradius, für {@code none_found} bei Holz. */
	private com.google.gson.JsonArray knownTrees(ServerPlayer bot) {
		return de.hannes.aiplayer.memory.Places.treesJson(bot, radius, 3);
	}

	private static final double HUNT_RADIUS = 16;
	private static final double HUNT_GATHER_RADIUS = 4;
	private static final int HUNT_CHECK_TICKS = 40;
	/** Nur mit genug Leben, sonst geht Sammeln vor (und der Begleitprozess entscheidet). */
	private static final float HUNT_MIN_HEALTH = 10;

	/** Erwachsenes Nutztier bis {@link #HUNT_RADIUS} in der Oberwelt: Kampf beginnen. Nicht vom eigenen Turm aus. */
	private boolean startHunt(ServerPlayer bot) {
		if (!hunt || !ownPillar.isEmpty() || ticks < nextHuntCheck || bot.getHealth() < HUNT_MIN_HEALTH
				|| bot.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			return false;
		}
		nextHuntCheck = ticks + HUNT_CHECK_TICKS;
		net.minecraft.world.entity.animal.Animal prey = bot.level().getEntitiesOfClass(
						net.minecraft.world.entity.animal.Animal.class, bot.getBoundingBox().inflate(HUNT_RADIUS),
						animal -> animal.isAlive() && !animal.isBaby() && !skippedAnimals.contains(animal.getId())
								&& FOOD_ANIMALS.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath()))
				.stream().min(java.util.Comparator.comparingDouble(animal -> animal.distanceToSqr(bot))).orElse(null);
		if (prey == null) {
			return false;
		}
		if (follower != null) {
			follower.abort(bot);
			follower = null;
		}
		hunter = new Fighter(prey);
		phase = Phase.HUNT;
		return true;
	}

	private static final Set<String> FOOD_ANIMALS = Set.of("cow", "pig", "sheep", "chicken", "rabbit", "mooshroom");

	private Outcome result(ServerPlayer bot) {
		JsonObject data = progress(bot);
		if (failure != null) {
			if (failureHint != null) {
				data.addProperty("hint", failureHint);
			}
			return Outcome.failed(failure, data);
		}
		if (mined == 0) {
			if (gaveUp) {
				data.addProperty("hint", MAX_UNREACHABLE_STREAK + " blocks in a row unreachable; try another place, a cave, or dig down");
			}
			if (lastStateIsLogSearch()) {
				data.add("known_trees", knownTrees(bot));
			}
			return Outcome.failed(everFound ? "none_reachable" : "none_found", data);
		}
		return Outcome.ok(data);
	}

	@Override
	public void release(ServerPlayer bot) {
		if (breaker != null) {
			breaker.abort(bot);
		}
		if (clearing != null) {
			clearing.abort(bot);
		}
		if (follower != null) {
			follower.abort(bot);
		}
		if (gatherer != null) {
			gatherer.release(bot);
		}
		if (hunter != null) {
			hunter.release(bot);
		}
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("block", label);
		data.addProperty("mined", mined);
		data.addProperty("requested", requested);
		data.addProperty("unreachable", skipped.size());
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
		}
		data.add("held_item", Inv.heldJson(bot));
		if (timeUp) {
			data.addProperty("stopped_because", "time_limit");
		} else if (gaveUp) {
			data.addProperty("stopped_because", "unreachable_streak");
		}
		if (animalsKilled > 0) {
			data.addProperty("animals_killed", animalsKilled);
		}
		if (mined > requested) {
			data.addProperty("vein_extra", mined - requested);
		}
		if (dugOnWay > 0) {
			data.addProperty("dug_on_the_way", dugOnWay);
		}
		if (placedOnWay > 0) {
			data.addProperty("placed_on_the_way", placedOnWay);
		}
		if (finalGatherer != null && finalGatherer.leftBehind() > 0) {
			data.addProperty("items_left", finalGatherer.leftBehind());
			data.addProperty("items_left_reason", finalGatherer.leftBehindReason());
		}
		if (!ownPillar.isEmpty()) {
			// Nur bei Abbruch von außen: der Bot steht noch auf seinem Turm.
			data.addProperty("standing_on_own_pillar", ownPillar.size());
		} else if (pillarLeftBecause != null) {
			data.addProperty("standing_on_own_pillar", pillarLeftHeight);
			data.addProperty("pillar_not_removed", pillarLeftBecause);
		}
		return data;
	}

	private static boolean isSettled(ServerPlayer bot) {
		return bot.onGround() || bot.isInWater() || bot.onClimbable();
	}

	private static Pos toPos(BlockPos pos) {
		return new Pos(pos.getX(), pos.getY(), pos.getZ());
	}
}
