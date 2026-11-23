package de.hannes.smartcraft.skill;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.memory.Places;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bringt den Bot an einen Arbeitsblock (Werkbank, Ofen, Truhe): sucht in der Nähe, nimmt die aus
 * dem Ortsgedächtnis dazu und läuft zum nächsten. Danach lässt sich der Block öffnen.
 * Aufstellen ist ein eigener Schritt ({@link #placeNear}).
 */
public final class Station {
	public enum State {
		RUNNING, READY, FAILED
	}

	private enum Phase {
		SCAN, APPROACH
	}

	private static final int SEARCH_RADIUS = 16;
	/** So weit entfernte Orte aus dem Gedächtnis läuft der Bot noch an. */
	private static final int MEMORY_RADIUS = 64;
	/**
	 * Werkbank: Hat der Bot eine dabei oder kann er eine craften, läuft er nicht so weit zu einer alten,
	 * sondern meldet „keine da“ – eine neue aufzustellen ist schneller.
	 */
	private static final int SPARE_TABLE_RADIUS = 24;
	private static final double APPROACH_REACH = 3.5;
	/** So viele Blöcke werden nacheinander angelaufen, bevor der Bot aufgibt. */
	private static final int MAX_APPROACHES = 3;
	private static final long SCAN_BUDGET_NANOS = 1_000_000;

	private final Places.Kind kind;
	private final BlockPos fixed;
	private Phase phase = Phase.SCAN;
	private BlockScan scan;
	private List<BlockPos> candidates;
	private int approaches;
	private PathFollower follower;
	private BlockPos pos;
	private State state = State.RUNNING;

	public Station(Places.Kind kind) {
		this(kind, null);
	}

	/** @param fixed genau dieser Block statt des nächstgelegenen; {@code null} für freie Suche */
	public Station(Places.Kind kind, BlockPos fixed) {
		this.kind = kind;
		this.fixed = fixed;
	}

	/** Genau dieser Behälter, welcher Art auch immer (Truhe, Fass, Shulkerkiste, Trichter …). */
	public static Station anyContainer(BlockPos fixed) {
		return new Station(null, fixed);
	}

	private boolean matches(ServerLevel level, BlockPos at) {
		return kind == null ? level.getBlockEntity(at) instanceof net.minecraft.world.Container : level.getBlockState(at).is(kind.block());
	}

	private int memoryRadius(ServerPlayer bot) {
		if (kind != Places.Kind.CRAFTING_TABLE) {
			return MEMORY_RADIUS;
		}
		boolean spare = Inv.findItem(bot, kind.itemId()) >= 0 || Recipes.canCraft(bot, kind.block().asItem());
		return spare ? SPARE_TABLE_RADIUS : MEMORY_RADIUS;
	}

	/** Wo der Arbeitsblock steht, sobald {@link State#READY}. */
	public BlockPos pos() {
		return pos;
	}

	/** Für das Ergebnis: welcher Block und wo. */
	public JsonObject describe() {
		JsonObject json = new JsonObject();
		json.addProperty("block", kind == null ? "container" : kind.itemId());
		json.add("pos", GameJson.pos(pos));
		return json;
	}

	public State tick(ServerPlayer bot) {
		if (state != State.RUNNING) {
			return state;
		}
		ServerLevel level = bot.level();
		switch (phase) {
			case SCAN -> {
				if (fixed != null) {
					candidates = List.of(fixed);
					nextCandidate();
					return state;
				}
				if (scan == null) {
					scan = new BlockScan(bot.blockPosition(), SEARCH_RADIUS, blockState -> blockState.is(kind.block()));
				}
				if (scan.advance(level, SCAN_BUDGET_NANOS)) {
					candidates = new ArrayList<>(scan.found());
					// Was der Bot sieht, merkt er sich; was er sich gemerkt hat, kommt dazu.
					candidates.stream().limit(8).forEach(found -> Places.remember(kind, level, found));
					for (BlockPos known : Places.known(kind, level, bot.blockPosition(), memoryRadius(bot))) {
						if (!candidates.contains(known)) {
							candidates.add(known);
						}
					}
					candidates.sort(Comparator.comparingDouble(found -> found.distSqr(bot.blockPosition())));
					nextCandidate();
				}
			}
			case APPROACH -> {
				// Nur prüfen, was geladen ist: ein ferner Ort aus dem Gedächtnis zeigt sich erst beim Hinlaufen.
				if (level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && !matches(level, pos)) {
					if (kind != null) {
						Places.forget(level, pos);
					}
					nextCandidate();
					return state;
				}
				Goal goal = Goal.withinReach(new Pos(pos.getX(), pos.getY(), pos.getZ()), APPROACH_REACH);
				if (goal.distance(bot.getX(), bot.getY(), bot.getZ()) <= 0 && bot.onGround()) {
					PathFollower.release(bot);
					if (kind != null) {
						Places.remember(kind, level, pos);
					}
					state = State.READY;
					return state;
				}
				if (follower == null) {
					follower = new PathFollower(goal, Moves.Options.NONE);
				}
				switch (follower.tick(bot)) {
					case RUNNING -> {
					}
					case ARRIVED -> {
						// Angekommen heißt in Reichweite; der nächste Tick prüft den Block und meldet READY.
					}
					case FAILED -> nextCandidate();
				}
			}
		}
		return state;
	}

	public void release(ServerPlayer bot) {
		if (follower != null) {
			follower.abort(bot);
		}
	}

	private void nextCandidate() {
		follower = null;
		if (approaches < MAX_APPROACHES && approaches < candidates.size()) {
			pos = candidates.get(approaches++);
			phase = Phase.APPROACH;
		} else {
			pos = null;
			state = State.FAILED;
		}
	}

	/**
	 * Stellt einen Block aus dem Inventar auf ein freies Feld mit festem Boden neben dem Bot.
	 *
	 * @param near bevorzugte Stelle; {@code null} für „möglichst nah am Bot“
	 * @return wo er jetzt steht, oder {@code null}, wenn kein Platz war
	 */
	public static BlockPos placeNear(ServerPlayer bot, int slot, BlockPos near) {
		for (BlockPos spot : placeSpots(bot, near != null ? near : bot.blockPosition())) {
			if (BlockPlacer.place(bot, slot, spot) == null) {
				return spot;
			}
		}
		return null;
	}

	/** Freie Felder neben dem Bot mit festem Boden darunter, die nächsten an {@code near} zuerst. */
	private static List<BlockPos> placeSpots(ServerPlayer bot, BlockPos near) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		List<BlockPos> spots = new ArrayList<>();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					BlockPos spot = feet.offset(dx, dy, dz);
					BlockPos below = spot.below();
					if (level.getBlockState(spot).isAir() && level.getFluidState(spot).isEmpty()
							&& level.getBlockState(below).isCollisionShapeFullBlock(level, below)) {
						spots.add(spot);
					}
				}
			}
		}
		spots.sort(Comparator.comparingDouble(spot -> spot.distSqr(near)));
		return spots;
	}

	/**
	 * Klickt den Block an wie ein Spieler; danach ist {@code bot.containerMenu} sein Fenster.
	 * Ob das geklappt hat, prüft der Aufrufer am Typ des Fensters.
	 */
	public static void open(ServerPlayer bot, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, center);
		// Nicht geduckt: geduckt würde der Klick den Block in der Hand setzen, statt das Fenster zu öffnen.
		bot.setShiftKeyDown(false);
		bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(center, Direction.UP, pos, false));
		bot.swing(InteractionHand.MAIN_HAND, true);
	}
}
