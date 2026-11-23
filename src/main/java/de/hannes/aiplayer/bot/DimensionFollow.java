package de.hannes.aiplayer.bot;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.action.ActionRunner;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.protocol.Priority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dimension Follow (Wunsch von Hannes, 28.10.2026): Wechselt der verfolgte Spieler die Dimension, wird der Bot nach einer
 * kurzen Ladezeit auf einen sicheren Platz neben ihm teleportiert. Ausgelöst nur durch den Wechsel des Spielers, nie
 * durch den Bot selbst (sonst holte ihn jedes eigene Portal zurück); je Wechsel höchstens {@link #MAX_ATTEMPTS} Versuche
 * mit Abstand, dann Meldung und Ruhe bis zum nächsten Wechsel. Eine Abkürzung: Der Bot nimmt nicht selbst das Portal.
 */
final class DimensionFollow {
	/** Ladezeit nach dem Wechsel des Spielers, bevor der Bot nachkommt. */
	private static final int DELAY_TICKS = 60;
	private static final int RETRY_TICKS = 100;
	private static final int MAX_ATTEMPTS = 5;
	private static final int SEARCH_RADIUS = 8;

	private final EventBus bus;
	private final ActionRunner runner;
	private ResourceKey<Level> lastPlayerDim;
	private long dueTick = -1;
	private int attempts;

	DimensionFollow(EventBus bus, ActionRunner runner) {
		this.bus = bus;
		this.runner = runner;
	}

	void tick(MinecraftServer server, ServerPlayer bot) {
		if (!AiSettings.dimensionFollow || AiSettings.followTarget == null || bot == null) {
			lastPlayerDim = null;
			dueTick = -1;
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(AiSettings.followTarget);
		if (player == null || player == bot) {
			return;
		}
		ResourceKey<Level> playerDim = player.level().dimension();
		long tick = server.getTickCount();
		if (lastPlayerDim == null) {
			lastPlayerDim = playerDim;
			return;
		}
		if (playerDim != lastPlayerDim) {
			lastPlayerDim = playerDim;
			attempts = 0;
			dueTick = bot.level().dimension() == playerDim ? -1 : tick + DELAY_TICKS;
		}
		if (dueTick < 0 || tick < dueTick) {
			return;
		}
		if (bot.level().dimension() == playerDim) {
			// Der Bot ist schon da (selbst durchs Portal gegangen): nichts zu tun.
			dueTick = -1;
			return;
		}
		attempts++;
		BlockPos spot = safeSpot(player.level(), player.blockPosition());
		if (spot == null) {
			if (attempts >= MAX_ATTEMPTS) {
				dueTick = -1;
				player.sendSystemMessage(Component.literal("[KI] Dimension Follow: kein sicherer Platz neben dir gefunden, "
						+ bot.getGameProfile().name() + " bleibt in " + bot.level().dimension().identifier().getPath() + "."));
			} else {
				dueTick = tick + RETRY_TICKS;
			}
			return;
		}
		dueTick = -1;
		String from = bot.level().dimension().identifier().toString();
		long gameTick = server.overworld().getGameTime();
		runner.stop(bot, gameTick);
		bot.teleportTo(player.level(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, java.util.Set.of(),
				player.getYRot(), 0, true);
		JsonObject data = new JsonObject();
		data.addProperty("from", from);
		data.addProperty("to", playerDim.identifier().toString());
		data.add("pos", GameJson.pos(spot));
		data.addProperty("player", player.getGameProfile().name());
		data.addProperty("hint", "teleported next to the player; look first, then carry on with the goal here or go back via a portal");
		bus.emit("dimension_followed", gameTick, Priority.HIGH, data);
	}

	/** Nächster Platz mit festem, ungefährlichem Boden und 2 freien Feldern, um den Spieler herum (auch wenn er schwebt). */
	static BlockPos safeSpot(ServerLevel level, BlockPos around) {
		for (int r = 0; r <= SEARCH_RADIUS; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int dy = 4; dy >= -24; dy--) {
						BlockPos feet = around.offset(dx, dy, dz);
						if (standable(level, feet)) {
							return feet;
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean standable(ServerLevel level, BlockPos feet) {
		if (!level.isInWorldBounds(feet) || !level.hasChunkAt(feet)) {
			return false;
		}
		BlockState ground = level.getBlockState(feet.below());
		if (!ground.isFaceSturdy(level, feet.below(), Direction.UP) || ground.is(Blocks.MAGMA_BLOCK)
				|| ground.is(BlockTags.FIRE) || !level.getFluidState(feet.below()).isEmpty()) {
			return false;
		}
		for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
			BlockState state = level.getBlockState(cell);
			if (!state.getCollisionShape(level, cell).isEmpty() || !level.getFluidState(cell).isEmpty()
					|| state.is(BlockTags.FIRE) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL)) {
				return false;
			}
		}
		return true;
	}
}
