package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.memory.FurnaceWatch;
import de.hannes.aiplayer.memory.Places;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.Menus;
import de.hannes.aiplayer.skill.PathFollower;
import de.hannes.aiplayer.skill.Station;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * {@code smelt_collect}: holt das Ergebnis aus einem Ofen, den der Bot befüllt hat (sonst aus dem
 * nächsten bekannten). Schema: {@code schemas/commands/smelt_collect.schema.json}.
 */
public final class SmeltCollectAction extends Action {
	private Station station;
	private Map<String, Integer> before;
	private ItemStack stillCooking = ItemStack.EMPTY;

	@Override
	public int timeoutTicks() {
		return 1800;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (station == null) {
			before = Inv.counts(bot);
			List<BlockPos> watched = FurnaceWatch.watched(bot.level(), bot.blockPosition());
			station = watched.isEmpty() ? new Station(Places.Kind.FURNACE) : new Station(Places.Kind.FURNACE, watched.get(0));
		}
		switch (station.tick(bot)) {
			case RUNNING -> {
				return null;
			}
			case FAILED -> {
				return Outcome.failed("no_furnace", progress(bot));
			}
			case READY -> {
			}
		}

		PathFollower.release(bot);
		Station.open(bot, station.pos());
		if (!(bot.containerMenu instanceof AbstractFurnaceMenu menu)) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "furnace did not open");
			return Outcome.failed("no_furnace", data);
		}
		boolean hadResult = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).hasItem();
		if (hadResult) {
			Menus.quickMove(bot, menu, AbstractFurnaceMenu.RESULT_SLOT);
		}
		boolean taken = hadResult && !menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).hasItem();
		stillCooking = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().copy();
		boolean lit = menu.isLit();
		Menus.close(bot);

		if (stillCooking.isEmpty()) {
			FurnaceWatch.unwatch(bot.level(), station.pos());
		}
		JsonObject data = progress(bot);
		if (!stillCooking.isEmpty() && !lit) {
			data.addProperty("hint", "furnace is out of fuel");
		}
		if (hadResult && !taken) {
			return Outcome.failed("inventory_full", data);
		}
		return hadResult ? Outcome.ok(data) : Outcome.failed("nothing_ready", data);
	}

	@Override
	public void release(ServerPlayer bot) {
		if (station != null) {
			station.release(bot);
		}
		PathFollower.release(bot);
		Menus.close(bot);
	}

	@Override
	public JsonObject progress(ServerPlayer bot) {
		JsonObject data = new JsonObject();
		if (before != null) {
			data.add("gained", Inv.gained(before, bot));
		}
		if (station != null && station.pos() != null) {
			data.add("furnace", GameJson.pos(station.pos()));
		}
		data.add("still_cooking", Inv.describe(stillCooking));
		return data;
	}
}
