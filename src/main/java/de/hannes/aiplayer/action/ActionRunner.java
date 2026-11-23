package de.hannes.aiplayer.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.hannes.aiplayer.action.Action.Outcome;
import de.hannes.aiplayer.perception.EventBus;
import de.hannes.aiplayer.perception.GameJson;
import de.hannes.aiplayer.protocol.Command;
import de.hannes.aiplayer.protocol.Event;
import de.hannes.aiplayer.protocol.Event.Status;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * Führt höchstens eine Aktion gleichzeitig aus und sorgt für die gemeinsamen Regeln:
 * sofort {@code _started}, am Ende genau ein {@code _result}, Timeout, Abbruch durch einen
 * neuen Befehl, durch {@code stop} oder durch Gefahr. Läuft auf dem Server-Thread.
 */
public final class ActionRunner {
	private final EventBus bus;

	private Action current;
	private Command command;
	private long startedTick;

	// Gefahr wird im Event-Listener nur vorgemerkt und im nächsten Tick behandelt: Events
	// entstehen mitten im Tick des Bots, dort darf die Aktion nicht umgebaut werden.
	private String pendingInterrupt;
	private JsonObject pendingTrigger;
	private Set<String> knownDangers = Set.of();
	/** Im Nether sind Lava und Feuer überall: Dort zählt nur, was höchstens so weit weg ist. */
	private static final int NETHER_DANGER_DISTANCE = 1;
	private boolean inNether;

	/** Ob gerade eine Aktion läuft, bei der Notwehr nicht erlaubt ist. */
	public boolean busyWithoutSelfDefense() {
		return isBusy() && (current == null || !current.allowsSelfDefense());
	}

	public ActionRunner(EventBus bus) {
		this.bus = bus;
		bus.subscribe(this::onEvent);
	}

	public boolean isBusy() {
		return current != null;
	}

	/** Startet eine Aktion. Eine laufende wird vorher mit {@code cancelled: superseded} beendet. */
	public void start(Command newCommand, Action action, ServerPlayer bot, long tick) {
		cancel(bot, tick, "superseded", null);
		current = action;
		command = newCommand;
		startedTick = tick;
		pendingInterrupt = null;
		bus.emitStarted(newCommand, tick, new JsonObject());
	}

	/** @return Name des abgebrochenen Befehls oder {@code null}, wenn nichts lief */
	public String stop(ServerPlayer bot, long tick) {
		String name = command != null ? command.name() : null;
		cancel(bot, tick, "stopped", null);
		return name;
	}

	/** Der Bot ist nicht mehr im Spiel. */
	public void botGone(long tick) {
		if (current != null) {
			finish(null, tick, new Outcome(Status.FAILED, "no_bot", new JsonObject()));
		}
	}

	/** Einmal pro Server-Tick, bevor der Bot tickt. */
	public void tick(ServerPlayer bot, long tick) {
		inNether = bot.level().dimension() == Level.NETHER;
		if (current == null) {
			return;
		}
		if (bot.isDeadOrDying()) {
			finish(bot, tick, new Outcome(Status.FAILED, "bot_died", current.progress(bot)));
			return;
		}
		if (pendingInterrupt != null) {
			JsonObject data = current.progress(bot);
			data.add("trigger", pendingTrigger);
			finish(bot, tick, new Outcome(Status.CANCELLED, pendingInterrupt, data));
			return;
		}
		if (tick - startedTick > current.timeoutTicks()) {
			finish(bot, tick, new Outcome(Status.FAILED, "timeout", current.progress(bot)));
			return;
		}

		Outcome outcome = current.tick(bot);
		if (outcome != null) {
			finish(bot, tick, outcome);
		}
	}

	private void cancel(ServerPlayer bot, long tick, String reason, JsonObject trigger) {
		if (current == null) {
			return;
		}
		JsonObject data = bot != null ? current.progress(bot) : new JsonObject();
		if (trigger != null) {
			data.add("trigger", trigger);
		}
		finish(bot, tick, new Outcome(Status.CANCELLED, reason, data));
	}

