package de.hannes.aiplayer.skill;

import com.google.gson.JsonArray;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Rüstung für den Nether: ein Goldteil (sonst greifen Piglins an) und Diamant oder Netherit in den übrigen drei
 * Plätzen. Wunsch von Hannes nach seinem Lauf am 16.10.2026: Der Bot ging mit 2 Äpfeln und ungetragener Eisenrüstung
 * in den Nether, ein Piglin traf ihn mit 8, er starb brennend.
 */
public final class NetherKit {
	/** In dieser Reihenfolge kommt das Goldteil hin: Stiefel und Helm kosten am wenigsten Schutz. */
	private static final EquipmentSlot[] GOLD_ORDER = {EquipmentSlot.FEET, EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.CHEST};

	private NetherKit() {
	}

	public static boolean isGold(ServerPlayer bot, ItemStack stack) {
		return !stack.isEmpty() && Inv.itemId(stack).startsWith("minecraft:golden_") && isArmor(bot, stack);
	}

	public static boolean isDiamondOrBetter(ServerPlayer bot, ItemStack stack) {
		String id = Inv.itemId(stack);
		return !stack.isEmpty() && (id.startsWith("minecraft:diamond_") || id.startsWith("minecraft:netherite_"))
				&& isArmor(bot, stack);
	}

	private static boolean isArmor(ServerPlayer bot, ItemStack stack) {
		EquipmentSlot slot = bot.getEquipmentSlotForItem(stack);
		return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
	}

	/** Getragene Goldteile. */
	public static int goldWorn(ServerPlayer bot) {
		int count = 0;
		for (EquipmentSlot slot : GOLD_ORDER) {
			if (isGold(bot, bot.getItemBySlot(slot))) {
				count++;
			}
		}
		return count;
	}

	/** Der Platz fürs Goldteil (getragen oder im Inventar), {@code null}, wenn es keins gibt. */
	private static EquipmentSlot goldSlot(ServerPlayer bot) {
		for (EquipmentSlot slot : GOLD_ORDER) {
			if (isGold(bot, bot.getItemBySlot(slot)) || find(bot, slot, true) >= 0) {
				return slot;
			}
		}
		return null;
	}

	/** Was für den Nether fehlt, als Liste lesbarer Einträge; leer, wenn alles da ist. */
	public static JsonArray missing(ServerPlayer bot) {
		JsonArray missing = new JsonArray();
		EquipmentSlot gold = goldSlot(bot);
		if (gold == null) {
			missing.add("golden armor piece (golden_boots: 4 gold ingots)");
			// Ohne Goldteil sind die Stiefel für Goldstiefel reserviert; Diamantstiefel braucht es dann nicht.
			gold = EquipmentSlot.FEET;
		}
		for (EquipmentSlot slot : GOLD_ORDER) {
			if (slot == gold) {
				continue;
			}
			if (!isDiamondOrBetter(bot, bot.getItemBySlot(slot)) && find(bot, slot, false) < 0) {
				missing.add("diamond " + slot.getName());
			}
		}
		return missing;
	}

	/** Mindestmengen für den Nether, wie im Systemprompt. */
	public static final int MIN_FOOD = 16;
	public static final int MIN_BLOCKS = 192; // 3 Stacks: Brücken und Türme im Nether (Wunsch von Hannes)
	public static final int MIN_ARROWS = 32;

