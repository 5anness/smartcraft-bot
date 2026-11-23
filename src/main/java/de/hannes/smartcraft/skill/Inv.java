package de.hannes.smartcraft.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.perception.GameJson;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Handgriffe am Inventar des Bots. Slots 0 bis 8 sind die Schnellleiste, 9 bis 35 der Rest. */
public final class Inv {
	/** Schnellleiste plus Hauptinventar, ohne Rüstung und Zweithand. */
	public static final int MAIN_SLOTS = 36;
	/** Kein Werkzeug im Inventar bringt bei diesem Block etwas ein. */
	public static final int NO_USABLE_TOOL = -2;

	private Inv() {
	}

	/** Ergänzt {@code minecraft:}, wenn kein Namensraum angegeben ist. */
	public static String fullId(String id) {
		return id.contains(":") ? id : "minecraft:" + id;
	}

	public static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/** @return erster passender Slot im Hauptinventar oder -1 */
	public static int findSlot(ServerPlayer bot, Predicate<ItemStack> matches) {
		Inventory inventory = bot.getInventory();
		// Die Hand zuerst: was schon gehalten wird, muss nicht umgeräumt werden.
		int selected = inventory.getSelectedSlot();
		if (!inventory.getItem(selected).isEmpty() && matches.test(inventory.getItem(selected))) {
			return selected;
		}
		for (int slot = 0; slot < MAIN_SLOTS; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matches.test(stack)) {
				return slot;
			}
		}
		return -1;
	}

	public static int findItem(ServerPlayer bot, String itemId) {
		String id = fullId(itemId);
		return findSlot(bot, stack -> itemId(stack).equals(id));
	}

	public static int count(ServerPlayer bot, String itemId) {
		return counts(bot).getOrDefault(fullId(itemId), 0);
	}

	/** Nimmt den Inhalt eines Slots in die Haupthand. */
	public static void hold(ServerPlayer bot, int slot) {
		Inventory inventory = bot.getInventory();
		if (Inventory.isHotbarSlot(slot)) {
			inventory.setSelectedSlot(slot);
			return;
		}
		int selected = inventory.getSelectedSlot();
		ItemStack inHand = inventory.getItem(selected);
		inventory.setItem(selected, inventory.getItem(slot));
		inventory.setItem(slot, inHand);
	}

	/**
	 * Bestes Werkzeug für einen Block: das schnellste, das auch etwas fallen lässt.
	 *
	 * @return Slot; -1 für „mit der bloßen Hand bzw. dem, was gerade gehalten wird“;
	 * {@link #NO_USABLE_TOOL}, wenn der Block ein Werkzeug verlangt, das fehlt
	 */
	public static int bestToolSlot(ServerPlayer bot, BlockState state) {
		Inventory inventory = bot.getInventory();
		boolean needsTool = state.requiresCorrectToolForDrops();
		int best = -1;
		float bestSpeed = 1.0f;
		for (int slot = 0; slot < MAIN_SLOTS; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || needsTool && !stack.isCorrectToolForDrops(state)) {
				continue;
			}
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed || needsTool && best == -1) {
				best = slot;
				bestSpeed = speed;
			}
		}
		if (best == -1 && needsTool) {
			return NO_USABLE_TOOL;
		}
		if (best == -1 && inventory.getItem(inventory.getSelectedSlot()).isDamageableItem()) {
			// Kein Werkzeug hilft: dann nicht ausgerechnet eins abnutzen. Lieber einen Slot ohne Werkzeug.
			for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
				if (!inventory.getItem(slot).isDamageableItem()) {
					return slot;
				}
			}
		}
		return best;
	}

	/**
	 * Ein Block zum Verbauen unterwegs: voller Würfel, fällt nicht, kein Erz und nichts mit Innenleben.
	 *
	 * @return Slot oder -1
	 */
	public static int findScaffold(ServerPlayer bot) {
		// Was nicht brennt zuerst: Bretter fangen neben Lava und Feuer Feuer.
		int slot = findSlot(bot, stack -> isScaffold(stack) && !isFlammable(stack));
		if (slot < 0) {
			slot = findSlot(bot, Inv::isScaffold);
		}
		return slot >= 0 ? slot : planksFromLogs(bot);
	}

	/** So viele Stämme auf einmal zu Brettern, wenn sonst nichts zum Bauen da ist. */
	private static final int LOGS_PER_CRAFT = 16;

	/**
	 * Keine Bausteine mehr: Stämme zu Brettern (4 je Stamm, wie im 2×2-Feld des Inventars, ohne Werkbank). Liefert den Slot
	 * der Bretter oder -1. Wunsch von Hannes, 28.10.2026.
	 */
	private static int planksFromLogs(ServerPlayer bot) {
		for (int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack logs = bot.getInventory().getItem(i);
			if (logs.isEmpty() || !(logs.getItem() instanceof BlockItem block)
					|| !block.getBlock().defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)) {
				continue;
			}
			String id = itemId(logs).replace("stripped_", "");
			String planksId = id.replaceAll("_(log|wood|stem|hyphae)$", "_planks");
			var planks = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
					net.minecraft.resources.Identifier.parse(planksId));
			if (planks.isEmpty() || planksId.equals(id)) {
				continue;
			}
			int used = Math.min(LOGS_PER_CRAFT, logs.getCount());
			logs.shrink(used);
			bot.getInventory().add(new ItemStack(planks.get(), used * 4));
			return findSlot(bot, Inv::isScaffold);
		}
		return -1;
	}

	/** Alle Bausteine im Inventar zusammen (was {@link #findScaffold} nehmen würde). */
	public static int scaffoldCount(ServerPlayer bot) {
		int total = 0;
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (isScaffold(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Ob der Block aus diesem Item durch Lava oder Feuer in der Nähe zu brennen anfängt (Holz, Wolle …). */
	public static boolean isFlammable(ItemStack stack) {
		return stack.getItem() instanceof BlockItem item && item.getBlock().defaultBlockState().ignitedByLava();
	}

	public static boolean isScaffold(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) {
			return false;
		}
		BlockState state = item.getBlock().defaultBlockState();
		// Obsidian ist fürs Portal da, nicht für Brücken. Nether-Lauf 4 verbaute seine 10 Obsidian und hatte danach
		// keinen Weg zurück.
		if (state.is(net.minecraft.world.level.block.Blocks.OBSIDIAN) || state.is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN)) {
			return false;
		}
		// Stämme nicht verbauen: Daraus werden Bretter (4 je Stamm) und Werkzeug (Hannes' Lauf 28.10.: volle Stämme verbaut).
		if (state.is(net.minecraft.tags.BlockTags.LOGS)) {
			return false;
		}
		return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
				&& !(item.getBlock() instanceof FallingBlock)
				&& !state.hasBlockEntity()
				&& !state.is(ConventionalBlockTags.ORES)
				&& !state.is(ConventionalBlockTags.STORAGE_BLOCKS);
	}

	/** Wie viele Blöcke zum Verbauen im Inventar liegen. */
	public static int countScaffold(ServerPlayer bot) {
		int count = 0;
		for (int slot = 0; slot < MAIN_SLOTS; slot++) {
			ItemStack stack = bot.getInventory().getItem(slot);
			if (!stack.isEmpty() && isScaffold(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** Item-ID → Anzahl über das ganze Inventar inklusive Rüstung und Zweithand. */
	public static Map<String, Integer> counts(ServerPlayer bot) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Inventory inventory = bot.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				counts.merge(itemId(stack), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	/** Was seit {@code before} dazugekommen ist, als Liste von {@code {"id", "count"}}. */
	public static JsonArray gained(Map<String, Integer> before, ServerPlayer bot) {
		JsonArray gained = new JsonArray();
		counts(bot).forEach((id, count) -> {
			int more = count - before.getOrDefault(id, 0);
			if (more > 0) {
				gained.add(GameJson.stack(id, more));
			}
		});
		return gained;
	}

	public static boolean hasRoomFor(ServerPlayer bot, ItemStack stack) {
		Inventory inventory = bot.getInventory();
		return inventory.getFreeSlot() != -1 || inventory.getSlotWithRemainingSpace(stack) != -1;
	}

	/** Das Item in der Haupthand mit Anzahl und, bei Werkzeugen, Haltbarkeit; {@code null} bei leerer Hand. */
	public static JsonElement heldJson(ServerPlayer bot) {
		return describe(bot.getMainHandItem());
	}

	public static JsonElement describe(ItemStack stack) {
		if (stack.isEmpty()) {
			return JsonNull.INSTANCE;
		}
		JsonObject json = GameJson.stack(itemId(stack), stack.getCount());
		if (stack.isDamageableItem()) {
			JsonObject durability = new JsonObject();
			durability.addProperty("left", stack.getMaxDamage() - stack.getDamageValue());
			durability.addProperty("max", stack.getMaxDamage());
			json.add("durability", durability);
		}
		return json;
	}
}
