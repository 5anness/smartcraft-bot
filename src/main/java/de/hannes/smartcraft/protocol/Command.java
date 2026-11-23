package de.hannes.smartcraft.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.regex.Pattern;

/**
 * Ein Befehl vom Begleitprozess. Format siehe {@code schemas/command.schema.json}.
 *
 * @param id   vom Absender vergeben; kommt in jeder Antwort als {@code cmd_id} zurück
 * @param name Name der Aktion in snake_case
 * @param args Argumente, leer wenn keine angegeben sind
 */
public record Command(String id, String name, JsonObject args) {
	private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
	private static final int MAX_ID_LENGTH = 64;

	/** Eine Zeile vom Socket war kein gültiger Befehl. {@link #getMessage()} ist der Grund für das Protokoll. */
	public static final class InvalidCommandException extends Exception {
		public InvalidCommandException(String reason) {
			super(reason);
		}
	}

	public static Command parse(String line) throws InvalidCommandException {
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(line);
		} catch (JsonParseException e) {
			throw new InvalidCommandException("invalid_json");
		}
		if (!parsed.isJsonObject()) {
			throw new InvalidCommandException("not_an_object");
		}
		JsonObject json = parsed.getAsJsonObject();

		if (!isInt(json.get("v")) || json.get("v").getAsInt() != Protocol.VERSION) {
			throw new InvalidCommandException("unsupported_version");
		}
		if (!isString(json.get("type")) || !json.get("type").getAsString().equals("command")) {
			throw new InvalidCommandException("not_a_command");
		}
		if (!isString(json.get("id")) || json.get("id").getAsString().isEmpty()
				|| json.get("id").getAsString().length() > MAX_ID_LENGTH) {
			throw new InvalidCommandException("invalid_id");
		}
		if (!isString(json.get("name")) || !NAME.matcher(json.get("name").getAsString()).matches()) {
			throw new InvalidCommandException("invalid_name");
		}
		JsonElement args = json.get("args");
		if (args != null && !args.isJsonObject()) {
			throw new InvalidCommandException("invalid_args");
		}

		return new Command(json.get("id").getAsString(), json.get("name").getAsString(),
				args != null ? args.getAsJsonObject() : new JsonObject());
	}

	private static boolean isString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}

	private static boolean isInt(JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return false;
		}
		double value = element.getAsDouble();
		return value == Math.rint(value);
	}
}
