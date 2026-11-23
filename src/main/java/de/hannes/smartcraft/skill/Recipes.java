package de.hannes.smartcraft.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.mixin.IngredientAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest die Rezepte des Spiels: was ein Item ergibt, was dafür nötig ist und was davon im Inventar
 * fehlt. Nichts ist fest eingetragen; Datenpakete und Tags (jede Holzart als Bretter) wirken mit.
 */
public final class Recipes {
	/** Ein Rezept mit dem, was es ergibt. */
	public record Crafting(RecipeHolder<CraftingRecipe> holder, ItemStack result, boolean needsTable) {
	}

	/** Eine Zutat mit Bedarf für einen Durchgang und Bestand im Inventar. */
	public record Need(Ingredient ingredient, int count, int have) {
	}

	private static final int MAX_OPTIONS_LISTED = 4;

	private static RecipeManager indexedManager;
	private static final Map<Item, List<Crafting>> CRAFTING = new HashMap<>();
	private static final Map<Item, List<RecipeHolder<SmeltingRecipe>>> SMELTING = new HashMap<>();

	private Recipes() {
	}

	/** @return das Item zu einer ID wie {@code stick} oder {@code minecraft:stick}, sonst null */
	public static Item item(String id) {
		Identifier key = Identifier.tryParse(Inv.fullId(id));
		return key != null && BuiltInRegistries.ITEM.containsKey(key) ? BuiltInRegistries.ITEM.getValue(key) : null;
	}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/** Alle Crafting-Rezepte, die dieses Item ergeben. */
	public static List<Crafting> craftingFor(ServerLevel level, Item item) {
		index(level);
		return CRAFTING.getOrDefault(item, List.of());
	}

	/** Alle Ofen-Rezepte, die dieses Item ergeben. */
	public static List<RecipeHolder<SmeltingRecipe>> smeltingFor(ServerLevel level, Item item) {
		index(level);
		return SMELTING.getOrDefault(item, List.of());
	}

	/**
	 * Die Rezepte für ein Item, das beste zuerst: was jetzt herstellbar ist, dann was das Item nicht
	 * selbst verbraucht (Umfärben: Bett + Farbstoff), dann wo am wenigsten fehlt, dann was ohne Werkbank geht.
	 */
	public static List<Crafting> ranked(ServerPlayer bot, Item item) {
		List<Crafting> recipes = new ArrayList<>(craftingFor(bot.level(), item));
		recipes.sort(Comparator
				.comparingInt((Crafting recipe) -> craftable(bot, recipe.holder().value()) > 0 ? 0 : 1)
				.thenComparingInt(recipe -> consumesSameKind(recipe) ? 1 : 0)
				.thenComparingInt(recipe -> missingCount(needs(bot, recipe.holder().value())))
				.thenComparingInt(recipe -> recipe.needsTable() ? 1 : 0));
		return recipes;
	}

	/** Ob eine Zutat selbst vom Ergebnis-Typ ist (z. B. ein Bett, um ein Bett umzufärben). */
	private static boolean consumesSameKind(Crafting recipe) {
		for (Ingredient ingredient : recipe.holder().value().placementInfo().ingredients()) {
			if (ingredient.test(recipe.result())) {
				return true;
			}
		}
		return false;
	}

	/** Wie oft sich das Rezept mit dem Inventar herstellen lässt. */
	public static int craftable(ServerPlayer bot, Recipe<?> recipe) {
		StackedItemContents contents = new StackedItemContents();
		bot.getInventory().fillStackedContents(contents);
		return contents.getBiggestCraftableStack(recipe, null);
	}

	/** Ob der Bot dieses Item jetzt craften könnte (ohne Rücksicht auf eine Werkbank). */
	public static boolean canCraft(ServerPlayer bot, Item item) {
		for (Crafting recipe : craftingFor(bot.level(), item)) {
			if (craftable(bot, recipe.holder().value()) > 0) {
				return true;
			}
		}
		return false;
	}

