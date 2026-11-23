package de.hannes.aiplayer.bot;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@code /aiplayer inventar}: Das Inventar des Bots als Doppelkiste, live mit dem echten Inventar verbunden. Reihe 1: Helm,
 * Brust, Hose, Schuhe, zweite Hand; Reihe 2: dunkle Glasscheiben als Trenner; Reihen 3–6: die 36 normalen Plätze (Reihe 3 =
 * Schnellleiste). Darunter das eigene Inventar des Spielers. Vanilla-Doppelkiste, geht ohne Mod beim Client. Wunsch von Hannes.
 */
final class BotInventoryView {
	private static final EquipmentSlot[] EQUIPMENT = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
			EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
	private static final int MAIN_START = 18;
	private static final int SIZE = 54;

	private BotInventoryView() {
	}

	static void open(ServerPlayer viewer, ServerPlayer bot) {
		viewer.openMenu(new SimpleMenuProvider((id, inventory, player) -> new Menu(id, inventory, new View(bot)),
				Component.literal("Inventar von " + bot.getScoreboardName())));
	}

	/** Platz im Fenster ohne Gegenstück im Bot (Glasscheibe oder leerer Rest der ersten Reihe). */
	private static boolean locked(int slot) {
		return slot >= EQUIPMENT.length && slot < MAIN_START;
	}

	private static ItemStack filler(int slot) {
		if (slot < 9) {
			return ItemStack.EMPTY;
		}
		ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.gray());
		pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		return pane;
	}

	/** Container, der direkt auf das Inventar des Bots liest und schreibt. */
	private record View(ServerPlayer bot) implements Container {
		@Override
		public int getContainerSize() {
			return SIZE;
		}

		@Override
		public boolean isEmpty() {
			return bot.getInventory().isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			if (slot < EQUIPMENT.length) {
				return bot.getItemBySlot(EQUIPMENT[slot]);
			}
			if (locked(slot)) {
				return filler(slot);
			}
			return bot.getInventory().getItem(slot - MAIN_START);
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			if (locked(slot)) {
				return ItemStack.EMPTY;
			}
			ItemStack stack = getItem(slot);
			if (stack.isEmpty()) {
				return ItemStack.EMPTY;
			}
			ItemStack taken = stack.split(count);
			setItem(slot, stack);
			return taken;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			if (locked(slot)) {
				return ItemStack.EMPTY;
			}
			ItemStack stack = getItem(slot);
			setItem(slot, ItemStack.EMPTY);
			return stack;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			if (slot < EQUIPMENT.length) {
				bot.setItemSlot(EQUIPMENT[slot], stack);
			} else if (!locked(slot)) {
				bot.getInventory().setItem(slot - MAIN_START, stack);
			}
		}

		@Override
		public void setChanged() {
			bot.getInventory().setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return bot.isAlive() && !bot.isRemoved();
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			if (locked(slot)) {
				return false;
			}
			if (slot < 4) {
				// Rüstungsplätze nur mit passender Rüstung (oder Kopf/Kürbis beim Helm), wie im Spielerinventar.
				return bot.getEquipmentSlotForItem(stack) == EQUIPMENT[slot];
			}
			return true;
		}

		@Override
		public void clearContent() {
		}
	}

	private static final class Menu extends ChestMenu {
		Menu(int id, Inventory playerInventory, Container view) {
			super(MenuType.GENERIC_9x6, id, playerInventory, view, 6);
			// Plätze ersetzen, damit Glas und Rüstungsregeln auch beim Klicken gelten.
			for (int i = 0; i < SIZE; i++) {
				Slot old = slots.get(i);
				int index = i;
				Slot slot = new Slot(view, index, old.x, old.y) {
					@Override
					public boolean mayPlace(ItemStack stack) {
						return view.canPlaceItem(index, stack);
					}

					@Override
					public boolean mayPickup(Player player) {
						return !locked(index);
					}

					@Override
					public int getMaxStackSize() {
						return index < 4 ? 1 : super.getMaxStackSize();
					}
				};
				slot.index = i;
				slots.set(i, slot);
			}
		}

		@Override
		public void clicked(int slot, int button, ContainerInput input, Player player) {
			if (slot >= 0 && slot < SIZE && locked(slot)) {
				return;
			}
			super.clicked(slot, button, input, player);
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			Slot slot = slots.get(index);
			if (!slot.hasItem() || (index < SIZE && locked(index))) {
				return ItemStack.EMPTY;
			}
			ItemStack stack = slot.getItem();
			ItemStack copy = stack.copy();
			if (index < SIZE) {
				if (!moveItemStackTo(stack, SIZE, slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else {
				// Vom Spieler zum Bot: Rüstung zuerst auf den passenden Platz, sonst ins normale Inventar.
				if (!moveItemStackTo(stack, 0, 4, false) && !moveItemStackTo(stack, MAIN_START, SIZE, false)) {
					return ItemStack.EMPTY;
				}
			}
			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
			return copy;
		}
	}
}
