package de.hannes.smartcraft.nav;

/** Die Welt aus Sicht der Wegfindung. Eigene Schnittstelle, damit sich die Suche ohne Spiel testen lässt. */
@FunctionalInterface
public interface CellSource {
	Cell at(int x, int y, int z);

	/**
	 * Ob der Bot diesen Block unterwegs gefahrlos abbauen darf. Die Welt-Implementierung schließt
	 * Unzerstörbares, Truhen und Blöcke neben Flüssigkeiten oder unter Kies aus.
	 */
	default boolean diggable(int x, int y, int z) {
		Cell cell = at(x, y, z);
		return cell == Cell.FLOOR || cell == Cell.OBSTACLE || cell == Cell.LOW;
	}
}
