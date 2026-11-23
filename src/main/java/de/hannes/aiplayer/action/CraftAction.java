package de.hannes.aiplayer.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.aiplayer.memory.Places;
import de.hannes.aiplayer.skill.Inv;
import de.hannes.aiplayer.skill.Menus;
import de.hannes.aiplayer.skill.PathFollower;
import de.hannes.aiplayer.skill.Recipes;
import de.hannes.aiplayer.skill.Station;
import de.hannes.aiplayer.protocol.Event.Status;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * {@code craft}: stellt ein Item nach den Rezepten des Spiels her, im eigenen 2×2-Raster oder an
 * einer Werkbank in der Nähe. Fehlende Zwischenprodukte (Bretter, Stöcke) stellt er vorher selbst her. Schema: {@code schemas/commands/craft.schema.json}.
 */
public final class CraftAction extends Action {
	private enum Phase {
		PLAN, SUB, STATION, CRAFT
	}

	/** Wie tief Zwischenprodukte geschachtelt sein dürfen (Stamm → Bretter → Stöcke → Fackeln). */
	private static final int MAX_DEPTH = 3;
	/** Höchstens so viele Zwischenschritte je Befehl, damit nichts im Kreis läuft. */
	private static final int MAX_SUBS = 8;

	private final String itemId;
	private final int requested;
	private Phase phase = Phase.PLAN;
	private Item item;
	private Recipes.Crafting recipe;
	private Station station;
	private Map<String, Integer> before;
	private int rounds;
	private int roundsDone;
	private String stoppedBecause;
	private JsonArray missing;
	private final int depth;
	private final Set<Item> chain;
	private final Set<Item> failedSubs = new HashSet<>();
	private final JsonArray intermediates = new JsonArray();
	private CraftAction sub;
	private int subs;

	public CraftAction(String itemId, int requested) {
		this(itemId, requested, 0, Set.of());
	}

	private CraftAction(String itemId, int requested, int depth, Set<Item> chain) {
		this.itemId = Inv.fullId(itemId);
		this.requested = requested;
		this.depth = depth;
		this.chain = chain;
	}

	@Override
	public int timeoutTicks() {
		return 1800;
	}

	@Override
	public Outcome tick(ServerPlayer bot) {
		switch (phase) {
			case PLAN -> {
				return plan(bot);
			}
			case SUB -> {
				Outcome done = sub.tick(bot);
				if (done == null) {
					return null;
				}
				sub.release(bot);
				JsonObject step = new JsonObject();
				step.addProperty("item", sub.itemId);
				step.addProperty("crafted", sub.crafted(bot));
				step.addProperty("status", done.status().name().toLowerCase());
				if (done.status() != Status.OK) {
					step.addProperty("reason", done.reason());
					failedSubs.add(sub.item);
				}
				intermediates.add(step);
				sub = null;
				phase = Phase.PLAN;
				return null;
			}
			case STATION -> {
				switch (station.tick(bot)) {
					case RUNNING -> {
					}
					case READY -> phase = Phase.CRAFT;
					case FAILED -> {
						JsonObject data = progress(bot);
						Item table = Places.Kind.CRAFTING_TABLE.block().asItem();
						boolean hasTable = Inv.findItem(bot, Places.Kind.CRAFTING_TABLE.itemId()) >= 0;
						data.addProperty("has_crafting_table_item", hasTable);
						data.addProperty("can_craft_crafting_table", Recipes.canCraft(bot, table));
						data.addProperty("hint", hasTable ? "place it with place_utility crafting_table"
								: "craft a crafting_table (4 planks), then place_utility crafting_table");
						return Outcome.failed("no_crafting_table", data);
					}
				}
			}
			case CRAFT -> {
				return craftOnce(bot);
			}
		}
		return null;
	}

	private Outcome plan(ServerPlayer bot) {
		item = Recipes.item(itemId);
		if (item == null) {
			return Outcome.failed("unknown_item", progress(bot));
		}
		List<Recipes.Crafting> recipes = Recipes.ranked(bot, item);
		if (recipes.isEmpty()) {
			JsonObject data = progress(bot);
			if (!Recipes.smeltingFor(bot.level(), item).isEmpty()) {
				data.addProperty("hint", "this item comes from a furnace, use smelt");
			}
			return Outcome.failed("no_recipe", data);
		}
		recipe = recipes.get(0);
		if (before == null) {
			before = Inv.counts(bot);
		}
		int perRound = recipe.result().getCount();
		rounds = (requested + perRound - 1) / perRound;
		int possible = Recipes.craftable(bot, recipe.holder().value());
		List<Recipes.Need> needs = Recipes.needs(bot, recipe.holder().value());
		if (possible < rounds && depth < MAX_DEPTH && subs < MAX_SUBS) {
			CraftAction next = intermediate(bot, needs, rounds);
			if (next != null) {
				sub = next;
				subs++;
				phase = Phase.SUB;
				return null;
			}
		}
		if (possible < rounds) {
			missing = Recipes.missingJson(needs, rounds);
			if (possible <= 0) {
				JsonObject data = progress(bot);
				data.add("needs", Recipes.needsJson(needs, rounds));
				return Outcome.failed("missing_ingredients", data);
			}
			stoppedBecause = "missing_ingredients";
			rounds = possible;
		}
		if (recipe.needsTable()) {
			station = new Station(Places.Kind.CRAFTING_TABLE);
			phase = Phase.STATION;
		} else {
			phase = Phase.CRAFT;
		}
		return null;
	}

