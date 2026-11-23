package de.hannes.smartcraft.bot;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import de.hannes.smartcraft.SmartCraftMod;
import de.hannes.smartcraft.debug.DebugLog;
import de.hannes.smartcraft.perception.DeathRecord;
import de.hannes.smartcraft.perception.EventBus;
import de.hannes.smartcraft.perception.GameJson;
import de.hannes.smartcraft.protocol.Priority;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.LongConsumer;

/**
 * Verwaltet den einen KI-Spieler: Beitritt über den normalen Join-Ablauf des Servers,
 * Tick, Respawn, Austritt. Alle Methoden laufen auf dem Server-Thread.
 */
public final class BotManager {
	public static final String DEFAULT_NAME = "Claude";

	/** So lange bleibt der Bot nach dem Tod liegen; entspricht der Sterbeanimation. */
	private static final int RESPAWN_DELAY_TICKS = 20;

	/** Warum ein Befehl abgelehnt wurde. {@code OK} heißt angenommen. */
	public enum Result {
		OK, ALREADY_JOINED, JOIN_IN_PROGRESS, INVALID_NAME, NAME_TAKEN, SERVER_FULL, NOT_JOINED
	}

	private record PendingJoin(GameProfile profile, PrepareSpawnTask task, Consumer<Component> feedback) {
	}

	// Wird vom Mixin auch vom Client-Thread gelesen (Einzelspieler: gleiche JVM).
	private static volatile UUID botId;

	private final DebugLog debugLog;
	private final EventBus events;
	private PendingJoin pending;
	private ServerPlayer bot;
	private FakeConnection connection;
	private int ticksDead;
	private DeathRecord lastDeath;
	private ObjLongConsumer<ServerPlayer> controller = (player, tick) -> {
	};
	private LongConsumer onBotGone = tick -> {
	};

	public BotManager(DebugLog debugLog, EventBus events) {
		this.debugLog = debugLog;
		this.events = events;
	}