	private void finish(ServerPlayer bot, long tick, Outcome outcome) {
		Action finished = current;
		Command finishedCommand = command;
		current = null;
		command = null;
		pendingInterrupt = null;
		pendingTrigger = null;

		JsonObject data = outcome.data();
		if (bot != null) {
			finished.release(bot);
			// Jedes Ergebnis nennt den bestätigten Standort danach.
			data.add("pos", GameJson.pos(bot.blockPosition()));
		}
		data.addProperty("ticks", tick - startedTick);
		bus.emitResult(finishedCommand, tick, outcome.status(), outcome.reason(), data);
	}

	private void onEvent(Event event) {
		// Auch ohne laufende Aktion mitführen, welche Gefahren schon gemeldet sind.
		boolean newDanger = event.name().equals("danger") && rememberDangers(event.data());
		if (current == null || pendingInterrupt != null) {
			return;
		}
		String reason = switch (event.name()) {
			case "damage_taken" -> current.cancelOnDamage() && !isLingering(event.data()) ? "damage" : null;
			case "danger" -> newDanger && (inNether ? current.cancelOnDangerInNether() : current.cancelOnDanger()) ? "danger" : null;
			default -> null;
		};
		if (reason != null) {
			pendingInterrupt = reason;
			pendingTrigger = new JsonObject();
			pendingTrigger.addProperty("event", event.name());
			pendingTrigger.add("data", event.data());
		}
	}

	/**
	 * Ertrinken, Verhungern und Magma unter den Füßen treffen jede Sekunde erneut und hören nur auf, wenn der
	 * Bot etwas tut. Würden sie abbrechen, käme er nie aus dem Wasser oder vom Magma herunter (im KI-Lauf
	 * verbrannte er so). Brennen ebenso: Im Drachenlauf stand er 2 Minuten brennend, weil jeder Treffer
	 * das rettende goto abbrach. Wither und Gift ebenso: In Hannes' Lauf (28.10.) brach der Wither-Effekt jeden goto
	 * ab, und der Bot wartete 40 s. Das Event geht trotzdem hinaus.
	 */
	private static String attacker(JsonObject damage) {
		return damage.has("attacker") ? damage.get("attacker").getAsString() : null;
	}

	private static boolean isLingering(JsonObject damage) {
		String source = damage.get("source").getAsString();
		return source.equals("minecraft:drown") || source.equals("minecraft:starve")
				|| source.equals("minecraft:hot_floor") || source.equals("minecraft:on_fire")
				|| source.equals("minecraft:in_fire") || source.equals("minecraft:lava")
				|| source.equals("minecraft:wither") || source.equals("minecraft:magic")
				// Ersticken: Läufe 31 und 32 (04.11.2026) – Kies rutschte nach, jeder Erstickungstreffer brach das rettende
				// mine auf den Kies ab, beide erstickten.
				|| source.equals("minecraft:in_wall")
				// Drachenatem: Aus der Wolke holt ihn der Reflex; ein abgebrochenes goto ließ ihn darin stehen (End-Lauf 10:
				// der Weg zum Standplatz brach zweimal ab, danach baute er sich 113 Blöcke hoch).
				|| source.equals("minecraft:indirect_magic") && "minecraft:ender_dragon".equals(attacker(damage));
	}

	/**
	 * Lava und Feuer unterbrechen, aber nur, wenn sie neu auftauchen. Wer danach bewusst
	 * weiterläuft, wird nicht bei jedem Schritt an der Lava entlang erneut gestoppt.
	 * Ein Abgrund neben dem Bot unterbricht nie: die Wegfindung meidet ihn ohnehin.
	 *
	 * @return ob eine Gefahr dabei ist, die im vorigen danger-Event noch nicht gemeldet war
	 */
	private boolean rememberDangers(JsonObject data) {
		Set<String> now = new HashSet<>();
		for (JsonElement danger : data.getAsJsonArray("dangers")) {
			String kind = danger.getAsJsonObject().get("kind").getAsString();
			int dist = danger.getAsJsonObject().has("dist") ? danger.getAsJsonObject().get("dist").getAsInt() : 0;
			if ((kind.equals("lava") || kind.equals("fire")) && (!inNether || dist <= NETHER_DANGER_DISTANCE)) {
				now.add(kind);
			}
		}
		boolean appeared = !knownDangers.containsAll(now);
		knownDangers = now;
		return appeared;
	}
}