	/**
	 * Rüstung ({@link #missing}) plus Vorräte: Diamantschwert, zwei Spitzhacken, Bogen mit Pfeilen, Schild, Essen,
	 * Bausteine, Feuerzeug. Wunsch von Hannes (28.10.2026): erst alles für den Nether farmen, dann das Portal bauen.
	 */
	public static JsonArray missingAll(ServerPlayer bot) {
		JsonArray missing = missing(bot);
		if (Inv.findItem(bot, "minecraft:diamond_sword") < 0 && Inv.findItem(bot, "minecraft:netherite_sword") < 0) {
			missing.add("diamond_sword");
		}
		int pickaxes = 0;
		for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
			if (Inv.itemId(bot.getInventory().getItem(i)).endsWith("_pickaxe")) {
				pickaxes++;
			}
		}
		if (pickaxes < 2) {
			missing.add((2 - pickaxes) + " more pickaxe (2 needed, one stone or iron for tunnels)");
		}
		if (Inv.findItem(bot, "minecraft:bow") < 0) {
			missing.add("bow (3 sticks, 3 string)");
		}
		int arrows = Inv.count(bot, "minecraft:arrow");
		if (arrows < MIN_ARROWS) {
			missing.add((MIN_ARROWS - arrows) + " more arrow (has " + arrows + ")");
		}
		if (Inv.findItem(bot, "minecraft:shield") < 0
				&& !bot.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) {
			missing.add("shield");
		}
		int food = 0;
		for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			net.minecraft.world.food.FoodProperties props = stack.get(net.minecraft.core.component.DataComponents.FOOD);
			if (Eater.isFood(stack) && !Eater.isHarmful(stack) && props != null && props.nutrition() >= 5) {
				food += stack.getCount();
			}
		}
		if (food < MIN_FOOD) {
			missing.add((MIN_FOOD - food) + " more cooked food (has " + food + ", raw meat does not count)");
		}
		int blocks = Inv.countScaffold(bot);
		if (blocks < MIN_BLOCKS) {
			missing.add((MIN_BLOCKS - blocks) + " more building blocks (cobblestone; has " + blocks + ")");
		}
		if (Inv.findItem(bot, "minecraft:flint_and_steel") < 0) {
			missing.add("flint_and_steel");
		}
		return missing;
	}

	/** Mindestmengen für das End (Drachenkampf mit bed_cycle, Weg über die Festung). */
	public static final int END_BEDS = 12;

	/** Ob für das End noch Betten fehlen (Betten im Inventar plus craftbare aus Wolle, Fäden und Brettern). */
	public static boolean bedsMissing(net.minecraft.server.level.ServerPlayer bot) {
		int beds = 0;
		int wool = 0;
		int planks = 0;
		for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
			net.minecraft.world.item.ItemStack stack = bot.getInventory().getItem(slot);
			if (stack.is(net.minecraft.tags.ItemTags.BEDS)) {
				beds += stack.getCount();
			} else if (stack.is(net.minecraft.tags.ItemTags.WOOL)) {
				wool += stack.getCount();
			} else if (stack.is(net.minecraft.tags.ItemTags.PLANKS)) {
				planks += stack.getCount();
			} else if (stack.is(net.minecraft.tags.ItemTags.LOGS)) {
				planks += stack.getCount() * 4;
			}
		}
		wool += Inv.count(bot, "minecraft:string") / 4;
		return beds + Math.min(wool / 3, planks / 3) < END_BEDS;
	}
	public static final int END_EYES = 18; // Wunsch von Hannes (04.11.2026): 12 für den Rahmen plus Würfe, die zerbrechen
	public static final int END_ARROWS = 64;
	public static final int END_BLOCKS = 128;

	/**
	 * Was für das End noch fehlt: Betten für {@code bed_cycle}, Enderaugen für das Endportal, Bogen und Pfeile für die
	 * Kristalle, Bausteine, Essen. Zählt Vorstufen mit (Wolle und Bretter für Betten, Perlen und Lohenstaub/-ruten für Augen).
	 * Wunsch von Hannes (01.11.2026): Der Bot soll wissen, was er zum Töten des Drachen braucht.
	 */
	public static JsonArray endMissing(ServerPlayer bot) {
		JsonArray missing = new JsonArray();
		int beds = 0;
		int wool = 0;
		int planks = 0;
		int food = 0;
		for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (stack.is(net.minecraft.tags.ItemTags.BEDS)) {
				beds += stack.getCount();
			} else if (stack.is(net.minecraft.tags.ItemTags.WOOL)) {
				wool += stack.getCount();
			} else if (stack.is(net.minecraft.tags.ItemTags.PLANKS)) {
				planks += stack.getCount();
			} else if (stack.is(net.minecraft.tags.ItemTags.LOGS)) {
				planks += stack.getCount() * 4;
			}
			net.minecraft.world.food.FoodProperties props = stack.get(net.minecraft.core.component.DataComponents.FOOD);
			if (Eater.isFood(stack) && !Eater.isHarmful(stack) && props != null && props.nutrition() >= 5) {
				food += stack.getCount();
			}
		}
		wool += Inv.count(bot, "minecraft:string") / 4; // 4 Fäden = 1 Wolle (Tipp von Hannes, 01.11.2026)
		int bedsPossible = beds + Math.min(wool / 3, planks / 3);
		if (bedsPossible < END_BEDS) {
			missing.add((END_BEDS - bedsPossible) + " more bed (has " + beds + "; fastest: villages have beds, mine them with collect #beds (look/fluid_spotted report village_in_sight); bed = 3 wool + 3 planks, wool: shear sheep; sheared sheep on grass regrow wool in 1 to 2 minutes, shear the same flock again; 4 string = 1 white_wool, string from cobwebs mined with a sword and from spiders)");
		}
		int eyes = Inv.count(bot, "minecraft:ender_eye");
		int pearls = Inv.count(bot, "minecraft:ender_pearl");
		int powder = Inv.count(bot, "minecraft:blaze_powder") + Inv.count(bot, "minecraft:blaze_rod") * 2;
		int eyesPossible = eyes + Math.min(pearls, powder);
		// Hat die Suche mit throw_eye begonnen, reichen 12 (der Rahmen); sonst lief der Bot nach ein paar Würfen für neue
		// Augen zurück in den Nether (Lauf 35, 04.11.2026).
		int eyesNeeded = de.hannes.aiplayer.action.ThrowEyeAction.searchStarted() ? 12 : END_EYES;
		if (eyesPossible < eyesNeeded) {
			missing.add((eyesNeeded - eyesPossible) + " more eye of ender (has " + eyes + " eyes, " + pearls + " pearls, "
					+ powder + " blaze powder incl. rods; eye = pearl + blaze powder)");
		}
		if (Inv.findItem(bot, "minecraft:bow") < 0) {
			missing.add("bow");
		}
		int arrows = Inv.count(bot, "minecraft:arrow");
		if (arrows < END_ARROWS) {
			missing.add((END_ARROWS - arrows) + " more arrow (has " + arrows + ")");
		}
		int blocks = Inv.countScaffold(bot);
		if (blocks < END_BLOCKS) {
			missing.add((END_BLOCKS - blocks) + " more building blocks (has " + blocks + ")");
		}
		if (food < MIN_FOOD) {
			missing.add((MIN_FOOD - food) + " more cooked food (has " + food + ")");
		}
		return missing;
	}

	/** Zieht Gold in seinen Platz und Diamant in die übrigen an. Vorher {@link #missing} prüfen. */
	public static void equip(ServerPlayer bot) {
		EquipmentSlot gold = goldSlot(bot);
		for (EquipmentSlot slot : GOLD_ORDER) {
			boolean wantGold = slot == gold;
			ItemStack worn = bot.getItemBySlot(slot);
			if (wantGold ? isGold(bot, worn) : isDiamondOrBetter(bot, worn)) {
				continue;
			}
			int index = find(bot, slot, wantGold);
			if (index < 0) {
				continue;
			}
			ItemStack stack = bot.getInventory().getItem(index);
			bot.getInventory().setItem(index, worn);
			bot.setItemSlot(slot, stack);
		}
	}

	private static int find(ServerPlayer bot, EquipmentSlot slot, boolean gold) {
		for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (!stack.isEmpty() && bot.getEquipmentSlotForItem(stack) == slot
					&& (gold ? isGold(bot, stack) : isDiamondOrBetter(bot, stack))) {
				return i;
			}
		}
		return -1;
	}
}
