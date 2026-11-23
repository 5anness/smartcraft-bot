package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.BlockPlacer;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code place}: setzt einen Block aus dem Inventar an eine Position und läuft vorher in
 * Reichweite. Schema: {@code schemas/commands/place.schema.json}.
 */
public final class PlaceAction extends Action {
	private static final double APPROACH_REACH = 3.5;

	private final String itemId;
	private final BlockPos pos;
	private PathFollower follower;
	private boolean checked;

	public PlaceAction(String itemId, BlockPos pos) {
		this.itemId = Inv.fullId(itemId);
		this.pos = pos;
	}

	@Override
	public int timeoutTicks() {
		return 1200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (!checked) {
			checked = true;
			if (Inv.findItem(bot, itemId) < 0) {
				return Outcome.failed("missing_item", progress(bot));
			}
			if (!bot.level().getBlockState(pos).canBeReplaced()) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "there is " + GameJson.blockId(bot.level().getBlockState(pos)));
				return Outcome.failed("occupied", data);
			}
			// Vor dem Hinlaufen prüfen: ein Block braucht einen Nachbarn, an den er gesetzt wird.
			if (!BlockPlacer.hasSupport(bot.level(), pos)) {
				return Outcome.failed("no_support", progress(bot));
			}
		}

		Goal goal = Goal.withinReach(new Pos(pos.getX(), pos.getY(), pos.getZ()), APPROACH_REACH);
		boolean inPosition = goal.distance(bot.getX(), bot.getY(), bot.getZ()) <= 0 && bot.onGround();
		if (!inPosition) {
			if (follower == null) {
				follower = new PathFollower(goal, Moves.Options.NONE);
			}
			switch (follower.tick(bot)) {
				case RUNNING -> {
					return null;
				}
				case ARRIVED -> {
				}
				case FAILED -> {
					JsonObject data = progress(bot);
					data.addProperty("hint", follower.hint());
					return Outcome.failed("out_of_reach", data);
				}
			}
		}

		PathFollower.release(bot);
		int slot = Inv.findItem(bot, itemId);
		String problem = slot < 0 ? "missing_item" : BlockPlacer.place(bot, slot, pos);
		return problem == null ? Outcome.ok(progress(bot)) : Outcome.failed(problem, progress(bot));
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("block", itemId);
		data.add("block_pos", GameJson.pos(pos));
		data.addProperty("now_there", GameJson.blockId(bot.level().getBlockState(pos)));
		data.addProperty("left_in_inventory", Inv.count(bot, itemId));
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
