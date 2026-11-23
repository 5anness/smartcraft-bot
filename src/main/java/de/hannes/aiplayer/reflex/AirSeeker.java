package de.hannes.aiplayer.reflex;

import de.hannes.aiplayer.nav.Cell;
import de.hannes.aiplayer.nav.WorldCells;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sucht unter Wasser den nächsten Ort mit Luft und schwimmt hin, auch seitlich unter einer Decke
 * hindurch. Eigene kleine Suche statt der Wegfindung, weil die keine Tauchwege plant.
 */
final class AirSeeker {
	/** So viele Felder sieht sich die Suche höchstens an; reicht für rund zehn Blöcke in jede Richtung. */
	private static final int MAX_CELLS = 12000;
	private static final int REPLAN_INTERVAL = 8;
	private static final int[][] STEPS = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};

	private BlockPos next;
	private int ticks;

	/**
	 * Steuert einen Tick lang Richtung Luft.
	 *
	 * @return {@code false}, wenn in Reichweite keine Luft zu finden ist
	 */
	boolean tick(ServerPlayer bot) {
		// Nur alle REPLAN_INTERVAL Ticks suchen, auch ohne Ergebnis: die größere Suche kostet sonst jeden Tick Zeit.
		if (ticks++ % REPLAN_INTERVAL == 0) {
			next = firstStep(bot);
		}
		if (next == null) {
			// Kein Weg bekannt: wenigstens nach oben drücken.
			bot.zza = 0;
			bot.xxa = 0;
			bot.setJumping(true);
			return false;
		}
		double dx = next.getX() + 0.5 - bot.getX();
		double dz = next.getZ() + 0.5 - bot.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > 0.15) {
			float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
			bot.setYRot(yaw);
			bot.setYHeadRot(yaw);
		}
		bot.zza = horizontal > 0.15 ? 1.0f : 0.0f;
		bot.xxa = 0;
		// Im Wasser hält „springen“ die Höhe oder steigt; zum Abtauchen loslassen.
		bot.setJumping(next.getY() >= bot.getY() - 0.3);
		return true;
	}

	/** Breitensuche durch Wasser und Luft; liefert das erste Feld auf dem kürzesten Weg zu einem Platz mit Luft am Kopf. */
	private static BlockPos firstStep(ServerPlayer bot) {
		WorldCells cells = new WorldCells(bot.level());
		BlockPos start = BlockPos.containing(bot.getX(), bot.getY() + 0.2, bot.getZ());
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		cameFrom.put(start, start);
		queue.add(start);
		while (!queue.isEmpty() && cameFrom.size() < MAX_CELLS) {
			BlockPos pos = queue.poll();
			if (cells.at(pos.getX(), pos.getY() + 1, pos.getZ()) == Cell.OPEN) {
				// Rückwärts bis zum ersten Schritt nach dem Start.
				BlockPos step = pos;
				while (!cameFrom.get(step).equals(start) && !step.equals(start)) {
					step = cameFrom.get(step);
				}
				return step;
			}
			for (int[] s : STEPS) {
				BlockPos neighbour = pos.offset(s[0], s[1], s[2]);
				if (cameFrom.containsKey(neighbour)) {
					continue;
				}
				// Der Körper braucht zwei freie Felder übereinander.
				if (cells.at(neighbour.getX(), neighbour.getY(), neighbour.getZ()).passable()
						&& cells.at(neighbour.getX(), neighbour.getY() + 1, neighbour.getZ()).passable()) {
					cameFrom.put(neighbour, pos);
					queue.add(neighbour);
				}
			}
		}
		return null;
	}
}
