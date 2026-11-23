package de.hannes.smartcraft.skill;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;

/**
 * Isst ein Item so, wie ein Spieler es tut: in die Hand nehmen, Rechtsklick halten, bis es weg ist.
 * Danach liegt wieder in der Hand, was vorher dort war. Einmal pro Tick {@link #tick} aufrufen.
 */
public final class Eater {
	private boolean drink;
	public enum State {
		RUNNING, DONE, FAILED
	}

	/** Länger dauert kein Essen (normal 32 Ticks); danach gilt es als gescheitert. */
	private static final int MAX_TICKS = 80;
	private static final double HARMFUL_PENALTY = 1000;
	private static final double SPECIAL_PENALTY = 100;

	private final int slot;
	private State state = State.RUNNING;
	private String failure;
	private boolean started;
	private int ticks;
	private int previousSelected;
	private int countBefore;
	private String itemId;

	/** @param slot Inventar-Slot mit dem Essen */
	public Eater(int slot) {
		this(slot, false);
	}

	/** @param drink auch Tränke und anderes Trinkbare, nicht nur Essen */
	public Eater(int slot, boolean drink) {
		this.drink = drink;
		this.slot = slot;
	}

	/** Was gegessen wird bzw. wurde; {@code null} vor dem ersten Tick. */
	public String itemId() {
		return itemId;
	}

	/** Bei {@link State#FAILED}: {@code not_edible}, {@code not_hungry} oder {@code interrupted}. */
	public String failure() {
		return failure;
	}

	/** Erster Platz mit einem Trank der Feuerresistenz, oder -1. */
	public static int fireResistanceSlot(ServerPlayer bot) {
		Inventory inventory = bot.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			PotionContents contents = inventory.getItem(slot).get(DataComponents.POTION_CONTENTS);
			if (contents == null || !inventory.getItem(slot).has(DataComponents.CONSUMABLE)) {
				continue;
			}
			for (MobEffectInstance effect : contents.getAllEffects()) {
				if (effect.is(MobEffects.FIRE_RESISTANCE)) {
					return slot;
				}
			}
		}
		return -1;
	}

	public static boolean isFood(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.FOOD) && stack.has(DataComponents.CONSUMABLE);
	}

	/** Ob das Essen schaden kann: Gift, Hunger oder Übelkeit als Nebenwirkung, oder ein Teleport wie bei der Chorusfrucht. */
	public static boolean isHarmful(ItemStack stack) {
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable == null) {
			return false;
		}
		for (ConsumeEffect effect : consumable.onConsumeEffects()) {
			if (effect instanceof TeleportRandomlyConsumeEffect) {
				return true;
			}
			if (effect instanceof ApplyStatusEffectsConsumeEffect apply
					&& apply.effects().stream().anyMatch(instance -> !instance.getEffect().value().isBeneficial())) {
				return true;
			}
		}
		return false;
	}

	/** Ob das Essen eine Sonderwirkung hat (goldener Apfel); so etwas isst der Bot nicht nebenbei weg. */
	private static boolean isSpecial(ItemStack stack) {
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		return consumable != null && !consumable.onConsumeEffects().isEmpty();
	}

	/**
	 * Das beste Essen im Inventar: am meisten Sättigung; Schädliches nur, wenn es nichts anderes gibt.
	 *
	 * @param allowHarmful ob Schädliches überhaupt in Frage kommt
	 * @return Slot oder -1
	 */
	public static int bestFoodSlot(ServerPlayer bot, boolean allowHarmful) {
		return bestFoodSlot(bot, allowHarmful, true);
	}

	/** @param allowSpecial auch Essen mit Sonderwirkung (goldener Apfel); beim bloßen Auffüllen nicht */
	public static int bestFoodSlot(ServerPlayer bot, boolean allowHarmful, boolean allowSpecial) {
		Inventory inventory = bot.getInventory();
		int best = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int slot = 0; slot < Inv.MAIN_SLOTS; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!isFood(stack) || !allowHarmful && isHarmful(stack) || !allowSpecial && isSpecial(stack)) {
				continue;
			}
			FoodProperties food = stack.get(DataComponents.FOOD);
			// Sättigung im Spiel: Hungerpunkte mal Faktor mal zwei.
			double score = food.nutrition() + food.nutrition() * food.saturation() * 2.0;
			if (isHarmful(stack)) {
				score -= HARMFUL_PENALTY;
			} else if (isSpecial(stack)) {
				score -= SPECIAL_PENALTY;
			}
			if (score > bestScore) {
				bestScore = score;
				best = slot;
			}
		}
		return best;
	}

	public State tick(ServerPlayer bot) {
		if (state != State.RUNNING) {
			return state;
		}
		Inventory inventory = bot.getInventory();
		if (!started) {
			started = true;
			ItemStack stack = inventory.getItem(slot);
			itemId = Inv.itemId(stack);
			Consumable consumable = stack.get(DataComponents.CONSUMABLE);
			if (!drink && !isFood(stack) || consumable == null) {
				return fail(bot, "not_edible");
			}
			if (!consumable.canConsume(bot, stack)) {
				return fail(bot, "not_hungry");
			}
			if (bot.isUsingItem()) {
				// Schild oder Ähnliches: erst loslassen, sonst nimmt das Spiel den Klick nicht an.
				bot.stopUsingItem();
			}
			countBefore = Inv.count(bot, itemId);
			previousSelected = inventory.getSelectedSlot();
			Inv.hold(bot, slot);
			bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
			if (!bot.isUsingItem()) {
				restoreHand(bot);
				return fail(bot, "not_edible");
			}
			return state;
		}
		if (bot.isUsingItem() && ++ticks < MAX_TICKS) {
			return state;
		}
		boolean eaten = Inv.count(bot, itemId) < countBefore;
		if (bot.isUsingItem()) {
			bot.stopUsingItem();
		}
		restoreHand(bot);
		if (!eaten) {
			return fail(bot, "interrupted");
		}
		state = State.DONE;
		return state;
	}

	/** Bei Abbruch von außen: aufhören zu kauen und die Hand zurückgeben. */
	public void abort(ServerPlayer bot) {
		if (state == State.RUNNING && started) {
			if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND) {
				bot.stopUsingItem();
			}
			restoreHand(bot);
			state = State.FAILED;
			failure = "interrupted";
		}
	}

	private State fail(ServerPlayer bot, String reason) {
		state = State.FAILED;
		failure = reason;
		return state;
	}

	private void restoreHand(ServerPlayer bot) {
		if (Inventory.isHotbarSlot(slot)) {
			bot.getInventory().setSelectedSlot(previousSelected);
		} else {
			// Inv.hold hat getauscht; derselbe Tausch noch einmal stellt den alten Zustand her.
			Inv.hold(bot, slot);
		}
	}
}
