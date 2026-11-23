package de.hannes.aiplayer.bot;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/**
 * Verbindung ohne Gegenstelle: Der Server behandelt den Bot wie einen verbundenen Spieler.
 * Es gibt keinen Netty-Channel, deshalb ist jede Methode überschrieben, die der Server beim
 * Join und im Betrieb aufruft.
 *
 * <p>Fast alle Pakete an den Bot werden verworfen. Ausnahmen sind die Pakete, bei denen sich
 * der Server darauf verlässt, dass der Client reagiert; diese Reaktion übernimmt
 * {@link #applyClientReactions()}. Liste in ARCHITECTURE.md, „Bekannte Client-Abhängigkeiten“.
 */
public final class FakeConnection extends Connection {
	private PacketListener listener;
	private DisconnectionDetails disconnection;
	private boolean disconnectionHandled;

	private Vec3 pendingMotion;
	private int pendingTeleportId = -1;

	public FakeConnection() {
		super(PacketFlow.SERVERBOUND);
	}

	@Override
	public void send(Packet<?> packet) {
		send(packet, null, true);
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
		switch (packet) {
			// Der Server trennt erst, nachdem das Abschiedspaket "gesendet" wurde. Da hier nichts
			// gesendet wird, gilt die Verbindung mit diesem Paket als beendet.
			case ClientboundDisconnectPacket(Component reason) -> disconnect(reason);
			// Rückstoß: Der Server schickt die neue Geschwindigkeit und erwartet, dass der Client
			// sie ausführt. Bei Spielerschlägen setzt er seine eigene Kopie danach sogar zurück.
			case ClientboundSetEntityMotionPacket motion when isOwnEntity(motion.id()) -> pendingMotion = motion.movement();
			// Teleport: Der Server wartet auf die Bestätigung. Nur die neueste zählt.
			case ClientboundPlayerPositionPacket position -> pendingTeleportId = position.id();
			default -> {
			}
		}
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
		send(packet, listener, true);
	}

	/**
	 * Führt aus, was ein echter Client auf die gemerkten Pakete hin tun würde. Wird einmal pro
	 * Server-Tick vor dem Tick des Bots aufgerufen, nie mitten im Senden: der Server ändert
	 * nach manchen Paketen noch Zustand, der die Reaktion sonst überschreiben würde.
	 */
	public void applyClientReactions() {
		if (!(listener instanceof ServerGamePacketListenerImpl game)) {
			return;
		}
		if (pendingTeleportId >= 0) {
			int id = pendingTeleportId;
			pendingTeleportId = -1;
			game.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
		}
		if (pendingMotion != null) {
			// Setzen statt addieren: Hat der Server die Geschwindigkeit schon selbst behalten
			// (Mobs, Pfeile), ändert das nichts; es gibt also keinen doppelten Rückstoß.
			game.player.setDeltaMovement(pendingMotion);
			pendingMotion = null;
		}
	}

	private boolean isOwnEntity(int entityId) {
		return listener instanceof ServerGamePacketListenerImpl game && game.player.getId() == entityId;
	}

	@Override
	public void flushChannel() {
	}

	@Override
	public void tick() {
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
		this.listener = packetListener;
	}

	@Override
	public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
	}

	@Override
	public PacketListener getPacketListener() {
		return listener;
	}

	@Override
	public boolean isConnected() {
		return disconnection == null;
	}

	@Override
	public boolean isConnecting() {
		return false;
	}

	@Override
	public boolean isMemoryConnection() {
		return false;
	}

	@Override
	public String getLoggableAddress(boolean logIps) {
		return "aiplayer-bot";
	}

	@Override
	public void disconnect(Component reason) {
		disconnect(new DisconnectionDetails(reason));
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
		if (disconnection == null) {
			disconnection = details;
		}
	}

	@Override
	public DisconnectionDetails getDisconnectionDetails() {
		return disconnection;
	}

	@Override
	public void setReadOnly() {
	}

	/** Wird vom Server nach einem Rauswurf (z. B. {@code /kick}) aufgerufen und räumt den Spieler ab. */
	@Override
	public void handleDisconnection() {
		if (disconnection != null && !disconnectionHandled && listener != null) {
			disconnectionHandled = true;
			listener.onDisconnect(disconnection);
		}
	}
}
