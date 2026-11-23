package de.hannes.aiplayer.reflex;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.protocol.Priority;
import de.hannes.aiplayer.skill.BlockPlacer;
import de.hannes.aiplayer.skill.Inv;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wasser wieder zubauen: Läuft in eine Lücke, die der Bot gerade abgebaut hat, Wasser hinein (etwa
 * beim Abbau über dem Kopf), setzt er sofort einen Block hinein. Sonst flutet es den Gang, und Abbauen
 * im Wasser dauert ein Vielfaches. Steht der Bot selbst im Wasser, greift der Reflex nicht; dafür gibt es
 * {@code seal_water}.
 */
public final class WaterGuard {
	/** So lange nach dem Abbau achtet er auf nachfließendes Wasser (Wasser fließt alle 5 Ticks weiter). */
	private static final int WATCH_TICKS = 30;
	private static final Map<BlockPos, Long> watched = new LinkedHashMap<>();
	/** Nach eigenem Ausgießen so lange still: test_obsidian, der Reflex mauerte das Wasser von make_obsidian zu. */
	private static final int PAUSE_AFTER_POUR = 100;
	private static long pausedUntil;
	private static BlockPos pausedAt;

	private static long suppressedUntil;

	/** Ganz still bis {@code until}: make_obsidian lässt sein Wasser absichtlich in die Löcher laufen. */
	public static void suppress(long until) {
		suppressedUntil = until;
	}

	private WaterGuard() {
	}

	/** Aus der Wahrnehmung: Der Bot hat diesen Block abgebaut. */
	public static void noteBroken(BlockPos pos, long tick) {
		watched.put(pos.immutable(), tick + WATCH_TICKS);
	}

	/** Der Bot hat selbst Wasser ausgegossen: eine Weile nicht zubauen, nur rund um diese Stelle (3 Blöcke). */
	public static void pause(long tick, BlockPos at) {
		pausedUntil = tick + PAUSE_AFTER_POUR;
		pausedAt = at.immutable();
	}

	static void tick(ServerPlayer bot, long tick, EventBus bus) {
		if (watched.isEmpty()) {
			return;
		}
		if (tick < suppressedUntil) {
			watched.clear();
			return;
		}
		if (tick < pausedUntil && pausedAt != null) {
			watched.keySet().removeIf(pos -> pos.distManhattan(pausedAt) <= 3);
			if (watched.isEmpty()) {
				return;
			}
		}
		if (bot.isInWater()) {
			// Steht er selbst im Wasser, kommt es aus seinem eigenen Feld: Zubauen würde ihm den Ausweg versperren.
			watched.clear();
			return;
		}
		BlockPos feet = bot.blockPosition();
		Iterator<Map.Entry<BlockPos, Long>> entries = watched.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<BlockPos, Long> entry = entries.next();
			BlockPos pos = entry.getKey();
			if (tick > entry.getValue()) {
				entries.remove();
				continue;
			}
			if (!bot.level().getFluidState(pos).is(FluidTags.WATER) || pos.equals(feet) || pos.equals(feet.above())) {
				continue;
			}
			entries.remove();
			int slot = Inv.findScaffold(bot);
			if (slot < 0) {
				continue;
			}
			ItemStack heldBefore = bot.getMainHandItem();
			String problem = BlockPlacer.place(bot, slot, pos);
			// Das Werkzeug zurück in die Hand, damit eine laufende Aktion damit weitermacht.
			if (bot.getMainHandItem() != heldBefore) {
				int back = Inv.findSlot(bot, stack -> stack == heldBefore);
				if (back >= 0) {
					Inv.hold(bot, back);
				}
			}
			JsonObject data = new JsonObject();
			data.add("pos", GameJson.pos(pos));
			data.addProperty("sealed", problem == null);
			if (problem != null) {
				data.addProperty("reason", problem);
			} else {
				data.addProperty("block", GameJson.blockId(bot.level().getBlockState(pos)));
			}
			bus.emit("reflex_sealed_water", tick, Priority.NORMAL, data);
		}
	}
}
