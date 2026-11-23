package de.hannes.aiplayer.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Zuschauer-Kamera für die Servertests, nur wenn {@code AIPLAYER_SHOT_PORT} gesetzt ist.
 * Das Testskript schickt eine Zeile pro Bild (der Dateiname ohne Endung); der Client speichert
 * das aktuelle Bild nach {@code screenshots/<name>.png} und antwortet mit {@code ok <name>}.
 * Ein normal gestartetes Spiel merkt davon nichts.
 */
public final class ShotClient implements ClientModInitializer {
	private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();

	private record Request(String name, PrintWriter reply) {
	}

	@Override
	public void onInitializeClient() {
		String port = System.getenv("AIPLAYER_SHOT_PORT");
		if (port == null || port.isBlank()) {
			return;
		}
		Thread listener = new Thread(() -> listen(Integer.parseInt(port.trim())), "aiplayer-shot-listener");
		listener.setDaemon(true);
		listener.start();

		// Aus dem Blickwinkel des Bots wäre der Bot selbst nie im Bild: von hinten oben zusehen.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				client.execute(() -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK)));
		ClientTickEvents.END_CLIENT_TICK.register(this::takePending);
	}

	private void takePending(Minecraft client) {
		Request request = requests.poll();
		if (request == null) {
			return;
		}
		if (client.player == null || client.level == null) {
			request.reply().println("failed not_in_world " + request.name());
			return;
		}
		Screenshot.grab(client.gameDirectory, request.name() + ".png",
				client.gameRenderer.mainRenderTarget(), 1,
				message -> request.reply().println("ok " + request.name()));
	}

	private void listen(int port) {
		try (ServerSocket server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
			while (true) {
				Socket socket = server.accept();
				Thread connection = new Thread(() -> serve(socket), "aiplayer-shot-connection");
				connection.setDaemon(true);
				connection.start();
			}
		} catch (IOException error) {
			System.err.println("[aiplayer] Screenshot-Port " + port + " nicht verfügbar: " + error);
		}
	}

	private void serve(Socket socket) {
		try (socket;
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
			String line;
			while ((line = in.readLine()) != null) {
				String name = line.strip().replaceAll("[^A-Za-z0-9_\\-]", "_");
				if (!name.isEmpty()) {
					requests.add(new Request(name, out));
				}
			}
		} catch (IOException ignored) {
			// Testskript hat sich verabschiedet.
		}
	}
}
