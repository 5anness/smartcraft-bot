package de.hannes.smartcraft.bridge;

import de.hannes.smartcraft.SmartCraftMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Startet den Begleitprozess (Python, {@code companion/agent.py}) als eigenen Prozess und beendet ihn wieder.
 * Der Mod ruft dabei keine API auf und kennt keinen Schlüssel (Kernprinzip 6): Er startet nur ein Programm.
 *
 * <p>Woher der Begleitprozess kommt:
 * <ul>
 *   <li>Entwicklung ({@code gradlew runClient/runServer}): der Ordner {@code companion/} im Projekt, mit dem
 *       vorhandenen Python (Abhängigkeiten installiert man dort selbst).</li>
 *   <li>Sonst: aus der Jar nach {@code config/smartcraft/} entpackt, mit einer eigenen virtuellen Umgebung
 *       {@code config/smartcraft/venv}, in die beim ersten Start {@code anthropic} installiert wird.</li>
 * </ul>
 * Alles Langsame (Entpacken, venv, pip, Start) läuft auf einem eigenen Thread, nie auf dem Server-Thread.
 * Ausgabe: {@code logs/smartcraft/companion.log}.
 */
public final class CompanionLauncher {
	/**
	 * Was in die eigene Umgebung kommt: anthropic immer, dazu das Paket des gewählten Anbieters. Die Stimme (piper-tts)
	 * ist optional und groß.
	 */
	private static List<String> packages() {
		List<String> packages = new ArrayList<>(List.of("anthropic>=1.0"));
		switch (de.hannes.smartcraft.bot.AiSettings.provider) {
			case OPENAI -> packages.add("openai>=1.0");
			case GEMINI -> packages.add("google-genai>=1.0");
			default -> { }
		}
		return packages;
	}

	/** Python-Modul des gewählten Anbieters, das im Entwicklungs-Python vorhanden sein muss. */
	private static String providerModule() {
		return switch (de.hannes.smartcraft.bot.AiSettings.provider) {
			case OPENAI -> "openai";
			case GEMINI -> "google.genai";
			default -> "anthropic";
		};
	}
	private static final int MAX_RESTARTS = 3;

	public enum State { STOPPED, PREPARING, RUNNING, FAILED }

