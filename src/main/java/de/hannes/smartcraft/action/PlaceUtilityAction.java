package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.memory.Places;
import de.hannes.smartcraft.nav.Goal;
import de.hannes.smartcraft.nav.Moves;
import de.hannes.smartcraft.nav.Pos;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.skill.Inv;
import de.hannes.smartcraft.skill.PathFollower;
import de.hannes.smartcraft.skill.Recipes;
import de.hannes.smartcraft.skill.Station;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code place_utility}: stellt Werkbank, Ofen oder Truhe aus dem Inventar auf einen freien Platz
 * neben dem Bot und merkt sich die Stelle. Schema: {@code schemas/commands/place_utility.schema.json}.
 */
public final class PlaceUtilityAction extends Action {
	/** So nah geht der Bot an die gewünschte Stelle heran, bevor er einen Platz sucht. */
	private static final double NEAR_TOLERANCE = 2.0;

	private final Places.Kind kind;
	private final BlockPos near;
	private PathFollower follower;
	private boolean checked;
	private BlockPos placedAt;

	public PlaceUtilityAction(Places.Kind kind, BlockPos near) {
		this.kind = kind;
		this.near = near;
	}

	@Override
	public int timeoutTicks() {
		return 1200;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (!checked) {
			checked = true;
			if (Inv.findItem(bot, kind.itemId()) < 0) {
				JsonObject data = progress(bot);
				data.addProperty("can_craft", Recipes.canCraft(bot, kind.block().asItem()));
				return Outcome.failed("missing_item", data);
			}
		}
		if (near != null) {
			Goal goal = Goal.near(new Pos(near.getX(), near.getY(), near.getZ()), NEAR_TOLERANCE);
			if (goal.distance(bot.getX(), bot.getY(), bot.getZ()) > 0) {
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
		}
		// Erst stehen: im Laufen oder Fallen gesetzte Blöcke landen leicht daneben.
		PathFollower.release(bot);
		if (!bot.onGround()) {
			return null;
		}
		placedAt = Station.placeNear(bot, Inv.findItem(bot, kind.itemId()), near);
		if (placedAt == null) {
			return Outcome.failed("no_space", progress(bot));
		}
		Places.remember(kind, bot.level(), placedAt);
		return Outcome.ok(progress(bot));
	}

	@Override
	public void release(ServerPlayer bot) {
		PathFollower.release(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("block", kind.itemId());
		if (placedAt != null) {
			data.add("block_pos", GameJson.pos(placedAt));
		}
		data.addProperty("left_in_inventory", Inv.count(bot, kind.itemId()));
		return data;
	}
}
