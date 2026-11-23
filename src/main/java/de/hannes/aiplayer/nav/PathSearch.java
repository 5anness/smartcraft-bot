package de.hannes.aiplayer.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A*-Suche auf Blockebene. Sie läuft in Portionen mit festem Zeitbudget ({@link #advance}),
 * damit sie nie einen Server-Tick blockiert.
 *
 * <p>Findet die Suche das Ziel nicht, kennt sie trotzdem das erreichbare Feld, das dem Ziel am
 * nächsten liegt ({@link #bestPos()}).
 */
public final class PathSearch {
	public enum State {
		RUNNING,
		/** Weg bis zum Ziel gefunden. */
		FOUND,
		/** Alles Erreichbare abgesucht, das Ziel ist nicht dabei. */
		ENCLOSED,
		/** Lange gesucht, ohne dem Ziel näher zu kommen: vermutlich unerreichbar. */
		STALLED,
		/** Obergrenze erreicht, während die Suche noch vorankam: das Ziel ist weit weg. */
		LIMIT_REACHED
	}

	/** Obergrenze je Suche. Für weite Strecken läuft der Bot das beste Teilstück und sucht dort neu. */
	public static final int MAX_EXPANDED = 10_000;
	/** So viele Felder ohne Annäherung ans Ziel, dann gilt es als unerreichbar. */
	public static final int STALL_LIMIT = 3_000;

	private static final class Node {
		final int x, y, z;
		double g = Double.POSITIVE_INFINITY;
		double f;
		Node parent;
		boolean closed;
		boolean edited;

		Node(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	private final CellSource cells;
	private final Goal goal;
	private final Moves.Options options;
	private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));
	private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private Node current;
	private Node best;
	private double bestDistance;
	private int bestFoundAt;
	private Node found;
	private int expanded;
	private State state = State.RUNNING;

	/** @param start muss ein Feld sein, auf dem der Bot stehen kann */
	public PathSearch(CellSource cells, Pos start, Goal goal, Moves.Options options) {
		this.cells = cells;
		this.goal = goal;
		this.options = options;

		Node first = node(start.x(), start.y(), start.z());
		first.g = 0;
		first.f = distanceToGoal(first);
		open.add(first);
		best = first;
		bestDistance = first.f;
	}

	public State state() {
		return state;
	}

	public int expanded() {
		return expanded;
	}

	/** Das erreichbare Feld, das dem Ziel am nächsten liegt. */
	public Pos bestPos() {
		return new Pos(best.x, best.y, best.z);
	}

	/** Entfernung von {@link #bestPos()} zum Ziel; 0 heißt, das Ziel ist erreichbar. */
	public double bestDistance() {
		return bestDistance;
	}

	/** Sucht weiter, bis das Ergebnis feststeht oder {@code budgetNanos} verbraucht sind. */
	public State advance(long budgetNanos) {
		long deadline = System.nanoTime() + budgetNanos;
		while (state == State.RUNNING) {
			expandOne();
			// Nach jedem Feld auf die Uhr sehen: so überzieht die Suche höchstens um ein einzelnes Feld.
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
		return state;
	}

	private void expandOne() {
		Node next;
		do {
			next = open.poll();
		} while (next != null && next.closed);
		if (next == null) {
			state = State.ENCLOSED;
			return;
		}
		current = next;
		current.closed = true;
		expanded++;

		double distance = distanceToGoal(current);
		if (distance < bestDistance) {
			best = current;
			bestDistance = distance;
			bestFoundAt = expanded;
		}
		if (distance <= 0) {
			found = current;
			state = State.FOUND;
			return;
		}
		if (expanded - bestFoundAt >= STALL_LIMIT) {
			state = State.STALLED;
			return;
		}
		if (expanded >= MAX_EXPANDED) {
			state = State.LIMIT_REACHED;
			return;
		}
		Moves.neighbours(cells, current.x, current.y, current.z, options, current.edited, this::relax);
	}

	/** Gerade Linie, an die sich die Suche halten soll (null: keine). Siehe {@link #setCorridor}. */
	private double[] corridor;
	/** Bis so weit neben der Linie ist alles gleich gut; darüber kostet jeder Block Abstand je Schritt extra. */
	private static final double CORRIDOR_WIDTH = 4;
	private static final double CORRIDOR_COST = 1.0;

	/**
	 * Hält die Suche nahe an der Luftlinie von (ax, az) nach (bx, bz). Hannes' Lauf 17.10.: Im Nether lief der Bot mit
	 * allow_place von Plattform zu Plattform seitlich weg (x 30 → 6 → 13 → −41 auf dem Weg nach Norden) statt geradeaus
	 * Richtung Festung zu bauen.
	 */
	public void setCorridor(double ax, double az, double bx, double bz) {
		double dx = bx - ax, dz = bz - az, length = Math.sqrt(dx * dx + dz * dz);
		corridor = length < 1 ? null : new double[] {ax, az, dx / length, dz / length};
	}

	private double corridorCost(int x, int z) {
		if (corridor == null) {
			return 0;
		}
		double side = Math.abs((x + 0.5 - corridor[0]) * corridor[3] - (z + 0.5 - corridor[1]) * corridor[2]);
		return side > CORRIDOR_WIDTH ? (side - CORRIDOR_WIDTH) * CORRIDOR_COST : 0;
	}

	/** Gewicht der Schätzung; 1 = normale A*-Suche (kürzester Weg). */
	private double weight = 1;

	/**
	 * Zieht die Suche stärker zum Ziel (gewichtetes A*): Sie nimmt dann den direkten Weg mit Graben und Bauen, statt erst alles
	 * abzusuchen, was sich billiger erlaufen lässt. Log-Auswertung 13.11.: goto mit Graben und Bauen scheiterte 1.145-mal mit
	 * search_stalled, oft bei Zielen wenige Blöcke entfernt oder senkrecht darüber. Ein Block Säule kostet 8, ein Schritt 1:
	 * Für 20 Blöcke nach oben lief die normale Suche erst alle Felder im Umkreis von 160 Schritten ab und brach vorher ab.
	 */
	public void setWeight(double weight) {
		this.weight = weight;
	}

	private void relax(int x, int y, int z, double cost, boolean edited) {
		Node next = node(x, y, z);
		double g = current.g + cost + corridorCost(x, z);
		if (!next.closed && g < next.g) {
			next.g = g;
			next.f = g + weight * distanceToGoal(next);
			next.parent = current;
			next.edited = edited;
			// Kein Umsortieren in der Queue: der veraltete Eintrag wird beim Herausnehmen übersprungen.
			open.add(next);
		}
	}

	/** Weg vom Start zum Ziel oder, ohne Treffer, zum besten gefundenen Feld. Der Start ist nicht enthalten. */
	public List<Pos> path() {
		List<Pos> path = new ArrayList<>();
		for (Node node = found != null ? found : best; node != null && node.parent != null; node = node.parent) {
			path.add(new Pos(node.x, node.y, node.z));
		}
		Collections.reverse(path);
		return path;
	}

	private Node node(int x, int y, int z) {
		// 26 Bit für x und z, 12 Bit für y: deckt die ganze Welt ab.
		long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
		Node node = nodes.get(key);
		if (node == null) {
			node = new Node(x, y, z);
			nodes.put(key, node);
		}
		return node;
	}

	private double distanceToGoal(Node node) {
		return goal.distance(node.x + 0.5, node.y, node.z + 0.5);
	}
}
