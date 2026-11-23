package de.hannes.smartcraft.bot;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * Verbrauch der KI in der XP-Leiste der menschlichen Spieler: Level = ganze Cent, Balken = Bruchteil des nächsten Cents,
 * darüber als Text in der Aktionsleiste Tokens und Dollar. Wunsch von Hannes (28.10.2026), Standard an beim Join,
 * {@code /aiplayer xpbar on|off}.
 * <p>
 * Nur angezeigt: Der Mod schickt den Clients eigene XP-Pakete, die echten Erfahrungspunkte auf dem Server bleiben
 * unverändert. Beim Ausschalten bekommen die Clients wieder ihre echten Werte. Die Zahlen kommen vom Begleitprozess
 * (Befehl {@code usage}); der Mod selbst kennt keine API.
 */
public final class UsageDisplay {
	private static final int INTERVAL = 20;

	private static boolean enabled;
	private static long tokens;
	private static double cost;

	private UsageDisplay() {
	}

	/** Vom Befehl {@code usage}: Gesamtverbrauch des Begleitprozesses seit seinem Start. */
	public static void update(long totalTokens, double totalCost) {
		tokens = totalTokens;
		cost = totalCost;
	}

	static boolean enabled() {
		return enabled;
	}

	/** Ob gerade überschriebene XP angezeigt werden (dann beim Ausschalten oder Verlassen zurücksetzen). */
	private static boolean showing;

	static void set(MinecraftServer server, boolean on) {
		enabled = on;
		if (!on) {
			restoreAll(server);
		}
	}

	private static void restoreAll(MinecraftServer server) {
		if (showing) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				restore(player);
			}
			showing = false;
		}
	}

	static void tick(MinecraftServer server, ServerPlayer bot) {
		if (bot == null) {
			// Bot hat das Spiel verlassen: echte XP zurück.
			restoreAll(server);
			return;
		}
		if (!enabled || server.getTickCount() % INTERVAL != 0) {
			return;
		}
		showing = true;
		int cents = (int) Math.floor(cost * 100);
		float progress = (float) Math.min(0.999, cost * 100 - cents);
		ClientboundSetExperiencePacket bar = new ClientboundSetExperiencePacket(progress, cents, cents);
		Component text = Component.literal(String.format(Locale.GERMANY, "%s · %s Tokens · %.2f $",
				bot.getGameProfile().name(), formatTokens(tokens), cost));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player == bot) {
				continue;
			}
			player.connection.send(bar);
			player.connection.send(new ClientboundSetActionBarTextPacket(text));
		}
	}

	/** Echte XP wieder anzeigen. */
	static void restore(ServerPlayer player) {
		player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience,
				player.experienceLevel));
	}

	private static String formatTokens(long count) {
		if (count >= 1_000_000) {
			return String.format(Locale.GERMANY, "%.2f Mio.", count / 1_000_000.0);
		}
		if (count >= 1_000) {
			return String.format(Locale.GERMANY, "%.1f Tsd.", count / 1_000.0);
		}
		return Long.toString(count);
	}
}