	/** Die Zutaten eines Rezepts, gleiche zusammengefasst, mit Bestand. */
	public static List<Need> needs(ServerPlayer bot, Recipe<?> recipe) {
		Map<Ingredient, Integer> counts = new LinkedHashMap<>();
		for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
			counts.merge(ingredient, 1, Integer::sum);
		}
		List<Need> needs = new ArrayList<>();
		counts.forEach((ingredient, count) -> needs.add(new Need(ingredient, count, have(bot, ingredient))));
		return needs;
	}

	private static int have(ServerPlayer bot, Ingredient ingredient) {
		Inventory inventory = bot.getInventory();
		int have = 0;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && ingredient.test(stack)) {
				have += stack.getCount();
			}
		}
		return have;
	}

	private static int missingCount(List<Need> needs) {
		int missing = 0;
		for (Need need : needs) {
			missing += Math.max(0, need.count() - need.have());
		}
		return missing;
	}

	/** Name einer Zutat: das Tag ({@code #minecraft:planks}), wenn sie eins ist, sonst das Item. */
	public static String name(Ingredient ingredient) {
		return ((IngredientAccessor) (Object) ingredient).smartcraft$values().unwrapKey()
				.map(tag -> "#" + tag.location())
				.orElseGet(() -> ingredient.items().findFirst().map(holder -> id(holder.value())).orElse("?"));
	}

	/** Zutatenliste für ein Ergebnis: {@code item}, {@code count}, {@code have}, bei mehreren Möglichkeiten {@code any_of}. */
	public static JsonArray needsJson(List<Need> needs, int times) {
		JsonArray array = new JsonArray();
		for (Need need : needs) {
			JsonObject json = new JsonObject();
			json.addProperty("item", name(need.ingredient()));
			json.addProperty("count", need.count() * times);
			json.addProperty("have", need.have());
			addOptions(json, need.ingredient());
			array.add(json);
		}
		return array;
	}

	/** Was für {@code times} Durchgänge fehlt: {@code item} und {@code count}. Leer, wenn alles da ist. */
	public static JsonArray missingJson(List<Need> needs, int times) {
		JsonArray array = new JsonArray();
		for (Need need : needs) {
			int missing = need.count() * times - need.have();
			if (missing > 0) {
				JsonObject json = new JsonObject();
				json.addProperty("item", name(need.ingredient()));
				json.addProperty("count", missing);
				addOptions(json, need.ingredient());
				array.add(json);
			}
		}
		return array;
	}

	/** Bei Zutaten mit mehreren Möglichkeiten (Tags): ein paar Beispiele und die Gesamtzahl. */
	private static void addOptions(JsonObject json, Ingredient ingredient) {
		List<Holder<Item>> options = ingredient.items().toList();
		// Auch ein Tag mit nur einem Item auflösen: #iron_tool_materials sagt sonst niemandem etwas.
		if (options.size() > 1 || name(ingredient).startsWith("#")) {
			JsonArray anyOf = new JsonArray();
			options.stream().limit(MAX_OPTIONS_LISTED).forEach(holder -> anyOf.add(id(holder.value())));
			json.add("any_of", anyOf);
			json.addProperty("options", options.size());
		}
	}

	private static void index(ServerLevel level) {
		RecipeManager manager = level.recipeAccess();
		if (manager == indexedManager) {
			return;
		}
		// Nach /reload gibt es einen neuen Manager mit womöglich anderen Rezepten.
		indexedManager = manager;
		CRAFTING.clear();
		SMELTING.clear();
		ContextMap context = SlotDisplayContext.fromLevel(level);
		for (RecipeHolder<?> holder : manager.getRecipes()) {
			Recipe<?> recipe = holder.value();
			if (recipe.isSpecial() || recipe.placementInfo().isImpossibleToPlace()) {
				continue;
			}
			ItemStack result = result(recipe, context);
			if (result.isEmpty()) {
				continue;
			}
			if (recipe instanceof CraftingRecipe crafting) {
				@SuppressWarnings("unchecked")
				RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) holder;
				boolean needsTable = crafting instanceof ShapedRecipe shaped
						? shaped.getWidth() > 2 || shaped.getHeight() > 2
						: crafting.placementInfo().ingredients().size() > 4;
				CRAFTING.computeIfAbsent(result.getItem(), item -> new ArrayList<>()).add(new Crafting(typed, result, needsTable));
			} else if (recipe instanceof SmeltingRecipe) {
				@SuppressWarnings("unchecked")
				RecipeHolder<SmeltingRecipe> typed = (RecipeHolder<SmeltingRecipe>) holder;
				SMELTING.computeIfAbsent(result.getItem(), item -> new ArrayList<>()).add(typed);
			}
		}
	}

	private static ItemStack result(Recipe<?> recipe, ContextMap context) {
		List<RecipeDisplay> displays = recipe.display();
		return displays.isEmpty() ? ItemStack.EMPTY : displays.get(0).result().resolveForFirstStack(context);
	}
}
