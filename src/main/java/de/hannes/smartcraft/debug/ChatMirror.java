package de.hannes.smartcraft.debug;

import de.hannes.smartcraft.protocol.Event;
import de.hannes.smartcraft.protocol.Priority;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Zeigt jedes Event zusätzlich im Chat, damit man beim Testen im Spiel sieht, was der Bot
 * wahrnimmt. Umschaltbar mit {@code /smartcraft events on|off}.
 */
public final class ChatMirror {
	private MinecraftServer server;
	private boolean enabled = true;

	public void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean enabled() {
		return enabled;
	}

	/** Läuft auf dem Server-Thread, weil Events nur dort entstehen. */
	public void show(Event event) {
		if (!enabled || server == null) {
			return;
		}
		StringBuilder text = new StringBuilder("[KI] ").append(event.name());
		if (event.status() != null) {
			text.append(' ').append(event.status().wireName());
		}
		if (event.reason() != null) {
			text.append(" (").append(event.reason()).append(')');
		}
		com.google.gson.JsonObject data = event.data();
		if (data != null && data.has("api_key")) {
			// Den API-Schlüssel nie im Chat zeigen (alle Spieler sehen diese Zeilen).
			data = data.deepCopy();
			data.addProperty("api_key", "***");
		}
		Component line = Component.literal(text.append(' ').append(data).toString())
				.withStyle(event.prio() == Priority.HIGH ? ChatFormatting.RED : ChatFormatting.GRAY);
		server.getPlayerList().broadcastSystemMessage(line, false);
	}
}
