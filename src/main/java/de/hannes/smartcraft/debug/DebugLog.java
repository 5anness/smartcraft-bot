package de.hannes.smartcraft.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.hannes.smartcraft.AiPlayerMod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Debug-Log der Sitzung: eine JSON-Zeile pro Vorgang unter {@code logs/aiplayer/}.
 * Geschrieben wird auf einem eigenen Thread, damit der Spiel-Thread nie auf die Platte wartet.
 */
public final class DebugLog {
	private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
	private final Path file;

	private DebugLog(Path file) {
		this.file = file;
	}

	/** Legt die Logdatei an und startet den Schreib-Thread. */
	public static DebugLog start(Path gameDir) {
		Path dir = gameDir.resolve("logs").resolve("aiplayer");
		DebugLog log = new DebugLog(dir.resolve(LocalDateTime.now().format(FILE_NAME) + ".jsonl"));

		Thread writer = new Thread(log::writeLoop, "aiplayer-debuglog");
		writer.setDaemon(true);
		writer.start();
		return log;
	}

	public Path file() {
		return file;
	}

	/**
	 * @param kind Art des Eintrags: {@code session}, {@code perception}, {@code command_in},
	 *             {@code result}, {@code safety}
	 * @param tick Spiel-Tick oder -1, wenn es keinen gibt
	 */
	public void write(String kind, long tick, JsonElement msg) {
		JsonObject line = new JsonObject();
		line.addProperty("ts", Instant.now().toString());
		line.addProperty("tick", tick);
		line.addProperty("kind", kind);
		line.add("msg", msg);
		lines.add(line.toString());
	}

	private void writeLoop() {
		try {
			Files.createDirectories(file.getParent());
			try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				while (true) {
					out.write(lines.take());
					out.newLine();
					// Nach jeder Zeile schreiben: das Log muss auch nach einem Absturz vollständig sein.
					out.flush();
				}
			}
		} catch (IOException e) {
			AiPlayerMod.LOGGER.error("Debug-Log {} kann nicht geschrieben werden", file, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
