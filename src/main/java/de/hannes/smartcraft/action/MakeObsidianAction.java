package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.protocol.Event.Status;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * {@code make_obsidian}: Obsidian bis {@code count} besorgen, ein Befehl für die ganze Arbeit. Erst liegendes Obsidian
 * abbauen, dann selbst Lavaquellen mit dem Wassereimer umwandeln, das Wasser zurückholen und das Obsidian abbauen.
 * Wunsch von Hannes nach seinem Lauf am 16.10.2026: Vor einem Lavasee mit 64 Quellen riet die KI neunmal Koordinaten
 * für {@code bucket} und gab auf; das Obsidian direkt vor ihr bemerkte sie nicht.
 * Schema: {@code schemas/commands/make_obsidian.schema.json}.
 */
public final class MakeObsidianAction extends Action {
	private static final String OBSIDIAN = "minecraft:obsidian";
	private static final int MAX_TICKS = 20 * 600;
	/** Wasser zurückholen: nur dicht um die Stelle, an der es gerade gegossen wurde. */
	private static final int REFILL_RADIUS = 6;
	/** Bei no_lava: so viele Chunks um den Bot nach der nächsten Lavaquelle suchen (64 Blöcke). */
	private static final int FAR_CHUNKS = 4;

	/** Nach dem Guss so lange warten, bis das Wasser über die Lavafläche gelaufen ist (7 Felder zu je 5 Ticks plus Puffer). */
	private static final int FLOW_TICKS = 60;

	private static final int PICKUP_RADIUS = 10;

	private enum Phase { NEXT, MINE, POUR, FLOW, REFILL, PICKUP, UNCOVER, APPROACH }

	private final int count;
	private final int radius;
	private final Set<BlockPos> skipObsidian = new HashSet<>();
	private final Set<BlockPos> skipLava = new HashSet<>();
	private final Set<BlockPos> skipCover = new HashSet<>();
	private Phase phase = Phase.NEXT;
	private Action step;
	private int start = -1;
	private int ticks;
	private int poured;
	/** Wie oft er sich schon zu einem Lavasee durchgegraben hat. */
	private int approaches;
	private static final int MAX_APPROACHES = 3;
	private final Set<BlockPos> approached = new HashSet<>();
	private int mined;
	private String lastProblem;
	/** Die Decke, deren Säule gerade freigegraben wird. */
	private BlockPos uncoverTarget;
	/** Wo das ausgegossene Wasser als Quelle steht; es bleibt liegen, bis das Obsidian abgebaut ist. */
	private BlockPos waterPos;
	private int flowTicks;

	public MakeObsidianAction(int count, int radius) {
		this.count = count;
		this.radius = radius;
	}

