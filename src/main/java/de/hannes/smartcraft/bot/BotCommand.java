package de.hannes.smartcraft.bot;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.hannes.smartcraft.action.ActionRunner;
import de.hannes.smartcraft.action.CommandDispatcher;
import de.hannes.smartcraft.bridge.BridgeServer;
import de.hannes.smartcraft.debug.ChatMirror;
import de.hannes.smartcraft.debug.DebugLog;
import de.hannes.smartcraft.perception.DeathRecord;
import de.hannes.smartcraft.perception.EventBus;
import de.hannes.smartcraft.perception.Perception;
import de.hannes.smartcraft.perception.Snapshot;
import de.hannes.smartcraft.protocol.Priority;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * {@code /aiplayer join [name] | leave | status | info | look | events on|off}, die Steuerung der KI
 * ({@code goal <text> | pause | resume | stop | tts on|off | humor <stufe> | talkmode <stufe>}),
 * {@code /aiplayer dimensionfollow on|off}, {@code /aiplayer gamemode <modus>}, {@code /aiplayer streamer [off]}, {@code /aiplayer locate on|off}, {@code /aiplayer stats on|off}, {@code /aiplayer xpbar on|off}, {@code /aiplayer kit nether|end} sowie Testhilfen unter {@code /aiplayer debug}.
 */
public final class BotCommand {
	private static final Predicate<CommandSourceStack> IS_OPERATOR = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private final BotManager bots;
	private final Perception perception;
	private final DebugLog debugLog;
	private final ChatMirror chatMirror;
	private final EventBus events;
	private final BridgeServer bridge;
	private final ActionRunner runner;

	public BotCommand(BotManager bots, Perception perception, DebugLog debugLog, ChatMirror chatMirror,
			EventBus events, BridgeServer bridge, ActionRunner runner) {
		this.events = events;
		this.bridge = bridge;
		this.runner = runner;
		this.chatMirror = chatMirror;
		this.bots = bots;
		this.perception = perception;
		this.debugLog = debugLog;
		this.follow = new DimensionFollow(events, runner);
	}

	private final DimensionFollow follow;

	private final Nameplate nameplate = new Nameplate();

