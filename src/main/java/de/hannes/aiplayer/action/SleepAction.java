package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import de.hannes.aiplayer.nav.Goal;
import de.hannes.aiplayer.nav.Moves;
import de.hannes.aiplayer.nav.Pos;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.BlockScan;
import de.hannes.aiplayer.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code sleep}: geht zu einem Bett in der Nähe und schläft bis zum Morgen. Warum es nicht geht,
 * sagt das Spiel selbst (Monster in der Nähe, Bett verstellt). Schema: {@code schemas/commands/sleep.schema.json}.
 */
public final class SleepAction extends Action {
	private static final int SEARCH_RADIUS = 24;
	private static final long SCAN_BUDGET_NANOS = 1_000_000;
	private static final double BED_REACH = 2.5;
	private static final int MAX_APPROACHES = 3;
	private static final int DAY_LENGTH = 24000;
	/** Ab hier lässt das Spiel schlafen, bis kurz vor Sonnenaufgang. */
	private static final int SLEEP_START = 12542;
	private static final int SLEEP_END = 23460;
	/** Schlafen alle, springt die Zeit nach 100 Ticks; bleibt sie danach stehen, ist jemand wach. */
	private static final int MAX_TICKS_IN_BED = 160;

	private BlockScan scan;
	private List<BlockPos> beds;
	private int approaches;
	private BlockPos bed;
	private PathFollower follower;
	private boolean sleeping;
	private int ticksInBed;

	@Override
	public int timeoutTicks() {
		return 2400;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (sleeping) {
			return tickInBed(bot);
		}
		if (beds == null) {
			if (scan == null) {
				if (level.dimension() != Level.OVERWORLD) {
					JsonObject data = progress(bot);
					data.addProperty("hint", "beds only work in the overworld");
					return Outcome.failed("not_possible_here", data);
				}
				int time = time(level);
				if (!level.isThundering() && (time < SLEEP_START || time >= SLEEP_END)) {
					JsonObject data = progress(bot);
					data.addProperty("sleep_possible_in_ticks", Math.floorMod(SLEEP_START - time, DAY_LENGTH));
					return Outcome.failed("not_night", data);
				}
				scan = new BlockScan(bot.blockPosition(), SEARCH_RADIUS, state -> state.is(BlockTags.BEDS));
			}
			if (!scan.advance(level, SCAN_BUDGET_NANOS)) {
				return null;
			}
			beds = new ArrayList<>(scan.found());
			beds.sort(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())));
			if (beds.isEmpty()) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "no bed within " + SEARCH_RADIUS + " blocks; craft one (3 wool, 3 planks) or use shelter");
				return Outcome.failed("no_bed", data);
			}
			bed = beds.get(0);
		}

		Goal goal = Goal.withinReach(new Pos(bed.getX(), bed.getY(), bed.getZ()), BED_REACH);
		if (goal.distance(bot.getX(), bot.getY(), bot.getZ()) > 0 || !bot.onGround()) {
			if (follower == null) {
				follower = new PathFollower(goal, Moves.Options.NONE);
			}
			if (follower.tick(bot) == PathFollower.State.FAILED) {
				String hint = follower.hint();
				follower = null;
				// Ein Bett hat zwei Hälften; die Suche findet beide, also oft gleich die andere Seite.
				if (++approaches >= Math.min(MAX_APPROACHES, beds.size())) {
					JsonObject data = progress(bot);
					data.addProperty("hint", hint);
					return Outcome.failed("out_of_reach", data);
				}
				bed = beds.get(approaches);
			}
			return null;
		}

		PathFollower.release(bot);
		BlockState state = level.getBlockState(bed);
		if (!state.is(BlockTags.BEDS)) {
			return Outcome.failed("no_bed", progress(bot));
		}
		boolean occupied = state.getValue(BedBlock.OCCUPIED);
		Either<Player.BedSleepingProblem, Unit> result = bot.startSleepInBed(bed);
		if (result.left().isPresent()) {
			Player.BedSleepingProblem problem = result.left().get();
			String reason = problem == Player.BedSleepingProblem.NOT_SAFE ? "monsters_nearby"
					: problem == Player.BedSleepingProblem.OBSTRUCTED ? "bed_obstructed"
					: problem == Player.BedSleepingProblem.TOO_FAR_AWAY ? "out_of_reach"
					: occupied ? "bed_occupied" : "sleep_failed";
			JsonObject data = progress(bot);
			if (reason.equals("monsters_nearby")) {
				data.addProperty("hint", "a monster is within 8 blocks of the bed; defend first");
			}
			return Outcome.failed(reason, data);
		}
		sleeping = true;
		return null;
	}

	private Outcome tickInBed(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (!bot.isSleeping()) {
			int time = time(level);
			JsonObject data = progress(bot);
			if (time < SLEEP_START || time >= SLEEP_END) {
				data.addProperty("slept", true);
				data.addProperty("spawn_set", true);
				return Outcome.ok(data);
			}
			return Outcome.failed("woken_up", data);
		}
		if (++ticksInBed > MAX_TICKS_IN_BED) {
			bot.stopSleepInBed(true, true);
			JsonObject data = progress(bot);
			data.addProperty("hint", "another player is awake, so the night does not pass");
			return Outcome.failed("night_not_skipped", data);
		}
		return null;
	}

	private static int time(ServerLevel level) {
		return (int) Math.floorMod(level.getOverworldClockTime(), (long) DAY_LENGTH);
	}

	@Override
	public void release(ServerPlayer bot) {
		if (follower != null) {
			follower.abort(bot);
		}
		PathFollower.release(bot);
		if (bot.isSleeping()) {
			bot.stopSleepInBed(true, true);
		}
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (bed != null) {
			data.add("bed", GameJson.pos(bed));
		}
		data.addProperty("time", time(bot.level()));
		return data;
	}
}
