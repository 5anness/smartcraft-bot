package de.hannes.smartcraft;

import de.hannes.smartcraft.reflex.Reflexes;
import de.hannes.smartcraft.action.ActionRunner;
import de.hannes.smartcraft.action.CommandDispatcher;
import de.hannes.smartcraft.bot.BotCommand;
import de.hannes.smartcraft.bot.BotManager;
import de.hannes.smartcraft.bridge.BridgeServer;
import de.hannes.smartcraft.bridge.CompanionLauncher;
import de.hannes.smartcraft.debug.ChatMirror;
import de.hannes.smartcraft.debug.DebugLog;
import de.hannes.smartcraft.memory.FurnaceWatch;
import de.hannes.smartcraft.memory.Places;
import de.hannes.smartcraft.perception.EventBus;
import de.hannes.smartcraft.perception.Perception;
import de.hannes.smartcraft.perception.SessionLog;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Einstiegspunkt. Der Mod arbeitet serverseitig: im integrierten Server einer
 * Einzelspieler-/LAN-Welt oder auf einem lokalen Dedicated Server.
 */
public class SmartCraftMod implements ModInitializer {
	public static final String MOD_ID = "smartcraft";
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
		CompanionLauncher companion = new CompanionLauncher(bridge.port());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> companion.stop());
		new BotCommand(bots, perception, debugLog, chatMirror, events, bridge, runner, companion).register();

		LOGGER.info("AI Player geladen, Debug-Log: {}", debugLog.file());
	}
}
