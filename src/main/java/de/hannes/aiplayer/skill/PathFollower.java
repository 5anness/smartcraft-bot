package de.hannes.aiplayer.skill;

import net.minecraft.server.level.ServerLevel;
import de.hannes.aiplayer.nav.CellSource;
import net.minecraft.world.level.block.Blocks;
import de.hannes.aiplayer.nav.Cell;
import de.hannes.aiplayer.nav.Doors;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.PathSearch;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.nav.WorldCells;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bringt den Bot zu einem Ziel: sucht den Weg, steuert Schritt für Schritt und passt auf.
 * Einmal pro Tick {@link #tick} aufrufen, bevor der Bot selbst tickt.
 *
 * <p>Gesteuert wird wie bei einem Spieler über Blickrichtung, „vorwärts“ und „springen“;
 * Physik und Kollision rechnet das Spiel. Mit den passenden {@link Moves.Options} baut der Bot
 * unterwegs Blöcke ab, überbrückt Lücken und baut sich hoch.
 */
public final class PathFollower {
	public enum State {
		RUNNING, ARRIVED, FAILED
	}

	/** Zeit, die die Wegsuche pro Tick höchstens bekommt. Ein Tick hat 50 ms. */
	private static final long SEARCH_NANOS_PER_TICK = 2_000_000;
	/** Ein Teilstück lohnt nur, wenn es dem Ziel deutlich näher kommt. */
	private static final double MIN_SEGMENT_GAIN = 4.0;
	private static final int STUCK_TICKS = 40;
	/** Im tiefen Wasser: so weit sucht der Bot nach einem Ufer, und so lange schwimmt er höchstens hin. */
	private static final int SHORE_RADIUS = 8;
	private static final int MAX_SWIM_TICKS = 300;
	private static final int MAX_REPLANS_WITHOUT_PROGRESS = 3;
	private static final int SAFETY_BLOCK_TICKS = 10;
	private static final int MAX_EDIT_ATTEMPTS_PER_STEP = 3;
	private static final double WAYPOINT_RADIUS = 0.3;
	/** So nah muss der Bot an einer Tür sein, damit er sie öffnet. */
	private static final double DOOR_REACH = 2.0;
	/** Sprinten nur auf geraden, ebenen Stücken: so viele Felder voraus in gleicher Richtung. */
	private static final int SPRINT_STRAIGHT_STEPS = 3;
	/** Wie im Spiel: Unter 7 Hunger kann niemand sprinten. */
	private static final int MIN_FOOD_TO_SPRINT = 7;
	private static final double MAX_DEVIATION = 3.5;

	private Goal goal;
	/** Erster Start dieser Strecke; die Nether-Suche hält sich an die Linie von hier zum Ziel. */
	private Pos corridorFrom;
	private final Moves.Options options;

	private PathSearch search;
	/** Die nächste bzw. die laufende Suche geht direkt aufs Ziel zu (zweiter Versuch nach search_stalled). */
	private boolean directNext;
	private boolean directSearch;
	/** Gewicht der direkten Suche: höher als die Kosten eines Schritts mit zwei abgebauten Blöcken (17). */
	private static final double DIRECT_WEIGHT = 20;
	private List<Pos> path = List.of();
	/** Geländer neben frisch gebauten Brückenblöcken über einem Abgrund. Nether-Lauf 2: Ein Pfeil stieß den Bot von
	 * der 1 Block breiten Brücke, er fiel 24 Blöcke tief. */
	private final java.util.ArrayDeque<BlockPos> railQueue = new java.util.ArrayDeque<>();
	private int index;
	private boolean pathReachesGoal;
	/** Entfernung zum Ziel am Ende des letzten Teilstücks; ein neues Teilstück muss näher herankommen. */
	private double lastSegmentDistance = Double.POSITIVE_INFINITY;

	private double bestWaypointDistance;
	private int ticksWithoutProgress;
	private int swimTicks;
	private int replansWithoutProgress;
	private int safetyBlockedTicks;

	// Umbauten für den nächsten Schritt: erst abbauen, dann Boden setzen oder hochbauen.
	private final Deque<BlockPos> digQueue = new ArrayDeque<>();
	private BlockBreaker breaker;
	private BlockPos bridgeAt;
	private boolean pillarPending;
	private PillarUp pillar;
	private int editAttempts;
	private final List<BlockPos> placedBlocks = new ArrayList<>();
	private int dugBlocks;

	private State state = State.RUNNING;
	private String failure;
	private String hint;
	private Pos closest;
	private double closestDistance;
	private int searchedNodes;
	private int replans;
	private long peakSearchNanos;

	public PathFollower(Goal goal, Moves.Options options) {
		this.goal = goal;
		this.options = options;
	}

	/** Neues Ziel im Lauf, z. B. weil sich das verfolgte Wesen bewegt hat. */
	public void retarget(Goal newGoal) {
		goal = newGoal;
		lastSegmentDistance = Double.POSITIVE_INFINITY;
		replansWithoutProgress = 0;
		state = State.RUNNING;
		failure = null;
		hint = null;
		replan();
	}

	public Goal goal() {
		return goal;
	}

	/** Grund aus der festen Liste: {@code no_path}, {@code stuck} oder {@code no_blocks}. */
	public String failure() {
		return failure;
	}

	/** Genauer, woran es lag, z. B. {@code enclosed} oder {@code blocked_drop_ahead}. */
	public String hint() {
		return hint;
	}

	/** Bei {@code no_path}: das erreichbare Feld, das dem Ziel am nächsten liegt. */
	public Pos closest() {
		return closest;
	}

	public double closestDistance() {
		return closestDistance;
	}

	public int searchedNodes() {
		return searchedNodes;
	}

	public int replans() {
		return replans;
	}

	/** Längste Zeit, die die Wegsuche in einem einzelnen Tick gebraucht hat. */
	public double peakSearchMillis() {
		return peakSearchNanos / 1_000_000.0;
	}

	/** Blöcke, die der Bot unterwegs gesetzt hat. */
	public List<BlockPos> placedBlocks() {
		return placedBlocks;
	}

	public int dugBlocks() {
		return dugBlocks;
	}

	public State tick(ServerPlayer bot) {
		if (state != State.RUNNING) {
			release(bot);
			return state;
		}
		WorldCells cells = new WorldCells(bot.level()).digFortress(options.digFortress());

		if (!isEditing() && goal.distance(bot.getX(), bot.getY(), bot.getZ()) <= 0 && isSettled(bot)) {
			return finish(bot, State.ARRIVED, null, null);
		}

		if (search == null && index >= path.size()) {
			if (!startSearch(bot, cells)) {
				return state;
			}
		}
		if (search != null) {
			release(bot);
			continueSearch(bot);
			return state;
		}
		if (isEditing()) {
			tickEdits(bot);
			return state;
		}

		Pos waypoint = path.get(index);
		CellSource view = magmaView != null ? magmaView : cells;
		if (Moves.kind(view, waypoint.x(), waypoint.y(), waypoint.z()) == Moves.Kind.NONE || columnBlocked(bot, cells, waypoint)) {
			// Der nächste Schritt geht (noch) nicht: entweder war er als Umbau geplant, oder die Welt
			// hat sich seit der Suche geändert.
			release(bot);
			if (options.any() && ++editAttempts <= MAX_EDIT_ATTEMPTS_PER_STEP && planEdits(bot, cells, waypoint)) {
				return state;
			}
			return replanOrFail(bot, "blocked_path_changed");
		}
		// Sicherheitsnetz: Auch der übernächste Schritt muss jetzt noch begehbar sein (Block abgebaut,
		// Lava geflossen). Mit Umbauten wird stattdessen jeder Schritt einzeln beim Betreten geprüft.
		if (!options.any() && index + 1 < path.size()) {
			Pos after = path.get(index + 1);
			if (Moves.kind(view, after.x(), after.y(), after.z()) == Moves.Kind.NONE) {
				return replanOrFail(bot, "blocked_path_changed");
			}
		}

		double dx = waypoint.x() + 0.5 - bot.getX();
		double dz = waypoint.z() + 0.5 - bot.getZ();
		double dy = waypoint.y() - bot.getY();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		boolean inWater = bot.isInWater();

		// Geschlossene Tür oder Zauntor im nächsten Feld: aufmachen wie ein Spieler, dann weiter.
		if (horizontal < DOOR_REACH && openDoorAt(bot, waypoint)) {
			release(bot);
			return state;
		}

		if (horizontal > MAX_DEVIATION) {
			// Weggestoßen oder abgerutscht: vom neuen Standort aus neu suchen.
			return replanOrFail(bot, "pushed_off_path");
		}

		boolean lastWaypoint = index == path.size() - 1;
		double verticalSlack = inWater || bot.onClimbable() ? 1.2 : 0.75;
		if (horizontal < (lastWaypoint ? 0.25 : WAYPOINT_RADIUS) && Math.abs(dy) < verticalSlack) {
			index++;
			bestWaypointDistance = Double.POSITIVE_INFINITY;
			ticksWithoutProgress = 0;
			replansWithoutProgress = 0;
			editAttempts = 0;
			if (index >= path.size()) {
				release(bot);
				if (pathReachesGoal) {
					return finish(bot, State.ARRIVED, null, null);
				}
				// Teilstück geschafft; im nächsten Tick von hier aus weitersuchen.
			}
			return state;
		}

		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
		boolean jump;
		float forward = 1.0f;
		if (inWater) {
			// Im Wasser hält „springen“ oben; zum Abtauchen loslassen.
			jump = dy > -0.3;
		} else if (bot.onClimbable()) {
			// An der Leiter klettert „springen“ hinauf; ohne Taste rutscht der Bot langsam hinunter.
			// Liegt das nächste Feld senkrecht darüber oder darunter, nicht seitlich von der Leiter laufen.
			jump = dy > 0.1;
			if (horizontal < 0.25) {
				forward = 0;
			}
		} else {
			// Halbe Blöcke (Platten, Treppen) nimmt der Bot im Laufen, erst darüber springt er.
			jump = dy > 0.6 && bot.onGround() && horizontal < 1.4;
		}

		// Sicherheitsnetz: Führt der nächste Schritt irgendwohin, wo der Weg gar nicht hin will
		// und es tief hinunter oder in Lava geht, bleibt der Bot stehen.
		if (bot.onGround() && horizontal > 0.05 && stepsIntoHazard(bot, cells, dx / horizontal, dz / horizontal)) {
			forward = 0;
			if (++safetyBlockedTicks > SAFETY_BLOCK_TICKS) {
				safetyBlockedTicks = 0;
				return replanOrFail(bot, "blocked_drop_ahead");
			}
		} else {
			safetyBlockedTicks = 0;
		}

		double distance = Math.sqrt(horizontal * horizontal + dy * dy);
		if (distance < bestWaypointDistance - 0.05) {
			bestWaypointDistance = distance;
			ticksWithoutProgress = 0;
		} else if (++ticksWithoutProgress > STUCK_TICKS) {
			return replanOrFail(bot, "no_progress");
		} else if (ticksWithoutProgress == STUCK_TICKS / 2 && bot.onGround()) {
			// Halbe Frist ohne Fortschritt: ein Sprung hilft über kleine Kanten.
			jump = true;
		}

		steer(bot, yaw, forward, jump);
		bot.setSprinting(forward > 0 && !inWater && bot.onGround() && !bot.isUsingItem()
				&& bot.getFoodData().getFoodLevel() >= MIN_FOOD_TO_SPRINT && straightAhead());
		return state;
	}

	/** Ob die nächsten Felder des Wegs in einer geraden Linie auf gleicher Höhe liegen. */
	private boolean straightAhead() {
		// Grob geradeaus reicht (Abweichung bis 45°, je Schritt höchstens 1 Block hoch oder runter): Wege der Wegsuche laufen
		// im Gelände im Zickzack und auf und ab. Hannes' Lauf (alina, 03.11.): mit „genau gerade, gleiche Höhe“ sprintete der
		// Bot fast nie und lief langsam.
		if (index == 0 || index + SPRINT_STRAIGHT_STEPS > path.size()) {
			return false;
		}
		Pos previous = path.get(index - 1);
		Pos first = path.get(index);
		double dirX = first.x() - previous.x();
		double dirZ = first.z() - previous.z();
		double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
		if (length < 0.5 || Math.abs(first.y() - previous.y()) > 1) {
			return false;
		}
		Pos last = first;
		for (int i = 1; i < SPRINT_STRAIGHT_STEPS; i++) {
			Pos next = path.get(index + i);
			double stepX = next.x() - last.x();
			double stepZ = next.z() - last.z();
			double stepLength = Math.sqrt(stepX * stepX + stepZ * stepZ);
			if (stepLength < 0.5 || Math.abs(next.y() - last.y()) > 1
					|| (stepX * dirX + stepZ * dirZ) / (stepLength * length) < 0.7) {
				return false;
			}
			last = next;
		}
		return true;
	}

	/** Lässt alle Tasten los. Nach dem Ende der Aktion aufrufen. */
	public static void release(ServerPlayer bot) {
		bot.zza = 0;
		bot.xxa = 0;
		bot.setJumping(false);
		bot.setSprinting(false);
	}

	/** Beim Abbruch von außen: angefangenen Abbau sauber beenden. */
	public void abort(ServerPlayer bot) {
		if (breaker != null) {
			breaker.abort(bot);
		}
		release(bot);
	}

	private static void steer(ServerPlayer bot, float yaw, float forward, boolean jump) {
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setXRot(0);
		bot.zza = forward;
		bot.xxa = 0;
		bot.setJumping(jump);
	}

	/** Öffnet eine geschlossene Holztür oder ein Zauntor im Fuß- oder Kopffeld des Wegpunkts. */
	private static boolean openDoorAt(ServerPlayer bot, Pos waypoint) {
		for (BlockPos pos : new BlockPos[]{new BlockPos(waypoint.x(), waypoint.y(), waypoint.z()),
				new BlockPos(waypoint.x(), waypoint.y() + 1, waypoint.z())}) {
			BlockState door = bot.level().getBlockState(pos);
			if (Doors.isClosed(door)) {
				Vec3 center = Vec3.atCenterOf(pos);
				bot.lookAt(EntityAnchorArgument.Anchor.EYES, center);
				boolean sneaking = bot.isShiftKeyDown();
				bot.setShiftKeyDown(false);
				bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(center, Direction.getApproximateNearest(bot.getX() - center.x, 0, bot.getZ() - center.z), pos, false));
				bot.setShiftKeyDown(sneaking);
				bot.swing(InteractionHand.MAIN_HAND, true);
				return true;
			}
		}
		return false;
	}

	private boolean isEditing() {
		return breaker != null || !digQueue.isEmpty() || bridgeAt != null || pillarPending || !railQueue.isEmpty();
	}

	/**
	 * Legt fest, was für den Schritt auf {@code waypoint} umgebaut werden muss.
	 *
	 * @return {@code false}, wenn der Schritt mit den erlaubten Mitteln nicht zu schaffen ist
	 */
	/**
	 * Ein Schritt nach unten, bei dem zwischen Bot und Landefeld noch ein Block steht (Decke eines Raums, Boden unter den
	 * Füßen). Das Landefeld selbst ist dann schon begehbar, deshalb fiel das ohne diese Prüfung nicht auf.
	 */
	private boolean columnBlocked(ServerPlayer bot, WorldCells cells, Pos waypoint) {
		if (!options.allowDig()) {
			return false;
		}
		Pos here = standingPos(bot, cells);
		if (here == null || waypoint.y() >= here.y() - 1) {
			return false;
		}
		boolean sameColumn = waypoint.x() == here.x() && waypoint.z() == here.z();
		int top = sameColumn ? here.y() - 1 : here.y() + 1;
		for (int yy = top; yy > waypoint.y(); yy--) {
			if (!cells.at(waypoint.x(), yy, waypoint.z()).passable()) {
				return true;
			}
		}
		return false;
	}

	private boolean planEdits(ServerPlayer bot, WorldCells cells, Pos waypoint) {
		Pos here = standingPos(bot, cells);
		// Hinunter auch mehrere Blöcke (Sprung nach dem Freiräumen, bis zur erlaubten Fallhöhe des Wegs).
		if (here == null || Math.abs(waypoint.x() - here.x()) + Math.abs(waypoint.z() - here.z()) > 1
				|| waypoint.y() > here.y() + 1 || waypoint.y() < here.y() - options.maxDrop()) {
			return false;
		}
		List<BlockPos> digs = new ArrayList<>();
		BlockPos bridge = null;
		boolean pillarUp = false;
		boolean possible = true;

		if (waypoint.x() == here.x() && waypoint.z() == here.z() && waypoint.y() < here.y()) {
			// Senkrecht hinunter: die Spalte unter den Füßen bis zur Landung abbauen, von oben nach unten.
			if (!options.allowDig()) {
				return false;
			}
			for (int yy = here.y() - 1; yy >= waypoint.y(); yy--) {
				possible &= addDig(cells, digs, here.x(), yy, here.z());
			}
		} else if (waypoint.x() == here.x() && waypoint.z() == here.z()) {
			if (waypoint.y() != here.y() + 1 || !options.allowPlace()) {
				return false;
			}
			possible = addDig(cells, digs, here.x(), here.y() + 2, here.z());
			pillarUp = true;
		} else {
			// Von oben nach unten abbauen, damit nichts auf den Bot rutscht, während er darunter gräbt.
			if (waypoint.y() > here.y()) {
				possible &= addDig(cells, digs, here.x(), here.y() + 2, here.z());
			}
			if (waypoint.y() < here.y()) {
				// Von oben nach unten die ganze Spalte bis zur Landung (bei einer Stufe zwei Felder, beim Sprung mehr).
				for (int yy = here.y() + 1; yy > waypoint.y() + 1; yy--) {
					possible &= addDig(cells, digs, waypoint.x(), yy, waypoint.z());
				}
			}
			possible &= addDig(cells, digs, waypoint.x(), waypoint.y() + 1, waypoint.z());
			possible &= addDig(cells, digs, waypoint.x(), waypoint.y(), waypoint.z());
			if (cells.at(waypoint.x(), waypoint.y() - 1, waypoint.z()).passable()) {
				if (!options.allowPlace()) {
					return false;
				}
				bridge = new BlockPos(waypoint.x(), waypoint.y() - 1, waypoint.z());
			}
		}
		if (!possible || digs.isEmpty() && bridge == null && !pillarUp) {
			return false;
		}
		digQueue.addAll(digs);
		bridgeAt = bridge;
		pillarPending = pillarUp;
		return true;
	}

	private boolean addDig(WorldCells cells, List<BlockPos> digs, int x, int y, int z) {
		if (cells.at(x, y, z).passable()) {
			return true;
		}
		if (!options.allowDig() || !cells.diggable(x, y, z)) {
			return false;
		}
		digs.add(new BlockPos(x, y, z));
		return true;
	}

	private void tickEdits(ServerPlayer bot) {
		release(bot);
		if (breaker == null && !digQueue.isEmpty()) {
			breaker = new BlockBreaker(digQueue.poll(), options.maxDrop());
		}
		if (breaker != null) {
			switch (breaker.tick(bot)) {
				case RUNNING -> {
				}
				case DONE -> {
					dugBlocks++;
					breaker = null;
				}
				case FAILED -> {
					String why = "dig_failed_" + breaker.failure();
					clearEdits();
					replanOrFail(bot, why);
				}
			}
			return;
		}
		// Reserve: Die letzten BLOCK_RESERVE Bausteine verbaut die Wegführung nie, egal welcher Befehl sie benutzt. Drachenlauf 27:
		// goto hielt mit few_blocks an, danach verbaute ein collect mit allow_place 319 Blöcke, der Bot strandete über 24 Blöcken Leere.
		if ((bridgeAt != null || pillarPending && pillar == null) && Inv.countScaffold(bot) <= BLOCK_RESERVE) {
			clearEdits();
			finish(bot, State.FAILED, "no_blocks", "only " + Inv.countScaffold(bot) + " building blocks left, kept as reserve "
					+ "(lava, way back): collect netherrack/cobblestone without allow_place first");
			return;
		}
		if (bridgeAt != null) {
			int slot = Inv.findScaffold(bot);
			if (slot < 0) {
				clearEdits();
				finish(bot, State.FAILED, "no_blocks", "nothing to build with");
				return;
			}
			String problem = BlockPlacer.place(bot, slot, bridgeAt);
			if (problem != null) {
				clearEdits();
				replanOrFail(bot, "place_failed_" + problem);
				return;
			}
			placedBlocks.add(bridgeAt);
			queueRails(bot, bridgeAt.above());
			bridgeAt = null;
			return;
		}
		if (!railQueue.isEmpty()) {
			BlockPos rail = railQueue.poll();
			int slot = Inv.findScaffold(bot);
			// Ein Geländer ist Zugabe: Klappt es nicht, geht es ohne weiter. Die Reserve bleibt unangetastet.
			if (slot >= 0 && Inv.countScaffold(bot) > BLOCK_RESERVE && BlockPlacer.place(bot, slot, rail) == null) {
				placedBlocks.add(rail);
			}
			return;
		}
		if (pillarPending) {
			if (pillar == null) {
				pillar = new PillarUp();
			}
			switch (pillar.tick(bot)) {
				case RUNNING -> {
				}
				case DONE -> {
					placedBlocks.add(pillar.placed());
					queuePillarRails(bot, pillar.placed());
					pillar = null;
					pillarPending = false;
				}
				case FAILED -> {
					String why = pillar.failure();
					clearEdits();
					if (why.equals("no_blocks")) {
						finish(bot, State.FAILED, "no_blocks", "nothing to build with");
					} else {
						replanOrFail(bot, "pillar_failed_" + why);
					}
				}
			}
		}
	}

	/** Unter so vielen Bausteinen kein Geländer mehr: Die Brücke selbst geht vor (Nether-Lauf 4: Bausteine früh alle). */
	private static final int MIN_BLOCKS_FOR_RAILS = 96;

	/**
	 * Seitlich neben dem Brückenfeld {@code feet}: Felder über einem Abgrund (3 Blöcke frei darunter), die der Weg nicht
	 * braucht. Erst ein Block neben dem Brückenblock (sonst hätte das Geländer nichts zum Anlegen), dann das Geländer darauf.
	 */
	private void queueRails(ServerPlayer bot, BlockPos feet) {
		queueRails(bot, feet, railQueue, path.subList(Math.min(index, path.size()), path.size()));
	}

	/** Auch für {@code tunnel}: hängt die Blöcke für das Geländer neben {@code feet} an {@code queue} an. */
	public static void queueRails(ServerPlayer bot, BlockPos feet, java.util.Deque<BlockPos> queue, List<Pos> ahead) {
		if (Inv.scaffoldCount(bot) < MIN_BLOCKS_FOR_RAILS || !rangedThreatNear(bot)) {
			return;
		}
		net.minecraft.world.level.Level level = bot.level();
		int dx = Integer.signum(feet.getX() - bot.getBlockX());
		int dz = Integer.signum(feet.getZ() - bot.getBlockZ());
		if (dx != 0 && dz != 0 || dx == 0 && dz == 0) {
			return;
		}
		for (int sign : new int[]{-1, 1}) {
			BlockPos rail = feet.offset(dz * sign, 0, dx * sign);
			boolean overDrop = true;
			for (int down = 0; down <= 3 && overDrop; down++) {
				BlockPos cell = rail.below(down);
				overDrop = level.getBlockState(cell).getCollisionShape(level, cell).isEmpty() && level.getFluidState(cell).isEmpty();
			}
			if (overDrop && !ahead.contains(new Pos(rail.getX(), rail.getY(), rail.getZ()))
					&& !ahead.contains(new Pos(rail.getX(), rail.getY() - 1, rail.getZ()))) {
				queue.add(rail.below());
				queue.add(rail);
				// Zweite Lage auf Kopfhöhe: Drachenlauf 77, ein Lohen-Feuerball warf ihn über das Brückengeländer, 25 Blöcke Sturz.
				if (!ahead.contains(new Pos(rail.getX(), rail.getY() + 1, rail.getZ()))) {
					queue.add(rail.above());
				}
			}
		}
	}

	/**
	 * Sicher hochbauen (Wunsch von Hannes, 28.10.2026): Steht nach einem Säulenschritt eine Gefahr in der Nähe, rundum ein
	 * Geländer auf Fußhöhe, so steht der Bot in einem Schacht. Die erste Stufe braucht je Seite einen Stützblock neben der
	 * Säule, danach trägt das Geländer der Stufe darunter. End-Läufe 4 und 6 und Hannes' Nether-Lauf: von der 1×1-Säule
	 * gestoßen (Drache, Enderman), tot.
	 */
	private void queuePillarRails(ServerPlayer bot, BlockPos placed) {
		if (Inv.scaffoldCount(bot) < MIN_BLOCKS_FOR_PILLAR_RAILS || !pillarThreat(bot)) {
			return;
		}
		// Nur die obersten Stufen: Dort steht und schießt er. End-Lauf 12: ein Turm von Höhe 62 bis 94 mit Geländer auf
		// jeder Stufe brauchte die Bausteine auf, oben (wo der Drache ihn stieß) war keins mehr.
		// Mit lebendem Drachen jede Stufe: Er greift beim Hochbauen etwa alle 6 s an und warf ihn achtmal von der Säule
		// (End-Lauf 19).
		boolean dragon = !bot.level().getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
				bot.getBoundingBox().inflate(200), net.minecraft.world.entity.LivingEntity::isAlive).isEmpty();
		if (!dragon && !path.isEmpty() && path.get(path.size() - 1).y() - placed.getY() > PILLAR_RAIL_TOP_LEVELS) {
			return;
		}
		net.minecraft.world.level.Level level = bot.level();
		BlockPos feet = placed.above();
		List<Pos> ahead = path.subList(Math.min(index, path.size()), path.size());
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos rail = feet.relative(dir);
			// Den Weg nicht zubauen: Geht es hier seitlich weiter, bleibt diese Seite offen.
			if (ahead.contains(new Pos(rail.getX(), rail.getY(), rail.getZ()))
					|| ahead.contains(new Pos(rail.getX(), rail.getY() + 1, rail.getZ()))
					|| !level.getBlockState(rail).getCollisionShape(level, rail).isEmpty()) {
				continue;
			}
			if (level.getBlockState(rail.below()).getCollisionShape(level, rail.below()).isEmpty()) {
				railQueue.add(rail.below());
			}
			railQueue.add(rail);
		}
	}

	private static final int MIN_BLOCKS_FOR_PILLAR_RAILS = 24;
	/** So viele Bausteine verbaut die Wegführung nie (Rückweg, Lava-Insel). */
	public static final int BLOCK_RESERVE = 32;
	/** Zuschlag je Brückenblock im Nether: teurer als Graben (8), damit der Weg durch Netherrack führt, wo es einen gibt. */
	private static final double NETHER_BRIDGE_EXTRA = 14;
	private static final int PILLAR_RAIL_TOP_LEVELS = 6;

	/** Gefahr für einen Bot auf einer Säule: Drache im End, Schütze oder Ghast, Feind (auch Enderman) bis 16 Blöcke. */
	private static boolean pillarThreat(ServerPlayer bot) {
		// Nur im Nether und im End: In der Oberwelt kostete das Geländer zu viele Blöcke und Zeit (Wunsch von Hannes, 28.10.).
		if (bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			return false;
		}
		if (rangedThreatNear(bot)) {
			return true;
		}
		if (!bot.level().getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
				bot.getBoundingBox().inflate(200), net.minecraft.world.entity.LivingEntity::isAlive).isEmpty()) {
			return true;
		}
		return !bot.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
				bot.getBoundingBox().inflate(16), net.minecraft.world.entity.LivingEntity::isAlive).isEmpty();
	}

	/** Geländer nur, wenn ein Schütze ihn von der Brücke stoßen kann. Nether-Lauf 15: Ein goto verbaute 269 Blöcke,
	 * meist Geländer, danach hatte er keine Bausteine mehr. */
	private static boolean rangedThreatNear(ServerPlayer bot) {
		for (net.minecraft.world.entity.LivingEntity enemy : Combat.hostiles(bot, RAIL_THREAT_RANGE)) {
			if (enemy instanceof net.minecraft.world.entity.monster.RangedAttackMob
					|| enemy instanceof net.minecraft.world.entity.monster.Blaze
					|| enemy instanceof net.minecraft.world.entity.monster.Ghast) {
				return true;
			}
		}
		return ghastNear(bot);
	}

	/** Ghasts schießen bis 64 Blöcke weit. Perlen-Lauf 4: Ein Feuerball von weiter weg stieß ihn von der Brücke. */
	private static boolean ghastNear(ServerPlayer bot) {
		return !bot.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Ghast.class,
				bot.getBoundingBox().inflate(GHAST_RANGE), net.minecraft.world.entity.LivingEntity::isAlive).isEmpty();
	}

	private static final double RAIL_THREAT_RANGE = 40;
	private static final double GHAST_RANGE = 64;

	private void clearEdits() {
		railQueue.clear();
		digQueue.clear();
		breaker = null;
		bridgeAt = null;
		pillarPending = false;
		pillar = null;
	}

	/** Sicht der Wegfindung, solange der Weg von einem Magmablock aus geplant ist (Magma gilt als Boden); sonst null. */
	private CellSource magmaView;

	private boolean startSearch(ServerPlayer bot, WorldCells cells) {
		Pos start = standingPos(bot, cells);
		if (start == null && bot.isInWater()) {
			// Im tiefen Wasser gibt es keinen Startpunkt: erst zum nächsten Ufer schwimmen.
			Pos shore = nearestStandable(bot, cells);
			if (shore != null && ++swimTicks <= MAX_SWIM_TICKS) {
				double dx = shore.x() + 0.5 - bot.getX();
				double dz = shore.z() + 0.5 - bot.getZ();
				double length = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
				Combat.face(bot, shore.x() + 0.5, bot.getEyeY(), shore.z() + 0.5);
				Combat.move(bot, dx / length, dz / length, false);
				bot.setJumping(true);
				return false;
			}
		}
		CellSource searchCells = cells;
		magmaView = null;
		if (start == null) {
			// Auf einem Magmablock: Die Suche meidet ihn als Boden, aber der Bot steht schon darauf und muss
			// herunter. Im KI-Lauf scheiterten darauf flee und attack mit no_start, bis er verbrannte. Bis er
			// unten ist, gilt jeder Magmablock als Boden, sonst kommt er aus einem ganzen Magmafeld nicht heraus.
			Pos hot = hotFloorStart(bot, cells);
			if (hot != null) {
				ServerLevel level = bot.level();
				BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
				start = hot;
				searchCells = new CellSource() {
					@Override
					public Cell at(int x, int y, int z) {
						Cell cell = cells.at(x, y, z);
						return cell == Cell.DANGER && level.getBlockState(cursor.set(x, y, z)).is(Blocks.MAGMA_BLOCK)
								? Cell.FLOOR : cell;
					}

					@Override
					public boolean diggable(int x, int y, int z) {
						return cells.diggable(x, y, z);
					}
				};
				magmaView = searchCells;
			}
		}
		if (start == null) {
			// Mitten im Sprung oder Fall: kurz warten, bis der Bot wieder steht.
			if (!isSettled(bot) && ++ticksWithoutProgress <= STUCK_TICKS) {
				return false;
			}
			closest = new Pos(bot.getBlockX(), bot.getBlockY(), bot.getBlockZ());
			closestDistance = goal.distance(bot.getX(), bot.getY(), bot.getZ());
			finish(bot, State.FAILED, "no_path", "no_start");
			return false;
		}
		ticksWithoutProgress = 0;
		swimTicks = 0;
		bot.setJumping(false);
		boolean dragon = !bot.level().getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
				bot.getBoundingBox().inflate(200), net.minecraft.world.entity.LivingEntity::isAlive).isEmpty();
		// Kanten meiden auch, solange der Drache lebt: Seine Stöße werfen über jede Kante (End-Lauf 16).
		de.hannes.aiplayer.nav.Moves.setEdgeCaution(ghastNear(bot) || dragon);
		de.hannes.aiplayer.nav.Moves.setPlaceExtra(bot.level().dimension() == net.minecraft.world.level.Level.NETHER ? NETHER_BRIDGE_EXTRA : 0);
		// Nicht, wenn das Ziel selbst am Brunnen liegt (bed_bomb zum Kopf): sonst lief er im Bogen um die Mitte.
		de.hannes.aiplayer.nav.Moves.setFountainCaution(dragon && goal.distance(0.5, bot.getY(), 0.5) > 16);
		search = new PathSearch(searchCells, start, goal, options);
		directSearch = directNext;
		directNext = false;
		if (directSearch) {
			search.setWeight(DIRECT_WEIGHT);
		}
		// Im Nether mit Bauen: an der Linie vom ersten Start zum Ziel bleiben, statt von Plattform zu Plattform zu ziehen.
		if (options.allowPlace() && bot.level().dimension() == net.minecraft.world.level.Level.NETHER) {
			if (corridorFrom == null) {
				corridorFrom = start;
			}
			Pos to = goal.anchor();
			search.setCorridor(corridorFrom.x(), corridorFrom.z(), to.x(), to.z());
		}
		return true;
	}

	private void continueSearch(ServerPlayer bot) {
		long startNanos = System.nanoTime();
		PathSearch.State result = search.advance(SEARCH_NANOS_PER_TICK);
		peakSearchNanos = Math.max(peakSearchNanos, System.nanoTime() - startNanos);
		if (result == PathSearch.State.RUNNING) {
			return;
		}
		PathSearch done = search;
		search = null;
		searchedNodes += done.expanded();
		closest = done.bestPos();
		closestDistance = done.bestDistance();

		switch (result) {
			case FOUND -> usePath(bot, done, true);
			case ENCLOSED -> finish(bot, State.FAILED, "no_path", "enclosed");
			case STALLED -> {
				// Mit Graben oder Bauen gibt es fast immer einen Weg; die normale Suche findet ihn nur nicht, weil Umbauten
				// teuer sind. Ein zweiter Versuch geht geradewegs aufs Ziel zu.
				if (options.any() && !directSearch) {
					directNext = true;
				} else {
					finish(bot, State.FAILED, "no_path", "search_stalled");
				}
			}
			default -> {
				// Suchgrenze erreicht: Das Ziel ist weit. Das beste Teilstück laufen, wenn es spürbar näher führt.
				if (done.bestDistance() < lastSegmentDistance - MIN_SEGMENT_GAIN && !done.path().isEmpty()) {
					usePath(bot, done, false);
				} else if (options.any() && !directSearch) {
					directNext = true;
				} else {
					finish(bot, State.FAILED, "no_path", "search_limit");
				}
			}
		}
	}

	private void usePath(ServerPlayer bot, PathSearch done, boolean reachesGoal) {
		List<Pos> found = done.path();
		if (found.isEmpty()) {
			finish(bot, State.ARRIVED, null, null);
			return;
		}
		path = found;
		index = 0;
		pathReachesGoal = reachesGoal;
		lastSegmentDistance = done.bestDistance();
		bestWaypointDistance = Double.POSITIVE_INFINITY;
		ticksWithoutProgress = 0;
		editAttempts = 0;
	}

	private State replanOrFail(ServerPlayer bot, String why) {
		release(bot);
		if (++replansWithoutProgress > MAX_REPLANS_WITHOUT_PROGRESS) {
			closest = new Pos(bot.getBlockX(), bot.getBlockY(), bot.getBlockZ());
			closestDistance = goal.distance(bot.getX(), bot.getY(), bot.getZ());
			return finish(bot, State.FAILED, "stuck", why);
		}
		replan();
		return state;
	}

	private void replan() {
		replans++;
		search = null;
		path = List.of();
		index = 0;
		ticksWithoutProgress = 0;
		editAttempts = 0;
		bestWaypointDistance = Double.POSITIVE_INFINITY;
		clearEdits();
	}

	private State finish(ServerPlayer bot, State result, String reason, String detail) {
		release(bot);
		state = result;
		failure = reason;
		hint = detail;
		return state;
	}

	/** Am Ziel erst als angekommen gelten, wenn der Bot steht und nicht gerade fällt oder springt. */
	private static boolean isSettled(ServerPlayer bot) {
		return bot.onGround() || bot.isInWater() || bot.onClimbable();
	}

	/**
	 * Das Feld, von dem die Suche startet. Steht der Bot gerade nicht sauber auf einem Feld
	 * (auf einer Kante, einen halben Block versetzt), wird das nächste passende in der Nähe genommen.
	 */
	/** Das nächste Feld zum Stehen im Umkreis von {@link #SHORE_RADIUS} Blöcken, oder {@code null}. */
	private static Pos nearestStandable(ServerPlayer bot, WorldCells cells) {
		int x = bot.getBlockX();
		int y = Mth.floor(bot.getY() + 0.2);
		int z = bot.getBlockZ();
		Pos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -SHORE_RADIUS; dx <= SHORE_RADIUS; dx++) {
			for (int dz = -SHORE_RADIUS; dz <= SHORE_RADIUS; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					if (Moves.kind(cells, x + dx, y + dy, z + dz) == Moves.Kind.NONE) {
						continue;
					}
					double distance = dx * dx + dz * dz + dy * dy * 2;
					if (distance < bestDistance) {
						bestDistance = distance;
						best = new Pos(x + dx, y + dy, z + dz);
					}
				}
			}
		}
		return best;
	}

	/** Das Feld über dem Magmablock, auf dem der Bot steht, wenn Füße und Kopf frei sind; sonst {@code null}. */
	private static Pos hotFloorStart(ServerPlayer bot, WorldCells cells) {
		int x = bot.getBlockX();
		int y = Mth.floor(bot.getY() + 0.2);
		int z = bot.getBlockZ();
		if (!bot.level().getBlockState(new BlockPos(x, y - 1, z)).is(Blocks.MAGMA_BLOCK)) {
			return null;
		}
		Cell feet = cells.at(x, y, z);
		return feet.passable() && feet != Cell.WATER && cells.at(x, y + 1, z).passable() ? new Pos(x, y, z) : null;
	}

	public static Pos standingPos(ServerPlayer bot, WorldCells cells) {
		int x = bot.getBlockX();
		int y = Mth.floor(bot.getY() + 0.2);
		int z = bot.getBlockZ();
		int[][] offsets = {{0, 0, 0}, {0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
				{0, -2, 0}, {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}};
		for (int[] o : offsets) {
			if (Moves.kind(cells, x + o[0], y + o[1], z + o[2]) != Moves.Kind.NONE) {
				return new Pos(x + o[0], y + o[1], z + o[2]);
			}
		}
		return null;
	}

	private boolean stepsIntoHazard(ServerPlayer bot, WorldCells cells, double dirX, double dirZ) {
		int aheadX = Mth.floor(bot.getX() + dirX * 0.8);
		int aheadZ = Mth.floor(bot.getZ() + dirZ * 0.8);
		if (aheadX == bot.getBlockX() && aheadZ == bot.getBlockZ()) {
			return false;
		}
		// Felder, die der Weg selbst vorsieht, hat die Suche schon geprüft (auch geplante Abstiege).
		for (int i = Math.max(0, index - 1); i < Math.min(index + 2, path.size()); i++) {
			if (path.get(i).x() == aheadX && path.get(i).z() == aheadZ) {
				return false;
			}
		}
		int y = Mth.floor(bot.getY() + 0.2);
		for (int drop = 0; drop <= options.maxDrop(); drop++) {
			Cell cell = cells.at(aheadX, y - drop, aheadZ);
			if (cell == Cell.DANGER) {
				return true;
			}
			if (Moves.kind(cells, aheadX, y - drop, aheadZ) != Moves.Kind.NONE || !cell.passable()) {
				return false;
			}
		}
		return true;
	}
}
