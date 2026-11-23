package de.hannes.smartcraft.perception;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.SmartCraftMod;
import de.hannes.smartcraft.debug.DebugLog;
import de.hannes.smartcraft.protocol.Command;
import de.hannes.smartcraft.protocol.Event;
import de.hannes.smartcraft.protocol.Priority;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Zentrale Stelle, durch die jedes Event läuft: vergibt die laufende Nummer,
 * schreibt ins Debug-Log und reicht an die Abnehmer weiter (Brücke, Chat-Anzeige).
 */
public final class EventBus {
	private final AtomicLong nextSeq = new AtomicLong(1);
	private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
	private final DebugLog debugLog;

	public EventBus(DebugLog debugLog) {
		this.debugLog = debugLog;
	}

	public void subscribe(Consumer<Event> listener) {
		listeners.add(listener);
	}

	/** Ein Fakt aus dem Spiel. */
	public void emit(String name, long tick, Priority prio, JsonObject data) {
		publish("perception", new Event(name, nextSeq.getAndIncrement(), tick, prio, data));
	}

	/** Sofortige Bestätigung, dass eine Aktion mit Dauer begonnen hat. */
	public void emitStarted(Command command, long tick, JsonObject data) {
		publish("result", new Event(command.name() + "_started", nextSeq.getAndIncrement(), tick,
				Priority.NORMAL, data, command.id(), null, null));
	}

	/**
	 * Das eine Ergebnis eines Befehls.
	 *
	 * @param reason Pflicht, wenn der Status nicht {@code OK} ist
	 */
	public void emitResult(Command command, long tick, Event.Status status, String reason, JsonObject data) {
		publish("result", new Event(command.name() + "_result", nextSeq.getAndIncrement(), tick,
				Priority.NORMAL, data, command.id(), status, reason));
	}

	/** Kopie fürs Log ohne API-Schlüssel ({@code data.api_key}, {@code data.api_keys}); nie protokolliert. */
	private static JsonObject masked(JsonObject json) {
		if (!(json.get("data") instanceof JsonObject data)) {
			return json;
		}
		JsonObject masked = maskKeys(data);
		if (masked == data) {
			return json;
		}
		JsonObject copy = json.deepCopy();
		copy.add("data", masked);
		return copy;
	}

	/** {@code data} ohne Schlüssel (gleiches Objekt, wenn keiner darin ist); auch für die Chat-Anzeige. */
	public static JsonObject maskKeys(JsonObject data) {
		if (data == null || (!data.has("api_key") && !data.has("api_keys"))) {
			return data;
		}
		JsonObject copy = data.deepCopy();
		if (copy.has("api_key")) {
			copy.addProperty("api_key", "***");
		}
		if (copy.get("api_keys") instanceof JsonObject keys) {
			for (String name : keys.keySet()) {
				keys.addProperty(name, "***");
			}
		}
		return copy;
	}

	private void publish(String kind, Event event) {
		JsonObject json = event.toJson();
		JsonObject logged = masked(json);
		debugLog.write(kind, event.tick(), logged);
		SmartCraftMod.LOGGER.info("Event: {}", logged);

		for (Consumer<Event> listener : listeners) {
			listener.accept(event);
		}
	}
}
