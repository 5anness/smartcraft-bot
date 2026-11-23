package de.hannes.aiplayer.memory;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.protocol.Priority;
import de.hannes.aiplayer.skill.Inv;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Behält die Öfen im Auge, die der Bot befüllt hat, und meldet einmal {@code smelting_done}, wenn
 * einer fertig ist oder ohne Brennstoff stehen bleibt. Gilt bis zum Neustart der Welt; danach
 * findet {@code smelt_collect} die Öfen über das Ortsgedächtnis.
 * Ein Ofen arbeitet nur, solange sein Chunk geladen ist.
 */
public final class FurnaceWatch {
	private static final int CHECK_INTERVAL = 20;
	private static final int SLOT_INPUT = 0;
	private static final int SLOT_RESULT = 2;

	private static final class Watched {
		final ResourceKey<Level> dimension;
		final BlockPos pos;
		boolean announced;
		int stalledChecks;

		Watched(ResourceKey<Level> dimension, BlockPos pos) {
			this.dimension = dimension;
			this.pos = pos;
		}
	}

	private static final List<Watched> WATCHED = new ArrayList<>();
	private static EventBus bus;

	private FurnaceWatch() {
	}

	public static void register(EventBus events) {
		bus = events;
		ServerTickEvents.END_SERVER_TICK.register(FurnaceWatch::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> WATCHED.clear());
	}

	/** Ab jetzt beobachten; ein erneutes Befüllen setzt die Meldung zurück. */
	public static void watch(ServerLevel level, BlockPos pos) {
		WATCHED.removeIf(watched -> watched.pos.equals(pos) && watched.dimension == level.dimension());
		WATCHED.add(new Watched(level.dimension(), pos.immutable()));
	}

	public static void unwatch(ServerLevel level, BlockPos pos) {
		WATCHED.removeIf(watched -> watched.pos.equals(pos) && watched.dimension == level.dimension());
	}

	/** Beobachtete Öfen in dieser Dimension, die nächsten zuerst. */
	public static List<BlockPos> watched(ServerLevel level, BlockPos from) {
		List<BlockPos> result = new ArrayList<>();
		for (Watched watched : WATCHED) {
			if (watched.dimension == level.dimension()) {
				result.add(watched.pos);
			}
		}
		result.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
		return result;
	}

	private static void tick(MinecraftServer server) {
		if (WATCHED.isEmpty() || server.getTickCount() % CHECK_INTERVAL != 0) {
			return;
		}
		for (Iterator<Watched> iterator = WATCHED.iterator(); iterator.hasNext(); ) {
			Watched watched = iterator.next();
			ServerLevel level = server.getLevel(watched.dimension);
			if (level == null || !level.hasChunk(watched.pos.getX() >> 4, watched.pos.getZ() >> 4)) {
				continue;
			}
			if (!(level.getBlockEntity(watched.pos) instanceof AbstractFurnaceBlockEntity furnace)) {
				iterator.remove();
				continue;
			}
			if (watched.announced) {
				continue;
			}
			ItemStack input = furnace.getItem(SLOT_INPUT);
			ItemStack result = furnace.getItem(SLOT_RESULT);
			BlockState state = level.getBlockState(watched.pos);
			boolean lit = state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
			boolean complete = input.isEmpty();
			// Zweimal hintereinander aus und nicht leer: der Brennstoff ist alle, es geht nicht weiter.
			watched.stalledChecks = !complete && !lit ? watched.stalledChecks + 1 : 0;
			if (!complete && watched.stalledChecks < 2) {
				continue;
			}
			watched.announced = true;
			JsonObject data = new JsonObject();
			data.add("furnace", GameJson.pos(watched.pos));
			data.add("ready", Inv.describe(result));
			data.addProperty("complete", complete);
			if (!complete) {
				data.add("unsmelted", Inv.describe(input));
				data.addProperty("hint", "out of fuel");
			}
			bus.emit("smelting_done", level.getGameTime(), Priority.NORMAL, data);
		}
	}
}
