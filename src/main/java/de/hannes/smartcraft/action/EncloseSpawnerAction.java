package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.BlockBreaker;
import de.hannes.smartcraft.skill.BlockPlacer;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code enclose_spawner}: baut einen geschlossenen Raum um den nächsten Spawner und den Bot. Ring in 2 Blöcken
 * Abstand vom Spawner auf Spawnerhöhe und eine Ebene darüber, Dach eine Ebene höher über der ganzen Fläche, Boden
 * unter offenen Feldern. Lohen erscheinen dann im Raum und können weder wegfliegen noch von außen schießen.
 * Schema: {@code schemas/commands/enclose_spawner.schema.json}.
 */
public final class EncloseSpawnerAction extends Action {
	private static final int SEARCH_RADIUS = 24;
	/** Abstand der Wand vom Spawner: innen bleibt ein 3 x 3-Feld, in dem Lohen erscheinen und der Bot steht. */
	private static final int WALL = 2;
	private static final double REACH = 3.5;
	private static final int MAX_PASSES = 4;

	private BlockPos spawner;
	private PathFollower follower;
	private List<BlockPos> plan;
	private int index;
	private int passes;
	private int placed;

	@Override
	public int timeoutTicks() {
		return 2400;
	}

	@Override
	public boolean cancelOnDanger() {
		return false;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (spawner == null) {
			spawner = findSpawner(bot);
			if (spawner == null) {
				return Outcome.failed("no_spawner", progress(bot));
			}
		}
		// Erst hinein: auf ein Feld neben dem Spawner, innerhalb der künftigen Wand.
		BlockPos feet = bot.blockPosition();
		boolean inside = Math.abs(feet.getX() - spawner.getX()) < WALL && Math.abs(feet.getZ() - spawner.getZ()) < WALL
				&& Math.abs(feet.getY() - spawner.getY()) <= 1;
		if (!inside && plan == null) {
			Goal goal = Goal.near(new Pos(spawner.getX(), spawner.getY(), spawner.getZ()), 1.5);
			if (follower == null) {
				follower = new PathFollower(goal, new Moves.Options(true, true));
			}
			if (follower.tick(bot) == PathFollower.State.FAILED) {
				JsonObject data = progress(bot);
				data.addProperty("hint", follower.hint());
				return Outcome.failed("out_of_reach", data);
			}
			return null;
		}
		PathFollower.release(bot);
		if (plan == null) {
			plan = plan();
		}
		// Ein Block je Tick; was noch keinen Nachbarn zum Ansetzen hat, kommt im nächsten Durchgang dran.
		while (index < plan.size()) {
			BlockPos pos = plan.get(index++);
			if (!level.getBlockState(pos).canBeReplaced() || pos.equals(bot.blockPosition()) || pos.equals(bot.blockPosition().above())) {
				continue;
			}
			if (!BlockBreaker.inReach(bot, pos) || !BlockPlacer.hasSupport(level, pos)) {
				continue;
			}
			int slot = Inv.findScaffold(bot);
			if (slot < 0) {
				return Outcome.failed("no_blocks", progress(bot));
			}
			if (BlockPlacer.place(bot, slot, pos) == null) {
				placed++;
			}
			return null;
		}
		int gaps = gaps(level);
		if (gaps > 0 && ++passes < MAX_PASSES) {
			index = 0;
			return null;
		}
		JsonObject data = progress(bot);
		return gaps == 0 ? Outcome.ok(data) : Outcome.failed("not_enclosed", data);
	}

	/** Boden unter dem Innenraum, Ring auf zwei Ebenen, Dach: von unten nach oben, damit alles Halt findet. */
	private List<BlockPos> plan() {
		List<BlockPos> blocks = new ArrayList<>();
		int y = spawner.getY();
		for (int dx = -WALL; dx <= WALL; dx++) {
			for (int dz = -WALL; dz <= WALL; dz++) {
				blocks.add(spawner.offset(dx, -1, dz));
			}
		}
		for (int dy = 0; dy <= 1; dy++) {
			for (int dx = -WALL; dx <= WALL; dx++) {
				for (int dz = -WALL; dz <= WALL; dz++) {
					if (Math.abs(dx) == WALL || Math.abs(dz) == WALL) {
						blocks.add(new BlockPos(spawner.getX() + dx, y + dy, spawner.getZ() + dz));
					}
				}
			}
		}
		for (int dx = -WALL; dx <= WALL; dx++) {
			for (int dz = -WALL; dz <= WALL; dz++) {
				blocks.add(spawner.offset(dx, 2, dz));
			}
		}
		return blocks;
	}

	private int gaps(ServerLevel level) {
		int gaps = 0;
		for (BlockPos pos : plan) {
			BlockState state = level.getBlockState(pos);
			if (state.canBeReplaced() || state.getCollisionShape(level, pos).isEmpty()) {
				gaps++;
			}
		}
		return gaps;
	}

	static BlockPos findSpawner(ServerPlayer bot) {
		BlockPos feet = bot.blockPosition();
		BlockPos best = null;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-SEARCH_RADIUS, -12, -SEARCH_RADIUS), feet.offset(SEARCH_RADIUS, 12, SEARCH_RADIUS))) {
			if (bot.level().getBlockState(pos).is(Blocks.SPAWNER) && (best == null || pos.distSqr(feet) < best.distSqr(feet))) {
				best = pos.immutable();
			}
		}
		return best;
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (spawner != null) {
			data.add("spawner", GameJson.pos(spawner));
		}
		data.addProperty("blocks_placed", placed);
		if (plan != null) {
			data.addProperty("gaps_left", gaps(bot.level()));
		}
		data.addProperty("blocks_left", Inv.countScaffold(bot));
		return data;
	}
}
