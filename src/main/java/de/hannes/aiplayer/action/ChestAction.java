package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.memory.Places;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.Menus;
import de.hannes.aiplayer.skill.PathFollower;
import de.hannes.aiplayer.skill.Recipes;
import de.hannes.aiplayer.skill.Station;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code store} und {@code take}: legt Items in die nächste Truhe oder holt sie heraus.
 * Schemas: {@code schemas/commands/store.schema.json}, {@code take.schema.json}.
 */
public final class ChestAction extends Action {
	/** So viele verschiedene Items der Truhe nennt das Ergebnis höchstens. */
	private static final int MAX_LISTED = 30;

	private final boolean store;
	/** Item-ID mit Namensraum; {@code null} heißt alles (nur bei {@code store}). */
	private final String itemId;
	private final int requested;
	private Station station;
	private int moved;
	private String stoppedBecause;
	private JsonObject contents;
	private int freeSlots = -1;

	/** Behälter an genau dieser Stelle (jede Art); {@code null} heißt nächste Truhe. */
	private final BlockPos fixed;

	public ChestAction(boolean store, String itemId, int requested, BlockPos fixed) {
		this.fixed = fixed;
		this.store = store;
		this.itemId = itemId == null ? null : Inv.fullId(itemId);
		this.requested = requested;
	}

	@Override
	public int timeoutTicks() {
		return 1800;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		if (station == null) {
			if (store && itemId != null && Inv.count(bot, itemId) == 0) {
				return Outcome.failed("missing_item", progress(bot));
			}
			station = fixed != null ? Station.anyContainer(fixed) : new Station(Places.Kind.CHEST);
		}
		switch (station.tick(bot)) {
			case RUNNING -> {
				return null;
			}
			case FAILED -> {
				JsonObject data = progress(bot);
				if (fixed != null) {
					data.addProperty("hint", "no container at " + fixed.toShortString() + " or not reachable");
					return Outcome.failed("no_chest", data);
				}
				boolean hasChest = Inv.findItem(bot, Places.Kind.CHEST.itemId()) >= 0;
				data.addProperty("has_chest_item", hasChest);
				data.addProperty("can_craft_chest", Recipes.canCraft(bot, Items.CHEST));
				data.addProperty("hint", hasChest ? "place it with place_utility chest"
						: "craft a chest (8 planks, at a crafting table), then place_utility chest");
				return Outcome.failed("no_chest", data);
			}
			case READY -> {
			}
		}

		PathFollower.release(bot);
		Station.open(bot, station.pos());
		AbstractContainerMenu menu = bot.containerMenu;
		// Alles außer den 36 Plätzen des eigenen Inventars gehört dem Behälter.
		int chestSlots = menu.slots.size() - 36;
		if (menu == bot.inventoryMenu || chestSlots <= 0) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "container did not open (blocked from above?)");
			return Outcome.failed("no_chest", data);
		}
		int first = store ? chestSlots : 0;
		int end = store ? menu.slots.size() : chestSlots;
		boolean sawItem = false;
		for (int index = first; index < end && moved < requested; index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (stack.isEmpty() || itemId != null && !Inv.itemId(stack).equals(itemId)) {
				continue;
			}
			sawItem = true;
			moved += Menus.move(bot, menu, index, requested - moved,
					store ? target -> target < chestSlots : target -> target >= chestSlots);
		}
		boolean wantedAll = requested == Integer.MAX_VALUE;
		if (moved == 0 || !wantedAll && moved < requested || wantedAll) {
			// Liegt noch etwas Passendes da, war das Ziel voll; sonst gab es schlicht nicht mehr.
			boolean left = false;
			for (int index = first; index < end; index++) {
				ItemStack stack = menu.getSlot(index).getItem();
				left |= !stack.isEmpty() && (itemId == null || Inv.itemId(stack).equals(itemId));
			}
			if (left && (wantedAll || moved < requested)) {
				stoppedBecause = store ? "chest_full" : "inventory_full";
			} else if (!sawItem) {
				stoppedBecause = store ? "missing_item" : "not_in_chest";
			} else if (!wantedAll && moved < requested) {
				stoppedBecause = store ? "not_enough_items" : "not_enough_in_chest";
			}
		}
		describeChest(menu, chestSlots);
		Menus.close(bot);

		JsonObject data = progress(bot);
		return moved > 0 ? Outcome.ok(data) : Outcome.failed(stoppedBecause != null ? stoppedBecause : "nothing_moved", data);
	}

	private void describeChest(AbstractContainerMenu menu, int chestSlots) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		freeSlots = 0;
		for (int index = 0; index < chestSlots; index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (stack.isEmpty()) {
				freeSlots++;
			} else {
				counts.merge(Inv.itemId(stack), stack.getCount(), Integer::sum);
			}
		}
		contents = new JsonObject();
		counts.entrySet().stream().limit(MAX_LISTED).forEach(entry -> contents.addProperty(entry.getKey(), entry.getValue()));
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
		data.addProperty("item", itemId == null ? "all" : itemId);
		if (requested != Integer.MAX_VALUE) {
			data.addProperty("requested", requested);
		}
		data.addProperty(store ? "stored" : "taken", moved);
		if (stoppedBecause != null && moved > 0) {
			data.addProperty("stopped_because", stoppedBecause);
		}
		if (station != null && station.pos() != null) {
			data.add("chest", GameJson.pos(station.pos()));
		}
		if (contents != null) {
			data.add("chest_contents", contents);
			data.addProperty("chest_free_slots", freeSlots);
		}
		data.add("held_item", Inv.heldJson(bot));
		return data;
	}
}
