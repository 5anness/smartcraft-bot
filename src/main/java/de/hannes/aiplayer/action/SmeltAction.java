package de.hannes.aiplayer.action;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.memory.FurnaceWatch;
import de.hannes.aiplayer.memory.Places;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.Menus;
import de.hannes.aiplayer.skill.PathFollower;
import de.hannes.aiplayer.skill.Recipes;
import de.hannes.aiplayer.skill.Station;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.entity.FuelValues;

import java.util.Map;
import java.util.Optional;

/**
 * {@code smelt}: geht zu einem Ofen in der Nähe, legt Rohstoff und Brennstoff ein und ist damit
 * fertig. Der Ofen arbeitet allein weiter; {@code smelting_done} meldet, wenn er durch ist, und
 * {@code smelt_collect} holt das Ergebnis. Schema: {@code schemas/commands/smelt.schema.json}.
 */
public final class SmeltAction extends Action {
	private static final int SLOT_INPUT = AbstractFurnaceMenu.INGREDIENT_SLOT;
	private static final int SLOT_FUEL = AbstractFurnaceMenu.FUEL_SLOT;
	private static final int SLOT_RESULT = AbstractFurnaceMenu.RESULT_SLOT;
	/** Fenster-Slots ab hier gehören zum Inventar des Bots. */
	private static final int FIRST_PLAYER_SLOT = AbstractFurnaceMenu.SLOT_COUNT;

	private final String itemId;
	private final int requested;
	private final String fuelArg;
	private Item item;
	private int cookTicks;
	private String resultId;
	private Station station;
	private Map<String, Integer> before;

	private int loaded;
	private String fuelId;
	private int fuelLoaded;
	private String stoppedBecause;

	public SmeltAction(String itemId, int requested, String fuel) {
		this.itemId = Inv.fullId(itemId);
		this.requested = requested;
		this.fuelArg = fuel == null ? null : Inv.fullId(fuel);
	}