	/** Ob der Server die Bewegung dieser Figur selbst berechnen muss, weil kein Client dahintersteht. */
	public static boolean isBot(Player player) {
		UUID id = botId;
		return id != null && player instanceof ServerPlayer && id.equals(player.getUUID());
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> reset());
	}

	/**
	 * @param controller läuft jeden Tick direkt vor dem Tick des Bots und setzt dessen Eingaben
	 * @param onBotGone  läuft, wenn der Bot das Spiel verlässt
	 */
	public void setController(ObjLongConsumer<ServerPlayer> controller, LongConsumer onBotGone) {
		this.controller = controller;
		this.onBotGone = onBotGone;
	}

	public ServerPlayer bot() {
		return bot;
	}

	public boolean isJoining() {
		return pending != null;
	}

	/** Der letzte Tod des Bots in dieser Sitzung oder {@code null}. */
	public DeathRecord lastDeath() {
		return lastDeath;
	}

	public void recordDeath(DeathRecord death) {
		lastDeath = death;
	}

	/**
	 * Startet den Beitritt. Das Spiel lädt erst die Chunks am Spawnpunkt; der eigentliche Join
	 * passiert einige Ticks später in {@link #tick}. {@code feedback} erhält dann das Ergebnis.
	 */
	public Result requestJoin(MinecraftServer server, String name, Consumer<Component> feedback) {
		if (bot != null) {
			return Result.ALREADY_JOINED;
		}
		if (pending != null) {
			return Result.JOIN_IN_PROGRESS;
		}
		if (!StringUtil.isValidPlayerName(name)) {
			return Result.INVALID_NAME;
		}
		PlayerList players = server.getPlayerList();
		if (players.getPlayerByName(name) != null) {
			return Result.NAME_TAKEN;
		}
		if (players.getPlayers().size() >= players.getMaxPlayers()) {
			return Result.SERVER_FULL;
		}

		// Feste UUID aus dem Namen: so findet das Spiel beim nächsten Join den gespeicherten Zustand wieder.
		GameProfile profile = UUIDUtil.createOfflineProfile(name);
		PrepareSpawnTask task = new PrepareSpawnTask(server, new NameAndId(profile));
		task.start(packet -> {
		});
		pending = new PendingJoin(profile, task, feedback);

		log(server, "bot_join_requested", name, null);
		return Result.OK;
	}

	/** Bricht einen laufenden Beitritt ab oder lässt den Bot das Spiel verlassen. */
	public Result leave(MinecraftServer server) {
		if (pending != null) {
			pending.task().close();
			log(server, "bot_join_cancelled", pending.profile().name(), null);
			pending = null;
			return Result.OK;
		}
		if (bot == null) {
			return Result.NOT_JOINED;
		}

		ServerPlayer leaving = bot;
		onBotGone.accept(server.overworld().getGameTime());
		forgetBot();
		// Gleicher Weg wie bei einem echten Verbindungsende: Leave-Nachricht, Speichern, Tab-Liste.
		leaving.connection.onDisconnect(new DisconnectionDetails(Component.literal("/smartcraft leave")));
		botId = null;
		log(server, "bot_left", leaving.getGameProfile().name(), leaving);
		return Result.OK;
	}

	private void tick(MinecraftServer server) {
		if (pending != null) {
			tickPendingJoin(server);
		}
		if (bot == null) {
			return;
		}

		if (server.getPlayerList().getPlayer(bot.getUUID()) != bot) {
			// Von außen entfernt, z. B. durch /kick.
			log(server, "bot_removed", bot.getGameProfile().name(), bot);
			onBotGone.accept(server.overworld().getGameTime());
			forgetBot();
			botId = null;
			return;
		}
		if (server.isPaused()) {
			return;
		}

		connection.applyClientReactions();
		// Welche Chunks um einen Spieler geladen bleiben, aktualisiert der Server sonst nur beim
		// Eintreffen von Bewegungspaketen des Clients. Ohne das blieben die Chunks am alten Ort.
		bot.level().getChunkSource().move(bot);

		if (bot.isDeadOrDying()) {
			ticksDead++;
		}
		// wonGame: Der Bot ist durchs Endportal gegangen und wartet wie ein Client im Abspann.
		if (bot.wonGame || ticksDead >= RESPAWN_DELAY_TICKS) {
			respawn(server);
		}

		controller.accept(bot, bot.level().getGameTime());

		// Bei echten Spielern stößt die Netzwerkverbindung diesen Tick an (Physik, Hunger, Schaden).
		bot.doTick();
	}

	/** Tut, was ein Client mit dem Klick auf „Respawn“ auslöst; Bett und Weltspawn wählt das Spiel. */
	private void respawn(MinecraftServer server) {
		boolean died = bot.isDeadOrDying();
		ServerGamePacketListenerImpl listener = bot.connection;
		listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));

		// Der Server legt beim Respawn eine neue Spielerfigur an.
		bot = listener.player;
		ticksDead = 0;
		listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());

		if (died) {
			String name = bot.getGameProfile().name();
			server.getPlayerList().broadcastSystemMessage(
					Component.literal(name + " ist wieder da").withStyle(ChatFormatting.YELLOW), false);

			JsonObject data = new JsonObject();
			data.add("pos", GameJson.pos(bot.blockPosition()));
			data.addProperty("dimension", bot.level().dimension().identifier().toString());
			events.emit("player_respawned", bot.level().getGameTime(), Priority.NORMAL, data);
		}
	}

	private void tickPendingJoin(MinecraftServer server) {
		PendingJoin join = pending;
		String name = join.profile().name();
		try {
			if (!join.task().tick()) {
				return;
			}
			pending = null;

			if (server.getPlayerList().getPlayerByName(name) != null) {
				join.task().close();
				join.feedback().accept(Component.literal("Beitritt abgebrochen: Der Name " + name + " ist inzwischen vergeben."));
				log(server, "bot_join_failed", name, null);
				return;
			}

			// Muss vor dem Spawn gesetzt sein, damit die Figur vom ersten Tick an serverseitig simuliert wird.
			botId = join.profile().id();
			connection = new FakeConnection();
			bot = join.task().spawnPlayer(connection, CommonListenerCookie.createInitial(join.profile(), false));
			// Ein Client meldet nach dem Laden der Welt, dass er bereit ist; bis dahin ist der Spieler unverwundbar.
			bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
			if (bot.gameMode() == GameType.CREATIVE) {
				// Neue Spieler übernehmen den Modus der Welt. Im Kreativmodus wäre der Bot unverwundbar
				// und für Mobs unsichtbar; die KI soll das Spiel aber regulär spielen.
				bot.setGameMode(GameType.SURVIVAL);
			}
			log(server, "bot_joined", name, bot);
		} catch (RuntimeException e) {
			pending = null;
			forgetBot();
			botId = null;
			SmartCraftMod.LOGGER.error("KI-Spieler {} konnte nicht beitreten", name, e);
			join.feedback().accept(Component.literal("Beitritt fehlgeschlagen: " + e));
			log(server, "bot_join_failed", name, null);
		}
	}

	private void forgetBot() {
		bot = null;
		connection = null;
		ticksDead = 0;
	}

	private void reset() {
		if (pending != null) {
			pending.task().close();
		}
		pending = null;
		forgetBot();
		botId = null;
		lastDeath = null;
	}

	private void log(MinecraftServer server, String what, String name, ServerPlayer player) {
		JsonObject msg = new JsonObject();
		msg.addProperty("what", what);
		msg.addProperty("name", name);
		if (player != null) {
			msg.addProperty("dimension", player.level().dimension().identifier().toString());
			msg.add("pos", GameJson.pos(player.blockPosition()));
		}
		debugLog.write("session", server.overworld().getGameTime(), msg);
		SmartCraftMod.LOGGER.info("{}", msg);
	}
}
