package de.hannes.smartcraft.bot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Ausrüstungspakete für Tests: {@code /aiplayer kit nether} gibt dem Bot alles, um ins Nether zu kommen und dort zu
 * bestehen, Obsidian für das Portal eingeschlossen. Rüstung und Schild legt er gleich an.
 */
final class Kits {
	private static final Map<EquipmentSlot, String> NETHER_WORN = Map.of(
			EquipmentSlot.HEAD, "diamond_helmet",
			EquipmentSlot.CHEST, "diamond_chestplate",
			EquipmentSlot.LEGS, "diamond_leggings",
			// Ein Goldteil hält Piglins friedlich.
			EquipmentSlot.FEET, "golden_boots",
			EquipmentSlot.OFFHAND, "shield");

	private static final List<String> NETHER_ITEMS = List.of(
			"diamond_sword", "diamond_pickaxe", "iron_pickaxe", "diamond_axe", "diamond_shovel",
			"bow", "arrow 64", "cooked_beef 64", "bread 32", "obsidian 10", "flint_and_steel",
			"cobblestone 64", "cobblestone 64", "dirt 64", "water_bucket", "bucket", "crafting_table", "furnace",
			"torch 64", "stick 16", "oak_planks 32", "iron_ingot 16", "coal 32", "gold_ingot 16");

	/** Für {@code /aiplayer kit end}: alles, was {@code end_missing} verlangt, dazu Reserve (Wunsch von Hannes, 04.11.2026). */
	private static final List<String> END_ITEMS = List.of(
			"diamond_sword", "diamond_pickaxe", "diamond_pickaxe", "bow", "arrow 64", "arrow 64",
			"ender_eye 18", "white_bed 16", "cooked_beef 64", "golden_apple 8", "cobblestone 64", "cobblestone 64",
			"cobblestone 64", "flint_and_steel", "water_bucket");

	private Kits() {
	}

	/** @return wie viele Stapel er bekam; was nicht ins Inventar passt, liegt vor seinen Füßen */
	static int giveNether(ServerPlayer bot) {
		return giveAll(bot, NETHER_ITEMS);
	}

	static int giveEnd(ServerPlayer bot) {
		return giveAll(bot, END_ITEMS);
	}

	private static int giveAll(ServerPlayer bot, List<String> items) {
		int given = 0;
		for (Map.Entry<EquipmentSlot, String> worn : NETHER_WORN.entrySet()) {
			ItemStack old = bot.getItemBySlot(worn.getKey());
			if (!old.isEmpty()) {
				give(bot, old);
			}
			bot.setItemSlot(worn.getKey(), stack(worn.getValue(), 1));
			given++;
		}
		for (String entry : items) {
			String[] parts = entry.split(" ");
			give(bot, stack(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 1));
			given++;
		}
		return given;
	}

	private static ItemStack stack(String id, int count) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id)), count);
	}

	private static void give(ServerPlayer bot, ItemStack stack) {
		if (!bot.getInventory().add(stack) && !stack.isEmpty()) {
			bot.drop(stack, false);
		}
	}
}
