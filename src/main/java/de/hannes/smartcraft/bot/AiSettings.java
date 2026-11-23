package de.hannes.smartcraft.bot;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.UUID;

/**
 * Einstellungen des KI-Spielers an einer Stelle: Der Mod ist die einzige Quelle der Wahrheit, der Begleitprozess bekommt
 * jede Änderung als {@code control_requested} mit {@code action: settings} und holt sie beim Verbinden mit dem Befehl
 * {@code settings}. Gilt bis zum Neustart des Servers. Wunsch von Hannes (28.10.2026): /aiplayer info, humor, talkmode,
 * dimensionfollow, streamer.
 */
public final class AiSettings {
	/** Humor der Chat- und Sprachausgabe; ändert den Talkmode nicht. */
	public enum Humor { LIGHT, MEDIUM, HARD, CRAZY }

	/** Wie oft der Bot von sich aus chattet; die Abstände stehen zentral im Begleitprozess (TALK_SETTINGS). */
	public enum Talk { LIGHT, MEDIUM, HARD }

	public static boolean tts;
	public static Humor humor = Humor.MEDIUM;
	public static Talk talk = Talk.LIGHT;
	public static boolean dimensionFollow;
	public static boolean streamer;
	/**
	 * API-Schlüssel aus {@code /aiplayer apikey}, der noch nicht beim Begleitprozess angekommen ist (nur im Speicher, nie
	 * gespeichert oder protokolliert). Der Mod ruft keine API auf; er reicht den Schlüssel nur weiter.
	 */
	public static volatile String pendingApiKey;
	/** Wem der Bot bei Dimensionswechseln folgt: wer dimensionfollow oder streamer eingeschaltet hat. */
	public static UUID followTarget;

	private AiSettings() {
	}

	public static JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("tts", tts);
		json.addProperty("humor", humor.name().toLowerCase(Locale.ROOT));
		json.addProperty("talkmode", talk.name().toLowerCase(Locale.ROOT));
		json.addProperty("dimension_follow", dimensionFollow);
		json.addProperty("streamer", streamer);
		return json;
	}

	/**
	 * Werte aus dem Befehl {@code settings} des Begleitprozesses (Startwerte aus seiner Umgebung, z. B. AIPLAYER_TTS).
	 *
	 * @throws IllegalArgumentException bei unbekannter Stufe
	 */
	public static void apply(JsonObject args) {
		if (args.has("tts")) {
			tts = args.get("tts").getAsBoolean();
		}
		if (args.has("humor")) {
			humor = Humor.valueOf(args.get("humor").getAsString().toUpperCase(Locale.ROOT));
		}
		if (args.has("talkmode")) {
			talk = Talk.valueOf(args.get("talkmode").getAsString().toUpperCase(Locale.ROOT));
		}
	}
}
