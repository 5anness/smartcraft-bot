package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.Recipes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * {@code light}: setzt eine Fackel, neben den Bot oder an eine Stelle. Fehlt die Fackel, craftet
 * er vorher welche, wenn Kohle und Stock da sind. Schema: {@code schemas/commands/light.schema.json}.
 */
public final class LightAction extends Action {
	private static final String TORCH = "minecraft:torch";

	private final BlockPos wanted;
	private CraftAction craft;
	private PlaceAction place;
	private int crafted;

	/** @param pos Stelle für die Fackel oder {@code null} für ein freies Feld neben dem Bot */
	public LightAction(BlockPos pos) {
		this.wanted = pos;
	}

	@Override
	public int timeoutTicks() {
		return 1400;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (place == null && craft == null && Inv.findItem(bot, TORCH) < 0) {
			if (!Recipes.canCraft(bot, Items.TORCH)) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "no torch; crafting 4 needs 1 coal or charcoal and 1 stick");
				return Outcome.failed("no_torch", data);
			}
			craft = new CraftAction(TORCH, 1);
		}
		if (craft != null) {
			Outcome outcome = craft.tick(bot);
			if (outcome == null) {
				return null;
			}
			craft.release(bot);
			craft = null;
			crafted = Inv.count(bot, TORCH);
			if (crafted == 0) {
				JsonObject data = progress(bot);
				if (outcome.data().has("missing")) {
					data.add("missing", outcome.data().get("missing"));
				}
				data.addProperty("hint", "crafting torches failed: " + outcome.reason());
				return Outcome.failed("no_torch", data);
			}
		}
		if (place == null) {
			BlockPos pos = wanted != null ? wanted : freeSpot(bot);
			if (pos == null) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "no free block with solid ground next to the bot");
				return Outcome.failed("no_space", data);
			}
			place = new PlaceAction(TORCH, pos);
		}
		Outcome outcome = place.tick(bot);
		if (outcome == null) {
			return null;
		}
		JsonObject data = progress(bot);
		data.add("block_pos", outcome.data().get("block_pos"));
		if (outcome.data().has("hint")) {
			data.add("hint", outcome.data().get("hint"));
		}
		return new Outcome(outcome.status(), outcome.reason(), data);
	}

	/** Das nächste freie Feld neben dem Bot, auf dessen Boden eine Fackel steht. */
	private static BlockPos freeSpot(ServerPlayer bot) {
		ServerLevel level = bot.level();
		BlockPos feet = bot.blockPosition();
		BlockPos best = null;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 0; dy >= -1; dy--) {
					BlockPos pos = feet.offset(dx, dy, dz);
					BlockPos below = pos.below();
					if (dx == 0 && dz == 0 || !level.getBlockState(pos).isAir()
							|| !level.getBlockState(below).isCollisionShapeFullBlock(level, below)) {
						continue;
					}
					if (best == null || pos.distSqr(feet) < best.distSqr(feet)) {
						best = pos;
					}
				}
			}
		}
		return best;
	}

	@Override
	public void release(ServerPlayer bot) {
		if (craft != null) {
			craft.release(bot);
		}
		if (place != null) {
			place.release(bot);
		}
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		data.addProperty("torches_left", Inv.count(bot, TORCH));
		if (crafted > 0) {
			data.addProperty("crafted", crafted);
		}
		return data;
	}
}