	public void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
				server -> {
					nameplate.tick(server, bots.bot());
					UsageDisplay.tick(server, bots.bot());
					follow.tick(server, bots.bot());
				});
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
				Commands.literal("aiplayer")
						.requires(BotCommand::mayUse)
						.then(Commands.literal("join")
								.executes(ctx -> join(ctx.getSource(), BotManager.DEFAULT_NAME))
								.then(Commands.argument("name", StringArgumentType.word())
										.executes(ctx -> join(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
						.then(Commands.literal("leave").executes(ctx -> leave(ctx.getSource())))
						.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
						.then(Commands.literal("look").executes(ctx -> look(ctx.getSource())))
						.then(Commands.literal("events")
								.then(Commands.literal("on").executes(ctx -> events(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> events(ctx.getSource(), false))))
						.then(Commands.literal("goal")
								.then(Commands.argument("text", StringArgumentType.greedyString())
										.executes(ctx -> goal(ctx.getSource(), StringArgumentType.getString(ctx, "text")))))
						.then(Commands.literal("pause").executes(ctx -> control(ctx.getSource(), "pause")))
						.then(Commands.literal("resume").executes(ctx -> control(ctx.getSource(), "resume")))
						.then(Commands.literal("stop").executes(ctx -> control(ctx.getSource(), "stop")))
						.then(Commands.literal("tts")
								.then(Commands.literal("on").executes(ctx -> tts(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> tts(ctx.getSource(), false))))
						.then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
						.then(Commands.literal("apikey")
								.then(Commands.argument("key", StringArgumentType.greedyString())
										.executes(ctx -> apiKey(ctx.getSource(), StringArgumentType.getString(ctx, "key")))))
						.then(levels("humor", AiSettings.Humor.values(), (source, level) -> humor(source, AiSettings.Humor.valueOf(level))))
						.then(levels("talkmode", AiSettings.Talk.values(), (source, level) -> talkmode(source, AiSettings.Talk.valueOf(level))))
						.then(Commands.literal("dimensionfollow")
								.then(Commands.literal("on").executes(ctx -> dimensionFollow(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> dimensionFollow(ctx.getSource(), false))))
						.then(levels("gamemode", GameType.values(), (source, mode) -> gamemode(source, GameType.valueOf(mode))))
						.then(Commands.literal("streamer")
								.executes(ctx -> streamer(ctx.getSource(), true))
								.then(Commands.literal("on").executes(ctx -> streamer(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> streamer(ctx.getSource(), false))))
						.then(Commands.literal("locate")
								.then(Commands.literal("on").executes(ctx -> locate(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> locate(ctx.getSource(), false))))
						.then(Commands.literal("xpbar")
								.then(Commands.literal("on").executes(ctx -> xpbar(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> xpbar(ctx.getSource(), false))))
						.then(Commands.literal("inventar").executes(ctx -> inventar(ctx.getSource())))
						.then(Commands.literal("stats")
								.then(Commands.literal("on").executes(ctx -> stats(ctx.getSource(), true)))
								.then(Commands.literal("off").executes(ctx -> stats(ctx.getSource(), false))))
						.then(Commands.literal("kit")
								.then(Commands.literal("nether").executes(ctx -> kitNether(ctx.getSource())))
								.then(Commands.literal("end").executes(ctx -> kitEnd(ctx.getSource()))))
						.then(Commands.literal("debug")
								.then(Commands.literal("punch").executes(ctx -> debugPunch(ctx.getSource())))
								.then(Commands.literal("break").executes(ctx -> debugBreak(ctx.getSource())))
								.then(Commands.literal("bedprobe")
										.then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
												.then(Commands.argument("interval", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200))
														.then(Commands.argument("below", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-2, 8))
																.executes(ctx -> {
																	BedProbe.start(ctx.getSource().getServer(), bots.bot() == null ? "" : bots.bot().getGameProfile().name(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"),
																			com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "interval"),
																			com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "below"));
																	return say(ctx.getSource(), "bedprobe gestartet");
																}))))))));
	}

	/** Ein Unterbefehl mit je einem Wort pro Stufe (Kleinschreibung), damit die Tab-Vervollständigung sie zeigt. */
	private static <E extends Enum<E>> com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> levels(
			String name, E[] values, java.util.function.BiFunction<CommandSourceStack, String, Integer> run) {
		var node = Commands.literal(name);
		for (E value : values) {
			node.then(Commands.literal(value.name().toLowerCase(Locale.ROOT))
					.executes(ctx -> run.apply(ctx.getSource(), value.name())));
		}
		return node;
	}

	/** Schickt die Einstellungen an den Begleitprozess (wenn verbunden); er setzt sie sofort um. */
	private void pushSettings(CommandSourceStack source) {
		if (!bridge.isClientConnected()) {
			return;
		}
		JsonObject data = AiSettings.toJson();
		data.addProperty("action", "settings");
		data.addProperty("by", source.getTextName());
		events.emit("control_requested", source.getServer().overworld().getGameTime(), Priority.NORMAL, data);
	}

	private String companionNote() {
		return bridge.isClientConnected() ? "" : " (Begleitprozess nicht verbunden: gilt ab seinem Start)";
	}

	/**
	 * Legt den Anthropic-API-Schlüssel fest. Der Mod ruft selbst keine API auf: Er reicht den Schlüssel an den
	 * Begleitprozess weiter (sofort oder beim nächsten Verbinden), der ihn in {@code companion/api_key.txt} speichert.
	 */
	private int apiKey(CommandSourceStack source, String raw) {
		String key = raw.trim();
		if (key.length() < 20 || key.contains(" ")) {
			source.sendFailure(Component.literal("Das sieht nicht wie ein API-Schlüssel aus (sk-ant-…)."));
			return 0;
		}
		String hint = "…" + key.substring(key.length() - 4);
		if (bridge.isClientConnected()) {
			JsonObject data = new JsonObject();
			data.addProperty("action", "apikey");
			data.addProperty("by", source.getTextName());
			data.addProperty("api_key", key);
			events.emit("control_requested", source.getServer().overworld().getGameTime(), Priority.NORMAL, data);
			return say(source, "API-Schlüssel " + hint + " an den Begleitprozess übergeben.");
		}
		AiSettings.pendingApiKey = key;
		return say(source, "API-Schlüssel " + hint + " gemerkt; der Begleitprozess übernimmt ihn beim Verbinden.");
	}

	private int tts(CommandSourceStack source, boolean on) {
		AiSettings.tts = on;
		pushSettings(source);
		return say(source, (on ? "Sprachausgabe an." : "Sprachausgabe aus.") + companionNote());
	}

	private int humor(CommandSourceStack source, AiSettings.Humor level) {
		AiSettings.humor = level;
		pushSettings(source);
		return say(source, "Humor: " + level.name() + companionNote());
	}

	private int talkmode(CommandSourceStack source, AiSettings.Talk level) {
		AiSettings.talk = level;
		pushSettings(source);
		return say(source, "Talkmode: " + level.name() + companionNote());
	}

	private int dimensionFollow(CommandSourceStack source, boolean on) {
		ServerPlayer player = source.getPlayer();
		if (on && (player == null || player instanceof FakePlayer || player == bots.bot())) {
			source.sendFailure(Component.literal("Dimension Follow braucht einen Spieler, dem der Bot folgt: im Spiel eingeben, nicht in der Konsole."));
			return 0;
		}
		AiSettings.dimensionFollow = on;
		if (on) {
			AiSettings.followTarget = player.getUUID();
		}
		pushSettings(source);
		return say(source, on ? "Dimension Follow an: Der Bot folgt " + player.getGameProfile().name() + " in andere Dimensionen."
				: "Dimension Follow aus.");
	}

	/** Spielmodus dessen, der den Befehl eingibt (der Bot behält seinen). */
	private int gamemode(CommandSourceStack source, GameType mode) {
		ServerPlayer player = source.getPlayer();
		if (player == null || player == bots.bot()) {
			source.sendFailure(Component.literal("gamemode setzt deinen eigenen Spielmodus und geht nur im Spiel, nicht in der Konsole."));
			return 0;
		}
		if (!player.setGameMode(mode) && player.gameMode() != mode) {
			source.sendFailure(Component.literal("Spielmodus konnte nicht gesetzt werden."));
			return 0;
		}
		return say(source, "Dein Spielmodus: " + mode.getName());
	}

	/** Streamer-Preset; „off“ schaltet nur den Streamer-Modus aus und lässt die Einzelwerte, wie sie sind. */
	private int streamer(CommandSourceStack source, boolean on) {
		if (!on) {
			AiSettings.streamer = false;
			pushSettings(source);
			return say(source, "Streamer-Modus aus. Die Einzeleinstellungen bleiben, wie sie sind (siehe /aiplayer info).");
		}
		ServerPlayer player = source.getPlayer();
		if (player == null || player == bots.bot()) {
			source.sendFailure(Component.literal("Der Streamer-Modus braucht einen Spieler (Zuschauermodus, Dimension Follow): im Spiel eingeben."));
			return 0;
		}
		AiSettings.tts = true;
		AiSettings.humor = AiSettings.Humor.CRAZY;
		AiSettings.talk = AiSettings.Talk.HARD;
		AiSettings.dimensionFollow = true;
		AiSettings.followTarget = player.getUUID();
		AiSettings.streamer = true;
		CommandDispatcher.locateEnabled = true;
		chatMirror.setEnabled(false);
		boolean spectator = player.setGameMode(GameType.SPECTATOR) || player.gameMode() == GameType.SPECTATOR;
		pushSettings(source);
		say(source, "Streamer-Modus an: TTS an, Humor CRAZY, Talkmode HARD, Locate an, Events aus, Dimension Follow an"
				+ (spectator ? ", du bist Zuschauer." : "."));
		if (!spectator) {
			source.sendFailure(Component.literal("Zuschauermodus konnte nicht gesetzt werden."));
		}
		if (!bridge.isClientConnected()) {
			source.sendFailure(Component.literal("Kein Begleitprozess verbunden: TTS, Humor und Talkmode gelten ab seinem Start."));
		}
		return 1;
	}

	private int info(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		ServerPlayer bot = bots.bot();
		String target = "-";
		if (AiSettings.followTarget != null) {
			ServerPlayer followed = source.getServer().getPlayerList().getPlayer(AiSettings.followTarget);
			target = followed != null ? followed.getGameProfile().name() : "nicht online";
		}
		say(source, "--- KI-Spieler ---");
		say(source, "Bot: " + (bot == null ? "nicht im Spiel" : bot.getGameProfile().name() + " in "
				+ bot.level().dimension().identifier().getPath() + ", Modus " + bot.gameMode().getName()));
		say(source, "Begleitprozess: " + (bridge.isClientConnected() ? "verbunden" : "nicht verbunden"));
		say(source, "TTS: " + onOff(AiSettings.tts));
		say(source, "Locate: " + onOff(CommandDispatcher.locateEnabled));
		say(source, "Events (Chat-Anzeige): " + onOff(chatMirror.enabled()));
		say(source, "Humor: " + AiSettings.humor.name());
		say(source, "Talkmode: " + AiSettings.talk.name());
		say(source, "Gamemode (du): " + (player != null ? player.gameMode().getName() : "-"));
		say(source, "Dimension Follow: " + onOff(AiSettings.dimensionFollow) + (AiSettings.dimensionFollow ? " (folgt " + target + ")" : ""));
		say(source, "Streamer Mode: " + onOff(AiSettings.streamer));
		say(source, "XP-Leiste (Tokens/$): " + onOff(UsageDisplay.enabled()) + ", Werte unter dem Namen: " + onOff(nameplate.enabled()));
		return 1;
	}

	private static String onOff(boolean on) {
		return on ? "ON" : "OFF";
	}

	/** Operatoren, die Konsole und der Host einer Einzelspieler-/LAN-Welt (auch ohne aktivierte Cheats). */
	private static boolean mayUse(CommandSourceStack source) {
		if (IS_OPERATOR.test(source)) {
			return true;
		}
		ServerPlayer player = source.getPlayer();
		return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
	}

	private int join(CommandSourceStack source, String name) {
		BotManager.Result result = bots.requestJoin(source.getServer(), name, source::sendFailure);
		if (result == BotManager.Result.OK) {
			UsageDisplay.set(source.getServer(), true);
			return say(source, name + " tritt bei …");
		}
		return fail(source, result);
	}

	private int leave(CommandSourceStack source) {
		BotManager.Result result = bots.leave(source.getServer());
		if (result == BotManager.Result.OK) {
			return say(source, "KI-Spieler hat das Spiel verlassen.");
		}
		return fail(source, result);
	}

	private int status(CommandSourceStack source) {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			say(source, bots.isJoining() ? "KI-Spieler tritt gerade bei." : "Kein KI-Spieler im Spiel.");
			return 0;
		}

		say(source, String.format(Locale.ROOT, "%s: %s bei %.1f %.1f %.1f, Modus %s, Leben %.1f/%.0f, Hunger %d/20%s",
				bot.getGameProfile().name(),
				bot.level().dimension().identifier(),
				bot.getX(), bot.getY(), bot.getZ(),
				bot.gameMode().getName(),
				bot.getHealth(), bot.getMaxHealth(),
				bot.getFoodData().getFoodLevel(),
				bot.isDeadOrDying() ? ", tot" : ""));

		DeathRecord death = bots.lastDeath();
		if (death != null) {
			say(source, String.format(Locale.ROOT, "Letzter Tod: %s (%s) bei %d %d %d in %s, %d Item-Arten verloren",
					death.message(), death.cause(),
					death.pos().getX(), death.pos().getY(), death.pos().getZ(),
					death.dimension(), death.lostItems().size()));
		}
		say(source, String.format(Locale.ROOT, "Wahrnehmung: Ø %.1f µs pro Tick, Spitze %.0f µs (ein Tick hat 50.000 µs)",
				perception.averageMicros(), perception.maxMicros()));
		return 1;
	}

	private int look(CommandSourceStack source) {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			return fail(source, BotManager.Result.NOT_JOINED);
		}

		long start = System.nanoTime();
		JsonObject snapshot = Snapshot.of(bot);
		long micros = (System.nanoTime() - start) / 1000;

		String text = snapshot.toString();
		debugLog.write("perception", bot.level().getGameTime(), snapshot);
		say(source, text);
		return say(source, String.format(Locale.ROOT, "Snapshot: %d Zeichen, etwa %d Tokens (Schätzung: Zeichen ÷ 3,5), erstellt in %d µs",
				text.length(), Snapshot.estimateTokens(text), micros));
	}

	private int events(CommandSourceStack source, boolean show) {
		chatMirror.setEnabled(show);
		return say(source, show ? "Events werden im Chat angezeigt." : "Events werden nicht mehr im Chat angezeigt.");
	}

	/** Leben und Hunger des Bots unter seinem Namen ein- oder ausblenden; gilt bis zum Neustart des Servers. */
	/** Öffnet das Inventar des Bots als Doppelkiste (live, Gegenstände tauschen geht). */
	private int inventar(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			return say(source, "Kein KI-Spieler im Spiel.");
		}
		BotInventoryView.open(source.getPlayerOrException(), bot);
		return 1;
	}

	private int stats(CommandSourceStack source, boolean on) {
		nameplate.set(source.getServer(), on);
		return say(source, on ? "stats an: Unter dem Namen des Bots stehen Herzen und Hunger."
				: "stats aus: Anzeige unter dem Namen entfernt.");
	}

	/** Verbrauch der KI (Tokens, $) in der XP-Leiste ein- oder ausblenden; Standard an bei jedem Join. */
	private int xpbar(CommandSourceStack source, boolean on) {
		UsageDisplay.set(source.getServer(), on);
		return say(source, on ? "xpbar an: XP-Leiste zeigt Cent, darüber Tokens und Dollar der KI."
				: "xpbar aus: XP-Leiste zeigt wieder deine echten Erfahrungspunkte.");
	}

	/** Schaltet den Befehl {@code locate} frei (Strukturen über die Weltdaten finden); bis zum Neustart des Servers. */
	private int locate(CommandSourceStack source, boolean on) {
		CommandDispatcher.locateEnabled = on;
		return say(source, on ? "locate an: Die KI kann Festungen und andere Strukturen direkt orten."
				: "locate aus: Die KI muss Strukturen selbst suchen.");
	}

	/** Gibt der KI im Begleitprozess ein Ziel. Der Mod reicht es nur weiter. */
	private int goal(CommandSourceStack source, String text) {
		if (!agentReachable(source)) {
			return 0;
		}
		JsonObject data = new JsonObject();
		data.addProperty("text", text.strip());
		data.addProperty("by", source.getTextName());
		events.emit("goal_requested", source.getServer().overworld().getGameTime(), Priority.NORMAL, data);
		return say(source, "Ziel an die KI geschickt: " + text.strip());
	}

	/** pause und stop halten zusätzlich sofort die laufende Aktion an, auch wenn die KI gerade nicht antwortet. */
	private int control(CommandSourceStack source, String action) {
		if (!agentReachable(source)) {
			return 0;
		}
		long tick = source.getServer().overworld().getGameTime();
		JsonObject data = new JsonObject();
		data.addProperty("action", action);
		data.addProperty("by", source.getTextName());
		// Erst das Event, dann anhalten: So weiß die KI schon Bescheid, wenn das abgebrochene Ergebnis eintrifft.
		events.emit("control_requested", tick, Priority.NORMAL, data);
		ServerPlayer bot = bots.bot();
		if ((action.equals("pause") || action.equals("stop")) && bot != null) {
			runner.stop(bot, tick);
		}
		return say(source, switch (action) {
			case "pause" -> "KI pausiert. Weiter mit /aiplayer resume.";
			case "resume" -> "KI macht weiter.";
			case "tts_on" -> "Sprachausgabe an.";
			case "tts_off" -> "Sprachausgabe aus.";
			default -> "KI gestoppt, das Ziel ist beendet.";
		});
	}

	private boolean agentReachable(CommandSourceStack source) {
		if (bots.bot() == null) {
			fail(source, BotManager.Result.NOT_JOINED);
			return false;
		}
		if (!bridge.isClientConnected()) {
			source.sendFailure(Component.literal("Kein Begleitprozess verbunden. Starte: python companion/agent.py"));
			return false;
		}
		return true;
	}

	/** Testhilfe: Ein unsichtbarer Hilfsspieler östlich des Bots schlägt zu, über denselben Code wie ein echter Spielerschlag. */
	/** Testhilfe: alles für den Nether, Obsidian für das Portal eingeschlossen. */
	private int kitNether(CommandSourceStack source) {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			return fail(source, BotManager.Result.NOT_JOINED);
		}
		int stacks = Kits.giveNether(bot);
		return say(source, "Nether-Ausrüstung gegeben (" + stacks + " Stapel): Diamantrüstung mit Goldstiefeln und Schild "
				+ "angelegt, Werkzeug, Bogen, Essen, 10 Obsidian, Feuerzeug, Bausteine. Volles Inventar liegt vor seinen Füßen.");
	}

	/** Testhilfe: alles für das End (18 Enderaugen, 16 Betten, Bogen, Pfeile, Bausteine, Essen). */
	private int kitEnd(CommandSourceStack source) {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			return fail(source, BotManager.Result.NOT_JOINED);
		}
		int stacks = Kits.giveEnd(bot);
		return say(source, "End-Ausrüstung gegeben (" + stacks + " Stapel): Diamantrüstung mit Goldstiefeln und Schild "
				+ "angelegt, Schwert, 2 Spitzhacken, Bogen, 128 Pfeile, 18 Enderaugen, 16 Betten, Essen, Goldäpfel, Bausteine.");
	}

	private int debugPunch(CommandSourceStack source) {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			return fail(source, BotManager.Result.NOT_JOINED);
		}
		FakePlayer attacker = FakePlayer.get(bot.level());
		attacker.snapTo(bot.getX() + 1, bot.getY(), bot.getZ(), 90, 0);
		attacker.attack(bot);
		return say(source, "Schlag von Osten ausgeführt; der Bot sollte nach Westen (-x) zurückweichen.");
	}

	/** Testhilfe: Der Bot baut den Block unter seinen Füßen ab. */
	private int debugBreak(CommandSourceStack source) {
		ServerPlayer bot = bots.bot();
		if (bot == null) {
			return fail(source, BotManager.Result.NOT_JOINED);
		}
		boolean broken = bot.gameMode.destroyBlock(bot.blockPosition().below());
		return say(source, broken ? "Block unter dem Bot abgebaut." : "Der Block unter dem Bot ließ sich nicht abbauen.");
	}

	private static int say(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int fail(CommandSourceStack source, BotManager.Result result) {
		source.sendFailure(Component.literal(switch (result) {
			case ALREADY_JOINED -> "Der KI-Spieler ist schon im Spiel.";
			case JOIN_IN_PROGRESS -> "Der KI-Spieler tritt gerade bei.";
			case INVALID_NAME -> "Ungültiger Spielername (höchstens 16 Zeichen, keine Leerzeichen).";
			case NAME_TAKEN -> "Ein Spieler mit diesem Namen ist schon im Spiel.";
			case SERVER_FULL -> "Der Server ist voll.";
			case NOT_JOINED -> "Kein KI-Spieler im Spiel.";
			case OK -> throw new IllegalArgumentException("OK ist kein Fehler");
		}));
		return 0;
	}
}
