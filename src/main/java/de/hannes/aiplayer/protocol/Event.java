package de.hannes.aiplayer.protocol;

import com.google.gson.JsonObject;

/**
 * Ein bestätigter Fakt aus dem Spiel, so wie er an den Begleitprozess geht.
 * Format siehe {@code schemas/event.schema.json}.
 *
 * @param name   Event-Name in snake_case, Vergangenheitsform (z. B. {@code block_broken});
 *               Antworten auf Befehle heißen {@code <befehl>_started} und {@code <befehl>_result}
 * @param seq    fortlaufende Nummer über alle Events dieser Sitzung
 * @param tick   Spiel-Tick der Welt, in der das Event entstand
 * @param data   eventspezifische Felder
 * @param cmdId  nur bei Antworten auf Befehle: die {@code id} des Befehls, sonst {@code null}
 * @param status nur bei {@code _result}: Ausgang des Befehls, sonst {@code null}
 * @param reason nur bei {@code _result} mit Status ungleich {@code OK}: Grund aus der festen Liste des Befehls
 */
public record Event(String name, long seq, long tick, Priority prio, JsonObject data,
					String cmdId, Status status, String reason) {

	/** Ausgang eines Befehls. */
	public enum Status {
		OK("ok"),
		FAILED("failed"),
		CANCELLED("cancelled");

		private final String wireName;

		Status(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}
	}

	/** Ein Event, das nicht zu einem Befehl gehört. */
	public Event(String name, long seq, long tick, Priority prio, JsonObject data) {
		this(name, seq, tick, prio, data, null, null, null);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("v", Protocol.VERSION);
		json.addProperty("type", "event");
		json.addProperty("name", name);
		json.addProperty("seq", seq);
		json.addProperty("tick", tick);
		json.addProperty("prio", prio.wireName());
		if (cmdId != null) {
			json.addProperty("cmd_id", cmdId);
		}
		if (status != null) {
			json.addProperty("status", status.wireName());
		}
		if (reason != null) {
			json.addProperty("reason", reason);
		}
		json.add("data", data);
		return json;
	}
}
