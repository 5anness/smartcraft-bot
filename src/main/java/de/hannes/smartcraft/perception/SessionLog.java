package de.hannes.smartcraft.perception;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.debug.DebugLog;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/** Hält beim Welteintritt Seed und Startposition fest, damit sich Fehler nachstellen lassen. */
public final class SessionLog {
	private SessionLog() {
	}

	public static void register(DebugLog debugLog) {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();

			JsonObject msg = new JsonObject();
			msg.addProperty("what", "world_joined");
			msg.addProperty("seed", server.overworld().getSeed());
			msg.addProperty("dimension", player.level().dimension().identifier().toString());
			msg.add("pos", GameJson.pos(player.blockPosition()));

			debugLog.write("session", player.level().getGameTime(), msg);
		});
	}
}