	@Override
	public int timeoutTicks() {
		return 1800;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		ServerLevel level = bot.level();
		if (station == null) {
			item = Recipes.item(itemId);
			if (item == null) {
				return Outcome.failed("unknown_item", progress(bot));
			}
			Optional<RecipeHolder<SmeltingRecipe>> recipe = level.recipeAccess()
					.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(item)), level);
			if (recipe.isEmpty()) {
				return Outcome.failed("not_smeltable", progress(bot));
			}
			cookTicks = recipe.get().value().cookingTime();
			resultId = Inv.itemId(recipe.get().value().assemble(new SingleRecipeInput(new ItemStack(item))));
			if (Inv.count(bot, itemId) == 0) {
				return Outcome.failed("missing_item", progress(bot));
			}
			if (fuelArg != null) {
				Item fuel = Recipes.item(fuelArg);
				if (fuel == null || !level.fuelValues().isFuel(new ItemStack(fuel))) {
					JsonObject data = progress(bot);
					data.addProperty("hint", fuelArg + " does not burn");
					return Outcome.failed("no_fuel", data);
				}
			}
			if (chooseFuel(bot, 0, ItemStack.EMPTY) < 0) {
				JsonObject data = progress(bot);
				data.addProperty("hint", fuelArg != null ? "no " + fuelArg + " in inventory"
						: "nothing that burns in inventory (coal, charcoal, planks, logs)");
				return Outcome.failed("no_fuel", data);
			}
			before = Inv.counts(bot);
			station = new Station(Places.Kind.FURNACE);
		}

		switch (station.tick(bot)) {
			case RUNNING -> {
				return null;
			}
			case FAILED -> {
				JsonObject data = progress(bot);
				boolean hasFurnace = Inv.findItem(bot, Places.Kind.FURNACE.itemId()) >= 0;
				data.addProperty("has_furnace_item", hasFurnace);
				data.addProperty("can_craft_furnace", Recipes.canCraft(bot, Items.FURNACE));
				data.addProperty("hint", hasFurnace ? "place it with place_utility furnace"
						: "craft a furnace (8 cobblestone, at a crafting table), then place_utility furnace");
				return Outcome.failed("no_furnace", data);
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
		Outcome outcome = load(bot, menu);
		Menus.close(bot);
		return outcome;
	}

	private Outcome load(ServerPlayer bot, AbstractFurnaceMenu menu) {
		ServerLevel level = bot.level();
		FuelValues fuels = level.fuelValues();

		// Fertiges von früher mitnehmen: „automatisch beim nächsten Besuch“.
		if (menu.getSlot(SLOT_RESULT).hasItem()) {
			Menus.quickMove(bot, menu, SLOT_RESULT);
		}
		ItemStack inputThere = menu.getSlot(SLOT_INPUT).getItem();
		if (!inputThere.isEmpty() && inputThere.getItem() != item) {
			JsonObject data = progress(bot);
			data.addProperty("hint", "furnace holds " + Inv.itemId(inputThere) + ", collect it first or use another furnace");
			return Outcome.failed("furnace_busy", data);
		}

		int amount = Math.min(requested, Inv.count(bot, itemId));
		amount = Math.min(amount, inputThere.isEmpty() ? item.getDefaultMaxStackSize()
				: inputThere.getMaxStackSize() - inputThere.getCount());
		if (amount < requested) {
			stoppedBecause = Inv.count(bot, itemId) < requested ? "not_enough_items" : "furnace_full";
		}

		// Brennstoff: was im Ofen liegt, zählt mit. Nur so viel nachlegen, wie die Menge braucht.
		ItemStack fuelThere = menu.getSlot(SLOT_FUEL).getItem();
		int burnThere = fuelThere.isEmpty() ? 0 : fuelThere.getCount() * fuels.burnDuration(fuelThere);
		int alreadyQueued = inputThere.getCount();
		int burnNeeded = (alreadyQueued + amount) * cookTicks - burnThere;
		if (burnNeeded > 0) {
			int fuelSlot = chooseFuel(bot, burnNeeded, fuelThere);
			ItemStack fuel = fuelSlot < 0 ? ItemStack.EMPTY : bot.getInventory().getItem(fuelSlot);
			boolean fits = !fuel.isEmpty() && (fuelThere.isEmpty() || ItemStack.isSameItemSameComponents(fuelThere, fuel));
			int burnEach = fits ? fuels.burnDuration(fuel) : 0;
			int fuelWanted = burnEach == 0 ? 0 : (burnNeeded + burnEach - 1) / burnEach;
			int fuelAvailable = fits ? Inv.count(bot, Inv.itemId(fuel)) : 0;
			if (fits) {
				fuelId = Inv.itemId(fuel);
			}
			if (fuelAvailable < fuelWanted) {
				// Zu wenig Brennstoff: nur so viel Rohstoff einlegen, wie damit durchläuft.
				int burnTotal = burnThere + fuelAvailable * burnEach;
				amount = Math.max(0, Math.min(amount, burnTotal / cookTicks - alreadyQueued));
				fuelWanted = fuelAvailable;
				stoppedBecause = "not_enough_fuel";
			}
			if (amount <= 0) {
				JsonObject data = progress(bot);
				data.addProperty("hint", fits ? "not enough fuel for a single item"
						: "furnace holds a different fuel and it is not enough");
				return Outcome.failed("no_fuel", data);
			}
			fuelLoaded = moveFromInventory(bot, menu, fuelId, fuelWanted, SLOT_FUEL);
		}

		loaded = moveFromInventory(bot, menu, itemId, amount, SLOT_INPUT);
		if (loaded == 0) {
			return Outcome.failed("load_failed", progress(bot));
		}
		FurnaceWatch.watch(level, station.pos());
		return Outcome.ok(progress(bot));
	}

	/** Legt bis zu {@code count} Stück eines Items aus dem Inventar in einen Ofen-Slot. */
	private static int moveFromInventory(ServerPlayer bot, AbstractFurnaceMenu menu, String id, int count, int target) {
		int moved = 0;
		for (int index = FIRST_PLAYER_SLOT; index < menu.slots.size() && moved < count; index++) {
			if (Inv.itemId(menu.getSlot(index).getItem()).equals(id)) {
				moved += Menus.move(bot, menu, index, count - moved, slot -> slot == target);
			}
		}
		return moved;
	}

	/**
	 * Wählt den Brennstoff: den verlangten, sonst Kohle, Holzkohle, Bretter, Stämme, dann anderes
	 * Brennbares. Reicht der bevorzugte Vorrat nicht für die ganze Menge, nimmt er den nächsten,
	 * der reicht (eine Kohle neben 30 Brettern: Bretter); reicht keiner, den mit der längsten
	 * Brenndauer. Liegt schon Brennstoff im Ofen, kommt nur derselbe in Frage.
	 * Werkzeug und der Rohstoff selbst werden nie verheizt.
	 *
	 * @return Inventar-Slot oder -1
	 */
	private int chooseFuel(ServerPlayer bot, int burnNeeded, ItemStack fuelThere) {
		FuelValues fuels = bot.level().fuelValues();
		Inventory inventory = bot.getInventory();
		int best = -1;
		int bestRank = Integer.MAX_VALUE;
		boolean bestEnough = false;
		int bestBurn = 0;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || !fuels.isFuel(stack) || stack.getItem() == item) {
				continue;
			}
			if (!fuelThere.isEmpty() && !ItemStack.isSameItemSameComponents(fuelThere, stack)) {
				continue;
			}
			int rank;
			if (fuelArg != null) {
				if (!Inv.itemId(stack).equals(fuelArg)) {
					continue;
				}
				rank = 0;
			} else if (stack.isDamageableItem() || stack.getMaxStackSize() == 1) {
				continue;
			} else if (stack.is(Items.COAL)) {
				rank = 0;
			} else if (stack.is(Items.CHARCOAL)) {
				rank = 1;
			} else if (stack.is(ItemTags.PLANKS)) {
				rank = 2;
			} else if (stack.is(ItemTags.LOGS_THAT_BURN)) {
				rank = 3;
			} else {
				rank = 4;
			}
			int burn = Inv.count(bot, Inv.itemId(stack)) * fuels.burnDuration(stack);
			boolean enough = burn >= burnNeeded;
			boolean better;
			if (enough != bestEnough) {
				better = enough;
			} else if (enough) {
				better = rank < bestRank;
			} else {
				better = burn > bestBurn;
			}
			if (best < 0 || better) {
				best = slot;
				bestRank = rank;
				bestEnough = enough;
				bestBurn = burn;
			}
		}
		return best;
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
		data.addProperty("item", itemId);
		data.addProperty("requested", requested);
		data.addProperty("loaded", loaded);
		if (resultId != null) {
			data.addProperty("makes", resultId);
		}
		if (loaded > 0) {
			data.addProperty("ready_in_ticks", loaded * cookTicks);
		}
		if (fuelId != null && fuelLoaded > 0) {
			data.add("fuel", GameJson.stack(fuelId, fuelLoaded));
		}
		if (stoppedBecause != null) {
			data.addProperty("stopped_because", stoppedBecause);
		}
		if (station != null && station.pos() != null) {
			data.add("furnace", GameJson.pos(station.pos()));
		}
		if (before != null) {
			data.add("collected", Inv.gained(before, bot));
		}
		return data;
	}
}
