package de.hannes.aiplayer.reflex;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.protocol.Priority;
import de.hannes.aiplayer.skill.Inv;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * Wirft beim Graben aufgesammelten Abraum weg, sobald das Inventar voll ist: erst Gestein, das er nie braucht, dann
 * Bausteine über {@link #KEEP_BUILDING}. Erze, Werkzeug, Essen und alles andere bleiben. Kit-Lauf 5: Mit dem Nether-Kit
 * war das Inventar nach kurzem Graben voll, collect scheiterte mit inventory_full, die KI warf Steine von Hand weg.
 */
final class JunkDropper {
	private static final int INTERVAL = 20;
	/** Ab so wenigen freien Plätzen räumt er auf; einer bleibt für das nächste Erz. */
	private static final int MIN_FREE = 1;
	/** Bausteine aus dem Graben, von denen er so viele behält (Brücken, Unterschlupf, Säulen). */
	private static final int KEEP_BUILDING = 192;
	/** Gesperrt für den Bot, damit er den Abraum nicht gleich wieder aufhebt. */
	private static final int PICKUP_DELAY = 1200;

	private static final Set<String> SPOIL = Set.of(
			"minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff", "minecraft:calcite",
			// Kies und Feuerstein bleiben: Feuerstein braucht er für das Feuerzeug (Hannes' Lauf 17.10.: Feuerstein weggeworfen).
			"minecraft:dripstone_block", "minecraft:pointed_dripstone",
			"minecraft:sand", "minecraft:red_sand", "minecraft:clay_ball", "minecraft:smooth_basalt",
			"minecraft:basalt", "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:magma_block",
			"minecraft:leaf_litter", "minecraft:rotten_flesh", "minecraft:poisonous_potato",
			// Nether-Kram. Drachenlauf 40: Inventar voll mit Knochen, Quarz, Warzenblöcken, weinendem Obsidian und Klumpen,
			// 21 Endermen getötet, die Perlen blieben liegen.
			"minecraft:bone", "minecraft:quartz", "minecraft:nether_wart_block", "minecraft:warped_wart_block",
			"minecraft:crying_obsidian", "minecraft:gold_nugget", "minecraft:warped_roots", "minecraft:crimson_roots",
			"minecraft:nether_sprouts", "minecraft:weeping_vines", "minecraft:twisting_vines", "minecraft:crimson_nylium",
			"minecraft:warped_nylium", "minecraft:crimson_fungus", "minecraft:warped_fungus", "minecraft:shroomlight",
			"minecraft:crimson_stem", "minecraft:warped_stem", "minecraft:bone_meal");
	/** Werkzeuge und Waffen nach Rang; ein schwächeres fliegt, wenn ein stärkeres derselben Art im Inventar ist. */
	private static final List<String> TIERS = List.of("wooden", "stone", "golden", "iron", "diamond", "netherite");
	private static final List<String> TOOL_KINDS = List.of("pickaxe", "sword", "axe", "shovel", "hoe");
	private static final Set<String> BUILDING = Set.of(
			"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:netherrack",
			"minecraft:blackstone", "minecraft:stone", "minecraft:deepslate");

	/** Einmal ausgelöst, räumt er allen Abraum weg (ein Stapel je Takt), nicht nur bis wieder ein Platz frei ist. */
	private static boolean cleaning;

	private JunkDropper() {
	}

	static void tick(ServerPlayer bot, long tick, EventBus bus) {
		if (tick % INTERVAL != 0) {
			return;
		}
		// Auf dem Weg ins End (Oberwelt mit allem für den Drachen, oder schon im End): Unnötiges sofort weg, Platz für Betten.
		if (endBound(bot)) {
			int endSlot = smallest(bot, END_JUNK);
			if (endSlot < 0) {
				endSlot = worseFood(bot);
			}
			if (endSlot >= 0) {
				drop(bot, endSlot, tick, bus);
				return;
			}
		}
		if (!cleaning && freeSlots(bot) > MIN_FREE) {
			return;
		}
		int slot = smallest(bot, SPOIL);
		if (slot < 0) {
			slot = outclassed(bot);
		}
		if (slot < 0 && building(bot) - bot.getInventory().getItem(Math.max(0, smallest(bot, BUILDING))).getCount() >= KEEP_BUILDING) {
			slot = smallest(bot, BUILDING);
		}
		cleaning = slot >= 0;
		if (slot < 0) {
			return;
		}
		drop(bot, slot, tick, bus);
	}

	private static void drop(ServerPlayer bot, int slot, long tick, EventBus bus) {
		ItemStack stack = bot.getInventory().removeItemNoUpdate(slot);
		String id = Inv.itemId(stack);
		int count = stack.getCount();
		ItemEntity entity = bot.drop(stack, false, true);
		if (entity != null) {
			entity.setPickUpDelay(PICKUP_DELAY);
		}
		JsonObject data = new JsonObject();
		data.addProperty("item", id);
		data.addProperty("count", count);
		bus.emit("reflex_dropped_junk", tick, Priority.NORMAL, data);
	}

	/** Ersetztes: schwächeres Werkzeug derselben Art (oder ein Doppel), Rüstung schlechter als die getragene.
	 * Hannes' Lauf 17.10.: Mit vollem Inventar (Eisenrüstung unter Diamant, zwei Steinspitzhacken) warf die KI 43 Stämme weg. */
	private static int outclassed(ServerPlayer bot) {
		var inventory = bot.getInventory();
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || slot == inventory.getSelectedSlot()) {
				continue;
			}
			String path = Inv.itemId(stack).substring("minecraft:".length());
			int tier = tierOf(path);
			String kind = TOOL_KINDS.stream().filter(k -> path.endsWith("_" + k)).findFirst().orElse(null);
			// Die zweite Spitzhacke bleibt (Ersatz und Tunnel; Drachenlauf 11 warf die Eisenspitzhacke weg).
			if (tier >= 0 && kind != null && !(kind.equals("pickaxe") && pickaxes(bot) <= 2)) {
				for (int other = 0; other < Inv.MAIN_SLOTS; other++) {
					String otherPath = Inv.itemId(inventory.getItem(other)).substring("minecraft:".length());
					if (other != slot && otherPath.endsWith("_" + kind) && !(kind.equals("axe") && otherPath.endsWith("_pickaxe"))
							&& (tierOf(otherPath) > tier || (tierOf(otherPath) == tier && other < slot))
							// Ein fast verbrauchtes besseres Werkzeug ersetzt nichts: Drachenlauf 79 warf die Eisenspitzhacke weg,
							// die Diamantspitzhacke zerbrach bald darauf, er saß ohne Spitzhacke im Fels fest.
							&& !nearlyBroken(inventory.getItem(other))) {
						return slot;
					}
				}
			}
			var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
			if (equippable != null && !path.startsWith("golden_") && equippable.slot().isArmor()) {
				ItemStack worn = bot.getItemBySlot(equippable.slot());
				if (!worn.isEmpty() && armor(worn) >= armor(stack)) {
					return slot;
				}
			}
		}
		return -1;
	}


	/**
	 * Unnötiges auf dem Weg zum Drachen: leere Eimer, Äxte, Schaufeln, Hacken, Ofen, Werkbank, Kohle, Stöcke, Fackeln und
	 * ähnliches. Wunsch von Hannes: Platz für Betten.
	 */
	private static final Set<String> END_JUNK = Set.of(
			"minecraft:bucket", "minecraft:furnace", "minecraft:crafting_table", "minecraft:coal", "minecraft:charcoal",
			"minecraft:stick", "minecraft:torch", "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe",
			"minecraft:golden_axe", "minecraft:diamond_axe", "minecraft:wooden_shovel", "minecraft:stone_shovel",
			"minecraft:iron_shovel", "minecraft:golden_shovel", "minecraft:diamond_shovel", "minecraft:wooden_hoe",
			"minecraft:stone_hoe", "minecraft:iron_hoe", "minecraft:golden_hoe", "minecraft:diamond_hoe",
			"minecraft:raw_iron", "minecraft:raw_gold", "minecraft:raw_copper", "minecraft:copper_ingot",
			"minecraft:flint", "minecraft:gravel", "minecraft:string", "minecraft:feather", "minecraft:leather",
			"minecraft:wheat_seeds", "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:sugar_cane",
			"minecraft:chest", "minecraft:fishing_rod", "minecraft:oak_sapling", "minecraft:spruce_sapling",
			"minecraft:birch_sapling", "minecraft:gunpowder", "minecraft:spider_eye", "minecraft:egg");
	/** So viel Essen (Sättigungspunkte) bleibt mindestens, wenn eine schlechtere Sorte wegfliegt (16 gegarte zu je 8). */
	private static final int KEEP_FOOD_POINTS = 16 * 6;

	/** Oberwelt mit allem für den Drachen (wie end_ready) oder schon im End, solange der Drache lebt. */
	private static boolean endBound(ServerPlayer bot) {
		var dim = bot.level().dimension();
		if (dim == net.minecraft.world.level.Level.END) {
			return true;
		}
		return dim == net.minecraft.world.level.Level.OVERWORLD && de.hannes.aiplayer.skill.NetherKit.endMissing(bot).isEmpty();
	}

	/**
	 * Mehrere Essenssorten: die schwächste (Nährwert mal Anzahl) fliegt, wenn die übrigen genug sind. Goldäpfel bleiben.
	 */
	private static int worseFood(ServerPlayer bot) {
		int worst = -1;
		int worstPoints = Integer.MAX_VALUE;
		int total = 0;
		java.util.Map<String, Integer> perKind = new java.util.HashMap<>();
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = bot.getInventory().getItem(slot);
			var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
			if (food == null) {
				continue;
			}
			int points = food.nutrition() * stack.getCount();
			total += points;
			perKind.merge(Inv.itemId(stack), points, Integer::sum);
		}
		if (perKind.size() < 2) {
			return -1;
		}
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = bot.getInventory().getItem(slot);
			String id = Inv.itemId(stack);
			if (stack.get(net.minecraft.core.component.DataComponents.FOOD) == null || slot == bot.getInventory().getSelectedSlot()
					|| id.contains("golden_apple") || id.equals("minecraft:golden_carrot")) {
				continue;
			}
			int points = perKind.get(id);
			if (points < worstPoints) {
				worstPoints = points;
				worst = slot;
			}
		}
		if (worst < 0 || total - worstPoints < KEEP_FOOD_POINTS) {
			return -1;
		}
		return worst;
	}

	/** Weniger als ein Viertel der Haltbarkeit übrig. */
	private static boolean nearlyBroken(ItemStack stack) {
		return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() < stack.getMaxDamage() / 4;
	}

	private static int pickaxes(ServerPlayer bot) {
		int count = 0;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			if (Inv.itemId(bot.getInventory().getItem(slot)).endsWith("_pickaxe")) {
				count++;
			}
		}
		return count;
	}

	private static int tierOf(String path) {
		for (int i = 0; i < TIERS.size(); i++) {
			if (path.startsWith(TIERS.get(i) + "_")) {
				return i;
			}
		}
		return -1;
	}

	private static double armor(ItemStack stack) {
		double[] total = {0};
		stack.forEachModifier(net.minecraft.world.entity.EquipmentSlotGroup.ARMOR, (attribute, modifier, display) -> {
			if (attribute.is(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)) {
				total[0] += modifier.amount();
			}
		});
		return total[0];
	}

	private static int freeSlots(ServerPlayer bot) {
		int free = 0;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			if (bot.getInventory().getItem(slot).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	private static int building(ServerPlayer bot) {
		int total = 0;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = bot.getInventory().getItem(slot);
			if (!stack.isEmpty() && BUILDING.contains(Inv.itemId(stack))) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Kleinster Stapel aus {@code ids}, nie das Item in der Hand; -1 wenn keiner. */
	private static int smallest(ServerPlayer bot, Set<String> ids) {
		int best = -1;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = bot.getInventory().getItem(slot);
			if (stack.isEmpty() || slot == bot.getInventory().getSelectedSlot() || !ids.contains(Inv.itemId(stack))) {
				continue;
			}
			if (best < 0 || stack.getCount() < bot.getInventory().getItem(best).getCount()) {
				best = slot;
			}
		}
		return best;
	}
}
