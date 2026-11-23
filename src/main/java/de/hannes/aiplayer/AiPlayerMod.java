package de.hannes.aiplayer;

import de.hannes.aiplayer.reflex.Reflexes;
import de.hannes.aiplayer.action.ActionRunner;
import de.hannes.aiplayer.action.CommandDispatcher;
import de.hannes.aiplayer.bot.BotCommand;
import de.hannes.aiplayer.bot.BotManager;
import de.hannes.aiplayer.bridge.BridgeServer;
import de.hannes.aiplayer.debug.ChatMirror;
import de.hannes.aiplayer.debug.DebugLog;
import de.hannes.aiplayer.memory.FurnaceWatch;
import de.hannes.aiplayer.memory.Places;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.perception.Perception;
import de.hannes.aiplayer.perception.SessionLog;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Einstiegspunkt. Der Mod arbeitet serverseitig: im integrierten Server einer
 * Einzelspieler-/LAN-Welt oder auf einem lokalen Dedicated Server.
 */
public class AiPlayerMod implements ModInitializer {
	public static final String MOD_ID = "aiplayer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		DebugLog debugLog = DebugLog.start(FabricLoader.getInstance().getGameDir());
		EventBus events = new EventBus(debugLog);
		ChatMirror chatMirror = new ChatMirror();
		chatMirror.register();
		events.subscribe(chatMirror::show);

		// Reihenfolge zählt: Der Bot tickt zuerst, danach nimmt er wahr, was dabei herauskam.
		BotManager bots = new BotManager(debugLog, events);
		bots.register();
		Perception perception = new Perception(bots, events);
		perception.register();

		Places.register();
		FurnaceWatch.register(events);

		BridgeServer bridge = new BridgeServer(BridgeServer.configuredPort());
		ActionRunner runner = new ActionRunner(events);
		Reflexes reflexes = new Reflexes(events);
		bots.setController((bot, tick) -> {
			// Ein Reflex (Notfall-Essen) braucht die Hände: Die Aktion setzt so lange aus.
			if (!reflexes.before(bot, tick, runner.isBusy(), runner.busyWithoutSelfDefense())) {
				runner.tick(bot, tick);
			}
			reflexes.after(bot, tick);
		}, runner::botGone);
		new CommandDispatcher(bridge, bots, events, debugLog, runner).register();

		SessionLog.register(debugLog);
		new BotCommand(bots, perception, debugLog, chatMirror, events, bridge, runner).register();

		LOGGER.info("AI Player geladen, Debug-Log: {}", debugLog.file());
	}
}