	/** Lava ist hier das Ziel. */
	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	@Override
	public int timeoutTicks() {
		return MAX_TICKS + 200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ticks++;
		if (waterPos != null) {
			// Liegt eigenes Wasser, soll es in die Löcher laufen; der Wasser-Reflex würde es sonst zubauen.
			de.hannes.smartcraft.reflex.WaterGuard.suppress(bot.level().getGameTime() + 40);
		}
		if (start < 0) {
			start = Inv.count(bot, OBSIDIAN);
			if (Inv.bestToolSlot(bot, Blocks.OBSIDIAN.defaultBlockState()) == Inv.NO_USABLE_TOOL) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "obsidian needs a diamond pickaxe");
				return Outcome.failed("wrong_tool", data);
			}
		}
		if (phase == Phase.FLOW) {
			if (++flowTicks < FLOW_TICKS) {
				return null;
			}
			phase = Phase.NEXT;
			// Neben frischem Obsidian kann jetzt auch übersprungenes liegen, das vorher an Lava grenzte.
			skipObsidian.clear();
		}
		// Fertig, aber das Wasser liegt noch: erst zurückholen (auch mitten in einem Schritt, der das letzte Obsidian brachte).
		if (phase != Phase.REFILL && (gained(bot) >= count || ticks > MAX_TICKS) && waterPos != null) {
			if (step != null) {
				step.release(bot);
				step = null;
			}
			if (startRefill(bot)) {
				return null;
			}
		}
		if (gained(bot) >= count && phase != Phase.REFILL) {
			return Outcome.ok(progress(bot));
		}
		if (ticks > MAX_TICKS) {
			return Outcome.failed("timeout", progress(bot));
		}
		if (step != null) {
			Outcome done = step.tick(bot);
			if (done == null) {
				return null;
			}
			step.release(bot);
			step = null;
			Phase was = phase;
			phase = Phase.NEXT;
			if (done.status() != Status.OK) {
				lastProblem = was.name().toLowerCase() + ": " + done.reason();
			}
			// Scheitert das Freigraben einer Säule, diese Decke nicht wieder versuchen (sonst endlos derselbe Block).
			if (was == Phase.UNCOVER && done.status() != Status.OK && uncoverTarget != null) {
				skipCover.add(uncoverTarget);
			}
			switch (was) {
				case MINE -> {
					if (done.status() == Status.OK) {
						mined++;
					}
				}
				case POUR -> {
					if (done.status() == Status.OK) {
						// Wasser nicht gleich zurückholen, sondern über die Lava laufen lassen: Es macht die ganze Fläche
						// zu Obsidian, und beim Abbauen fließt es ins Loch, statt dass die Lava darunter das Item verbrennt
						// (Wunsch von Hannes, 19.10.: vorher wurde je Guss nur eine Quelle zu Obsidian).
						poured++;
						phase = Phase.FLOW;
						flowTicks = 0;
					} else if (Inv.findItem(bot, "minecraft:water_bucket") < 0 && Inv.findItem(bot, "minecraft:bucket") >= 0) {
						// Misslungener Guss: Wasser sofort zurückholen, sonst ist der Eimer weg.
						step = new BucketAction(true, "water", null, REFILL_RADIUS, false);
						phase = Phase.REFILL;
						waterPos = null;
					}
				}
				case REFILL -> {
					waterPos = null;
					// Das Wasser trägt abgebautes Obsidian ein Stück weg: jetzt, wo es zurück ist, einsammeln.
					if (mined > 0) {
						step = new PickupAction(PICKUP_RADIUS);
						phase = Phase.PICKUP;
					}
				}
				default -> {
				}
			}
			return null;
		}

		ServerLevel level = bot.level();
		// 1. Obsidian, das schon da ist (nicht aus einem Portalrahmen). Liegt Lava darunter, nur mit Wasser daneben oder
		// darüber: Das läuft ins Loch, macht die Lava darunter fest und hält das Item über ihr.
		Optional<BlockPos> obsidian = nearest(bot, pos -> level.getBlockState(pos).is(Blocks.OBSIDIAN)
				&& !skipObsidian.contains(pos) && !nextToPortal(level, pos) && minable(level, pos));
		if (obsidian.isPresent()) {
			BlockPos pos = obsidian.get();
			skipObsidian.add(pos);
			step = new MineAction(pos, lavaBelow(level, pos));
			phase = Phase.MINE;
			return null;
		}
		// Nichts mehr abzubauen: das liegende Wasser zurückholen, bevor die nächste Quelle drankommt.
		if (startRefill(bot)) {
			return null;
		}
		// 2. Eine Lavaquelle mit freiem Platz daneben umwandeln.
		if (Inv.findItem(bot, "minecraft:water_bucket") < 0) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "needs a water bucket (bucket fill with fluid water at a lake or river)");
			return Outcome.failed("no_water_bucket", data);
		}
		Optional<BlockPos> lava = nearest(bot, pos -> level.getFluidState(pos).isSource()
				&& level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)
				&& !skipLava.contains(pos) && openSide(level, pos));
		if (lava.isPresent()) {
			BlockPos pos = lava.get();
			skipLava.add(pos);
			waterPos = BucketAction.besideLava(level, pos);
			step = new BucketAction(false, "water", pos, radius, false);
			phase = Phase.POUR;
			return null;
		}
		// 3. Zugedeckte Lava (Hannes' Lauf: Lavasee unter Stein): den Block über einer Quelle abbauen, dann ist Platz fürs
		// Wasser. Liegt darüber noch Fels (bis 4 Blöcke), erst die Säule von oben freigraben (Hannes' Lauf 28.10.: zugebauter
		// See, jeder Deckenblock mit Lava daneben; vorher nur Decken mit Luft darüber und nur Lava direkt darunter).
		Optional<BlockPos> cover = nearest(bot, pos -> {
			if (skipCover.contains(pos) || level.getBlockState(pos).isAir() || !level.getFluidState(pos).isEmpty()
					|| pos.getY() >= bot.getBlockY()) {
				return false;
			}
			BlockPos below = pos.below();
			return level.getFluidState(below).isSource() && level.getFluidState(below).is(net.minecraft.tags.FluidTags.LAVA)
					&& coverColumnTop(level, pos) != null;
		});
		if (cover.isPresent()) {
			BlockPos pos = cover.get();
			BlockPos top = coverColumnTop(level, pos);
			uncoverTarget = pos;
			if (top.equals(pos)) {
				skipCover.add(pos);
			}
			// Von oben nach unten: erst was über der Decke liegt, zuletzt die Decke selbst (Lava daneben erlaubt).
			step = new MineAction(top, true).uncoverLava();
			phase = Phase.UNCOVER;
			return null;
		}
		// 4. Keine erreichbare Quelle im Umkreis: zum nächsten Lavasee durchgraben und durchbauen (Wunsch von Hannes,
		// 28.10.: vor großen eingeschlossenen Lavaseen meldete er lava_unreachable und suchte woanders).
		if (approaches < MAX_APPROACHES) {
			BlockPos target = nearestLavaSource(level, bot.blockPosition(), FAR_CHUNKS);
			if (target != null && !approached.contains(target)) {
				approaches++;
				approached.add(target);
				// Stehen über dem Rand: ein Feld zum Bot hin, eine Höhe über der Lava.
				int dx = Integer.signum(bot.getBlockX() - target.getX());
				int dz = Integer.signum(bot.getBlockZ() - target.getZ());
				BlockPos stand = target.offset(dx, 1, dz);
				step = new GotoAction(bot, new de.hannes.smartcraft.nav.Pos(stand.getX(), stand.getY(), stand.getZ()), 2,
						new de.hannes.smartcraft.nav.Moves.Options(true, true));
				phase = Phase.APPROACH;
				return null;
			}
		}
		JsonObject data = progress(bot);
		boolean anyLava = nearest(bot, pos -> level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)).isPresent();
		BlockPos far = anyLava ? null : nearestLavaSource(level, bot.blockPosition(), FAR_CHUNKS);
		if (far != null) {
			data.add("nearest_lava", de.hannes.smartcraft.perception.GameJson.pos(far));
		}
		data.addProperty("hint", anyLava ? "lava nearby, but no source with a free cell above or beside it; dig next to it or search elsewhere"
				: far != null ? "no lava within " + radius + " blocks; nearest lava source at nearest_lava: goto there (allow_dig) and run make_obsidian again"
				: "no lava within " + FAR_CHUNKS * 16 + " blocks; move on and mine deeper (lava is common below y 0)");
		return Outcome.failed(anyLava ? "lava_unreachable" : "no_lava", data);
	}

	/**
	 * Oberster feste Block der Säule über einer Decke, der zuerst weg muss (die Decke selbst, wenn darüber Luft ist);
	 * {@code null}, wenn über der Decke mehr als 4 Blöcke Fels oder irgendwo Flüssigkeit liegt.
	 */
	private static BlockPos coverColumnTop(ServerLevel level, BlockPos cover) {
		BlockPos top = cover;
		for (int up = 1; up <= 5; up++) {
			BlockPos cell = cover.above(up);
			if (!level.getFluidState(cell).isEmpty()) {
				return null;
			}
			if (level.getBlockState(cell).isAir()) {
				return top;
			}
			top = cell;
		}
		return null;
	}

	/**
	 * Nächste Lavaquelle bis {@code chunks} Chunks, nur in geladenen Chunks und nur in Abschnitten, deren Palette Lava
	 * enthalten kann – billig genug für einen Tick. Gesamtlauf 2: Nach {@code no_lava} suchte der Bot 20 Minuten an der
	 * Oberfläche, obwohl Lava in der Tiefe lag.
	 */
	static BlockPos nearestLavaSource(ServerLevel level, BlockPos feet, int chunks) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		int cx = feet.getX() >> 4;
		int cz = feet.getZ() >> 4;
		for (int dx = -chunks; dx <= chunks; dx++) {
			for (int dz = -chunks; dz <= chunks; dz++) {
				net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
				if (chunk == null) {
					continue;
				}
				net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.getStates().maybeHas(state -> state.is(Blocks.LAVA))) {
						continue;
					}
					int baseY = level.getSectionYFromSectionIndex(i) << 4;
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								net.minecraft.world.level.block.state.BlockState state = section.getBlockState(x, y, z);
								if (state.is(Blocks.LAVA) && state.getFluidState().isSource()) {
									BlockPos pos = new BlockPos(((cx + dx) << 4) + x, baseY + y, ((cz + dz) << 4) + z);
									double dist = pos.distSqr(feet);
									if (dist < bestDist) {
										bestDist = dist;
										best = pos;
									}
								}
							}
						}
					}
				}
			}
		}
		return best;
	}

	private Optional<BlockPos> nearest(ServerPlayer bot, java.util.function.Predicate<BlockPos> test) {
		BlockPos feet = bot.blockPosition();
		return StreamSupport.stream(BlockPos.betweenClosed(feet.offset(-radius, -8, -radius), feet.offset(radius, 8, radius))
						.spliterator(), false)
				.filter(test).map(BlockPos::immutable)
				.min(Comparator.comparingDouble(pos -> pos.distSqr(feet)));
	}

	/** Ein freies Feld direkt über oder neben der Quelle: Nur dorthin gegossenes Wasser erreicht sie sicher. */
	private static boolean openSide(ServerLevel level, BlockPos lava) {
		for (Direction direction : new Direction[]{Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
			BlockPos cell = lava.relative(direction);
			if (level.getBlockState(cell).isAir() && BucketAction.besideLava(level, lava) != null) {
				return true;
			}
		}
		return false;
	}

	/** Holt das liegengelassene Wasser zurück; {@code true}, wenn der Schritt dafür gestartet wurde. */
	private boolean startRefill(ServerPlayer bot) {
		if (waterPos == null || Inv.findItem(bot, "minecraft:water_bucket") >= 0 || Inv.findItem(bot, "minecraft:bucket") < 0) {
			return false;
		}
		boolean source = bot.level().getFluidState(waterPos).isSource();
		step = new BucketAction(true, "water", source ? waterPos : null, REFILL_RADIUS, false);
		phase = Phase.REFILL;
		waterPos = source ? waterPos : null;
		return true;
	}

	private static boolean lavaBelow(ServerLevel level, BlockPos pos) {
		return level.getFluidState(pos.below()).is(net.minecraft.tags.FluidTags.LAVA);
	}

	/** Keine Lava seitlich oder darüber; Lava darunter nur, wenn Wasser direkt daneben oder darüber steht. */
	private static boolean minable(ServerLevel level, BlockPos pos) {
		boolean water = false;
		for (Direction direction : new Direction[]{Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
			net.minecraft.world.level.material.FluidState fluid = level.getFluidState(pos.relative(direction));
			if (fluid.is(net.minecraft.tags.FluidTags.LAVA)) {
				return false;
			}
			water |= fluid.is(net.minecraft.tags.FluidTags.WATER);
		}
		return !lavaBelow(level, pos) || water;
	}

	private static boolean nextToPortal(ServerLevel level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (level.getBlockState(pos.relative(direction)).is(Blocks.NETHER_PORTAL)) {
				return true;
			}
		}
		return false;
	}

	private int gained(ServerPlayer bot) {
		return Inv.count(bot, OBSIDIAN) - start;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (step != null) {
			step.release(bot);
		}
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("obsidian_gained", Math.max(0, start < 0 ? 0 : gained(bot)));
		data.addProperty("obsidian", Inv.count(bot, OBSIDIAN));
		data.addProperty("lava_converted", poured);
		data.addProperty("mined", mined);
		data.addProperty("water_buckets", Inv.count(bot, "minecraft:water_bucket"));
		if (lastProblem != null) {
			data.addProperty("last_problem", lastProblem);
		}
		return data;
	}
}
