package de.hannes.smartcraft.bridge;

import de.hannes.smartcraft.AiPlayerMod;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Lokaler Socket für den Begleitprozess: eine JSON-Nachricht pro Zeile, UTF-8.
 * Es gibt höchstens einen Begleitprozess; ein neuer verdrängt den alten.
 *
 * <p>Alle Netzwerkarbeit läuft auf eigenen Threads. Der Server-Thread berührt nur die beiden
 * Queues über {@link #send} und {@link #poll} und wartet nie auf das Netzwerk.
 */
public final class BridgeServer {
	public static final int DEFAULT_PORT = 25599;

	/** Schutz vor einem Begleitprozess, der nicht mehr liest: darüber hinaus werden Nachrichten verworfen. */
	private static final int MAX_QUEUED_MESSAGES = 10_000;
	private static final int MAX_LINE_LENGTH = 64 * 1024;

	private final int port;
	private final Queue<String> inbound = new ConcurrentLinkedQueue<>();
	private volatile ServerSocket serverSocket;
	private volatile Client client;
	private volatile boolean clientJustConnected;

	public BridgeServer(int port) {
		this.port = port;
	}

	/** Port aus der Umgebungsvariable {@code AIPLAYER_PORT}, sonst {@link #DEFAULT_PORT}. */
	public static int configuredPort() {
		String value = System.getenv("AIPLAYER_PORT");
		if (value == null || value.isBlank()) {
			return DEFAULT_PORT;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			AiPlayerMod.LOGGER.warn("AIPLAYER_PORT={} ist keine Zahl, nehme {}", value, DEFAULT_PORT);
			return DEFAULT_PORT;
		}
	}

	public int port() {
		return port;
	}

	public boolean isClientConnected() {
		return client != null;
	}

	/** Öffnet den Socket. Scheitert das (z. B. Port belegt), läuft der Mod ohne Brücke weiter. */
	public void start() {
		try {
			// Ausdrücklich 127.0.0.1: von außerhalb dieses Rechners nicht erreichbar.
			serverSocket = new ServerSocket(port, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
		} catch (IOException e) {
			AiPlayerMod.LOGGER.error("Brücke: Port {} lässt sich nicht öffnen, der Begleitprozess kann sich nicht verbinden", port, e);
			return;
		}
		Thread thread = new Thread(this::acceptLoop, "aiplayer-bridge-accept");
		thread.setDaemon(true);
		thread.start();
		AiPlayerMod.LOGGER.info("Brücke: lausche auf 127.0.0.1:{}", port);
	}

	public void stop() {
		ServerSocket socket = serverSocket;
		serverSocket = null;
		closeQuietly(socket);
		Client current = client;
		client = null;
		if (current != null) {
			current.close();
		}
		inbound.clear();
	}

	/** Reiht eine Nachricht für den Begleitprozess ein. Ohne Verbindung wird sie verworfen. */
	public void send(String line) {
		Client current = client;
		if (current != null) {
			current.outbound.offer(line);
		}
	}

	/** Nächste empfangene Zeile oder {@code null}. */
	public String poll() {
		return inbound.poll();
	}

	/** Einmalig {@code true}, nachdem sich ein Begleitprozess verbunden hat. */
	public boolean consumeClientJustConnected() {
		if (!clientJustConnected) {
			return false;
		}
		clientJustConnected = false;
		return true;
	}

	private void acceptLoop() {
		ServerSocket socket = serverSocket;
		while (socket != null && !socket.isClosed()) {
			try {
				Client next = new Client(socket.accept());
				Client previous = client;
				client = next;
				if (previous != null) {
					previous.close();
				}
				next.start();
				clientJustConnected = true;
				AiPlayerMod.LOGGER.info("Brücke: Begleitprozess verbunden");
			} catch (IOException e) {
				if (serverSocket != null) {
					AiPlayerMod.LOGGER.warn("Brücke: Verbindungsannahme fehlgeschlagen", e);
				}
			}
		}
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception ignored) {
			// Beim Aufräumen gibt es nichts mehr zu retten.
		}
	}

	private final class Client {
		final Socket socket;
		final BlockingQueue<String> outbound = new LinkedBlockingQueue<>(MAX_QUEUED_MESSAGES);

		Client(Socket socket) {
			this.socket = socket;
		}

		void start() {
			Thread reader = new Thread(this::readLoop, "aiplayer-bridge-read");
			reader.setDaemon(true);
			reader.start();
			Thread writer = new Thread(this::writeLoop, "aiplayer-bridge-write");
			writer.setDaemon(true);
			writer.start();
		}

		void close() {
			closeQuietly(socket);
		}

		private void readLoop() {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					if (!line.isBlank() && line.length() <= MAX_LINE_LENGTH) {
						inbound.add(line);
					}
				}
			} catch (IOException ignored) {
				// Verbindung beendet; unten wird aufgeräumt.
			}
			disconnected();
		}

		private void writeLoop() {
			try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
				while (!socket.isClosed()) {
					out.write(outbound.take());
					out.write('\n');
					// Sofort senden, solange nichts Weiteres wartet: Events sollen ohne Verzögerung ankommen.
					if (outbound.isEmpty()) {
						out.flush();
					}
				}
			} catch (IOException ignored) {
				// Verbindung beendet.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			disconnected();
		}

		private void disconnected() {
			close();
			if (client == this) {
				client = null;
				AiPlayerMod.LOGGER.info("Brücke: Begleitprozess getrennt");
			}
		}
	}
}
