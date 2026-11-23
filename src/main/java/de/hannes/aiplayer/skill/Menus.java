package de.hannes.aiplayer.skill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntPredicate;

/**
 * Handgriffe in offenen Fenstern (Werkbank, Ofen, Truhe). Alles läuft über dieselben Klicks, die
 * ein Spieler macht; der Server prüft sie wie bei einem echten Client.
 */
public final class Menus {
	private Menus() {
	}

	/** @return Nummer des Fenster-Slots, der diesen Platz eines Behälters zeigt, sonst -1 */
	public static int slotFor(AbstractContainerMenu menu, Container container, int containerSlot) {
		for (Slot slot : menu.slots) {
			if (slot.container == container && slot.getContainerSlot() == containerSlot) {
				return slot.index;
			}
		}
		return -1;
	}

	/** Umschalt-Klick: schiebt den ganzen Stapel in den jeweils anderen Bereich des Fensters. */
	public static void quickMove(ServerPlayer bot, AbstractContainerMenu menu, int slot) {
		menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, bot);
	}

	/**
	 * Legt bis zu {@code count} Items aus einem Slot in die Ziel-Slots: Stapel aufnehmen, einzeln
	 * oder ganz ablegen, Rest zurücklegen.
	 *
	 * @return wie viele tatsächlich umgelegt wurden
	 */
	public static int move(ServerPlayer bot, AbstractContainerMenu menu, int from, int count, IntPredicate targets) {
		int want = Math.min(count, menu.getSlot(from).getItem().getCount());
		if (want <= 0 || !menu.getCarried().isEmpty()) {
			return 0;
		}
		menu.clicked(from, 0, ContainerInput.PICKUP, bot);
		int moved = 0;
		for (int index = 0; index < menu.slots.size() && moved < want; index++) {
			ItemStack carried = menu.getCarried();
			if (carried.isEmpty()) {
				break;
			}
			if (index == from || !targets.test(index)) {
				continue;
			}
			Slot target = menu.getSlot(index);
			if (!target.mayPlace(carried)) {
				continue;
			}
			ItemStack present = target.getItem();
			int space;
			if (present.isEmpty()) {
				space = target.getMaxStackSize(carried);
			} else if (ItemStack.isSameItemSameComponents(present, carried)) {
				space = target.getMaxStackSize(present) - present.getCount();
			} else {
				space = 0;
			}
			int amount = Math.min(space, Math.min(want - moved, carried.getCount()));
			if (amount <= 0) {
				continue;
			}
			if (amount == carried.getCount()) {
				menu.clicked(index, 0, ContainerInput.PICKUP, bot);
			} else {
				// Rechtsklick legt genau ein Item ab.
				for (int i = 0; i < amount; i++) {
					menu.clicked(index, 1, ContainerInput.PICKUP, bot);
				}
			}
			moved += amount;
		}
		if (!menu.getCarried().isEmpty()) {
			menu.clicked(from, 0, ContainerInput.PICKUP, bot);
		}
		return moved;
	}

	/** Schließt das offene Fenster; Items im Craftingraster oder am Mauszeiger gehen zurück ins Inventar. */
	public static void close(ServerPlayer bot) {
		if (bot.containerMenu != bot.inventoryMenu) {
			bot.closeContainer();
		} else {
			bot.doCloseContainer();
		}
	}
}