	private final int port;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "smartcraft-companion");
		thread.setDaemon(true);
		return thread;
	});
	private volatile State state = State.STOPPED;
	private volatile String detail = "";
	private volatile Process process;
	/** Soll laufen (für den Neustart nach einem Absturz). */
	private volatile boolean wanted;
	private int restarts;

	public CompanionLauncher(int port) {
		this.port = port;
		Runtime.getRuntime().addShutdownHook(new Thread(this::kill, "smartcraft-companion-stop"));
	}

	/** Servertests und Laufskripte starten ihren eigenen Begleitprozess: {@code SMARTCRAFT_COMPANION=off}. */
	public static boolean disabledByEnvironment() {
		return "off".equalsIgnoreCase(System.getenv("SMARTCRAFT_COMPANION"));
	}

	public State state() {
		return state;
	}

	public String detail() {
		return detail;
	}

	public boolean active() {
		return state == State.PREPARING || state == State.RUNNING;
	}

	/**
	 * Startet den Begleitprozess, wenn er nicht schon läuft. {@code report} bekommt Meldungen für den Chat; es wird auf
	 * dem Arbeits-Thread aufgerufen, der Aufrufer muss sie selbst auf den Server-Thread bringen.
	 */
	public synchronized void start(Consumer<String> report) {
		if (active()) {
			report.accept("Begleitprozess läuft schon.");
			return;
		}
		wanted = true;
		restarts = 0;
		state = State.PREPARING;
		detail = "wird vorbereitet";
		worker.execute(() -> launch(report));
	}

	public synchronized void stop() {
		wanted = false;
		kill();
		state = State.STOPPED;
		detail = "";
	}

	private void kill() {
		Process running = process;
		process = null;
		if (running == null || !running.isAlive()) {
			return;
		}
		running.descendants().forEach(ProcessHandle::destroy);
		running.destroy();
		try {
			if (!running.waitFor(3, TimeUnit.SECONDS)) {
				running.descendants().forEach(ProcessHandle::destroyForcibly);
				running.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void launch(Consumer<String> report) {
		try {
			Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
			Path logDir = gameDir.resolve("logs").resolve("smartcraft");
			Files.createDirectories(logDir);
			Path log = logDir.resolve("companion.log");

			Path home = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize().resolve("smartcraft");
			Path companion = findProjectCompanion(gameDir);
			// Entwicklung: das Python nehmen, in dem anthropic schon installiert ist (dort liegt auch piper-tts).
			List<String> python = companion != null ? findPython(true) : null;
			if (companion == null) {
				detail = "entpacke den Begleitprozess";
				extract(home);
				companion = home.resolve("companion");
			}
			if (python == null) {
				python = prepareVenv(home, log, report);
				if (python == null) {
					return;
				}
			}
			if (!wanted) {
				return;
			}
			run(python, companion, log, report);
		} catch (IOException | RuntimeException e) {
			SmartCraftMod.LOGGER.warn("Begleitprozess konnte nicht starten", e);
			fail(report, "Begleitprozess konnte nicht starten: " + e.getMessage());
		}
	}

	private void run(List<String> python, Path companion, Path log, Consumer<String> report) throws IOException {
		List<String> command = new ArrayList<>(python);
		command.add("-u");
		command.add(companion.resolve("agent.py").toString());
		ProcessBuilder builder = new ProcessBuilder(command).directory(companion.toFile())
				.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
		builder.environment().put("SMARTCRAFT_PORT", String.valueOf(port));
		builder.environment().put("PYTHONUNBUFFERED", "1");
		builder.environment().put("PYTHONIOENCODING", "utf-8");
		Files.writeString(log, System.lineSeparator() + "=== Start " + java.time.LocalDateTime.now() + " ===" + System.lineSeparator(),
				StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		Process started = builder.start();
		process = started;
		state = State.RUNNING;
		detail = "läuft (PID " + started.pid() + ")";
		report.accept("Begleitprozess gestartet. Ausgabe: logs/smartcraft/companion.log");
		started.onExit().thenAccept(exited -> {
			if (process != exited) {
				return; // gezielt beendet
			}
			process = null;
			if (!wanted) {
				state = State.STOPPED;
				return;
			}
			if (restarts < MAX_RESTARTS) {
				restarts++;
				state = State.PREPARING;
				detail = "Neustart " + restarts + " von " + MAX_RESTARTS;
				report.accept("Begleitprozess beendet (Code " + exited.exitValue() + "), starte neu (" + restarts + "/"
						+ MAX_RESTARTS + ") …");
				worker.execute(() -> {
					try {
						Thread.sleep(5000);
						if (wanted) {
							run(python, companion, log, report);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (IOException e) {
						fail(report, "Neustart gescheitert: " + e.getMessage());
					}
				});
			} else {
				fail(report, "Begleitprozess mehrfach abgestürzt (Code " + exited.exitValue()
						+ "), siehe logs/smartcraft/companion.log");
			}
		});
	}

	private void fail(Consumer<String> report, String message) {
		state = State.FAILED;
		detail = message;
		wanted = false;
		report.accept(message);
	}

	/** Im Entwicklungsordner: {@code companion/agent.py} bis zu drei Ebenen über dem Spielordner (run/, run/inst2/). */
	private static Path findProjectCompanion(Path gameDir) {
		// SMARTCRAFT_COMPANION=bundled: auch in der Entwicklung die Jar-Variante (Test für Entpacken, venv, pip).
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()
				|| "bundled".equalsIgnoreCase(System.getenv("SMARTCRAFT_COMPANION"))) {
			return null;
		}
		Path dir = gameDir.toAbsolutePath().normalize();
		for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParent()) {
			Path candidate = dir.resolve("companion");
			if (Files.isRegularFile(candidate.resolve("agent.py"))) {
				return candidate;
			}
		}
		return null;
	}

	/** Kopiert companion/ und schemas/ aus der Jar; Schlüssel, Notizen und Logs dort bleiben unberührt. */
	private static void extract(Path home) throws IOException {
		ModContainer mod = FabricLoader.getInstance().getModContainer(SmartCraftMod.MOD_ID).orElseThrow();
		for (String folder : List.of("companion", "schemas")) {
			Path source = mod.findPath(folder).orElseThrow(() -> new IOException(folder + " fehlt in der Jar"));
			try (Stream<Path> files = Files.walk(source)) {
				for (Path file : (Iterable<Path>) files::iterator) {
					if (Files.isDirectory(file)) {
						continue;
					}
					Path target = home.resolve(folder);
					for (Path part : source.relativize(file)) {
						target = target.resolve(part.toString());
					}
					Files.createDirectories(target.getParent());
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Eigene virtuelle Umgebung mit anthropic; einmalig, danach nur noch prüfen. */
	private List<String> prepareVenv(Path home, Path log, Consumer<String> report) throws IOException {
		Path venv = home.resolve("venv");
		boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
		Path venvPython = windows ? venv.resolve("Scripts").resolve("python.exe") : venv.resolve("bin").resolve("python");
		Path marker = venv.resolve(".smartcraft-installed");
		String wanted = String.join(" ", packages());
		if (Files.isRegularFile(venvPython) && Files.isRegularFile(marker)
				&& Files.readString(marker, StandardCharsets.UTF_8).trim().equals(wanted)) {
			return List.of(venvPython.toString());
		}
		List<String> python = findPython(false);
		if (python == null) {
			fail(report, "Python 3.11 oder neuer nicht gefunden. Bitte installieren (python.org) und erneut "
					+ "/smartcraft companion start.");
			return null;
		}
		if (!Files.isRegularFile(venvPython)) {
			detail = "lege die Python-Umgebung an";
			report.accept("Erster Start: lege eine Python-Umgebung an und installiere " + wanted
					+ " (kann eine Minute dauern) …");
			List<String> create = new ArrayList<>(python);
			create.addAll(List.of("-m", "venv", venv.toString()));
			if (!runQuiet(create, log)) {
				fail(report, "Python-Umgebung konnte nicht angelegt werden, siehe logs/smartcraft/companion.log");
				return null;
			}
		} else {
			report.accept("Installiere " + wanted + " für den gewählten Anbieter (kann ein bis zwei Minuten dauern) …");
		}
		detail = "installiere " + wanted;
		List<String> pip = new ArrayList<>(List.of(venvPython.toString(), "-m", "pip", "install", "--upgrade"));
		pip.addAll(packages());
		if (!runQuiet(pip, log)) {
			fail(report, "pip install " + wanted + " gescheitert, siehe logs/smartcraft/companion.log");
			return null;
		}
		Files.writeString(marker, wanted, StandardCharsets.UTF_8);
		return List.of(venvPython.toString());
	}

	private static boolean runQuiet(List<String> command, Path log) {
		try {
			Process run = new ProcessBuilder(command).redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
			return run.waitFor(10, TimeUnit.MINUTES) && run.exitValue() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Python 3.11+ suchen: {@code python}, {@code python3}, {@code py -3} (Windows-Starter). Mit {@code withAnthropic} nur
	 * eines, in dem {@code anthropic} schon installiert ist.
	 */
	private static List<String> findPython(boolean withAnthropic) {
		String check = "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)"
				+ (withAnthropic ? "; import anthropic; import " + providerModule() : "");
		for (List<String> candidate : List.of(List.of("python"), List.of("python3"), List.of("py", "-3"))) {
			List<String> command = new ArrayList<>(candidate);
			command.addAll(List.of("-c", check));
			try {
				Process probe = new ProcessBuilder(command).redirectErrorStream(true)
						.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				if (probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0) {
					return candidate;
				}
				probe.destroyForcibly();
			} catch (IOException e) {
				// nicht vorhanden, nächsten probieren
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return null;
	}
}