	/**
	 * Sucht ein fehlendes Zwischenprodukt, das sich craften lässt (Bretter für Stöcke). Zuerst eine Variante, die
	 * sofort geht, sonst die, bei der am wenigsten fehlt (dann eine Stufe tiefer). Umfärbe-Rezepte und Items aus der
	 * eigenen Kette bleiben außen vor.
	 */
	private CraftAction intermediate(ServerPlayer bot, List<Recipes.Need> needs, int times) {
		Set<Item> nextChain = new HashSet<>(chain);
		nextChain.add(item);
		for (Recipes.Need need : needs) {
			int short_ = need.count() * times - need.have();
			if (short_ <= 0) {
				continue;
			}
			Item fallback = null;
			int fallbackMissing = Integer.MAX_VALUE;
			for (Holder<Item> option : need.ingredient().items().toList()) {
				Item candidate = option.value();
				if (nextChain.contains(candidate) || failedSubs.contains(candidate)) {
					continue;
				}
				List<Recipes.Crafting> options = Recipes.ranked(bot, candidate);
				if (options.isEmpty()) {
					continue;
				}
				Recipes.Crafting best = options.get(0);
				if (Recipes.craftable(bot, best.holder().value()) > 0) {
					return new CraftAction(Recipes.id(candidate), short_, depth + 1, nextChain);
				}
				int missingNow = 0;
				int total = 0;
				for (Recipes.Need inner : Recipes.needs(bot, best.holder().value())) {
					missingNow += Math.max(0, inner.count() - inner.have());
					total += inner.count();
				}
				// Nur tiefer gehen, wenn wenigstens etwas da ist; sonst ist es kein Zwischenprodukt, sondern Sammeln.
				if (missingNow < total && missingNow < fallbackMissing) {
					fallback = candidate;
					fallbackMissing = missingNow;
				}
			}
			if (fallback != null && depth + 1 < MAX_DEPTH) {
				return new CraftAction(Recipes.id(fallback), short_, depth + 1, nextChain);
			}
		}
		return null;
	}

	private int crafted(ServerPlayer bot) {
		return before == null ? 0 : Math.max(0, Inv.count(bot, itemId) - before.getOrDefault(itemId, 0));
	}

	/** Ein Durchgang pro Tick: Rezept ins Raster legen, Ergebnis per Umschalt-Klick ins Inventar nehmen. */
	private Outcome craftOnce(ServerPlayer bot) {
		PathFollower.release(bot);
		AbstractCraftingMenu menu;
		if (recipe.needsTable()) {
			if (!(bot.containerMenu instanceof CraftingMenu)) {
				Station.open(bot, station.pos());
			}
			if (!(bot.containerMenu instanceof CraftingMenu open)) {
				JsonObject data = progress(bot);
				data.addProperty("hint", "crafting table did not open");
				return finish(bot, Outcome.failed("no_crafting_table", data));
			}
			menu = open;
		} else {
			menu = bot.inventoryMenu;
		}

		int had = Inv.count(bot, itemId);
		menu.handlePlacement(false, false, recipe.holder(), bot.level(), bot.getInventory());
		ItemStack result = menu.getResultSlot().getItem();
		if (result.isEmpty() || result.getItem() != item) {
			stoppedBecause = "missing_ingredients";
			return finish(bot, null);
		}
		Menus.quickMove(bot, menu, menu.getResultSlot().index);
		if (Inv.count(bot, itemId) <= had) {
			stoppedBecause = "inventory_full";
			return finish(bot, null);
		}
		if (++roundsDone >= rounds) {
			return finish(bot, null);
		}
		return null;
	}

	private Outcome finish(ServerPlayer bot, Outcome failure) {
		Menus.close(bot);
		if (failure != null) {
			return failure;
		}
		JsonObject data = progress(bot);
		if (roundsDone == 0) {
			return Outcome.failed(stoppedBecause != null ? stoppedBecause : "craft_failed", data);
		}
		return Outcome.ok(data);
	}

	@Override
	public void release(ServerPlayer bot) {
		if (sub != null) {
			sub.release(bot);
		}
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
		data.addProperty("crafted", crafted(bot));
		if (!intermediates.isEmpty()) {
			data.add("intermediates", intermediates);
		}
		data.addProperty("in_inventory", Inv.count(bot, itemId));
		if (stoppedBecause != null) {
			data.addProperty("stopped_because", stoppedBecause);
		}
		if (missing != null && !missing.isEmpty()) {
			data.add("missing", missing);
		}
		if (station != null && station.pos() != null) {
			data.add("crafting_table", station.describe());
		}
		return data;
	}
}
