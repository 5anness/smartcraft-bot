package de.hannes.smartcraft.nav;

/** Wohin der Bot will. Gemessen wird an der Fußposition (Mitte des Feldes, Unterkante). */
public interface Goal {
	/** Augenhöhe eines stehenden Spielers über den Füßen. */
	double EYE_HEIGHT = 1.62;

	/** Wie weit es von dieser Fußposition noch ist; 0 heißt angekommen. Dient der Suche als Schätzung. */
	double distance(double feetX, double feetY, double feetZ);

	/** Der Punkt, um den es geht; für Meldungen und Zeitlimits. */
	Pos anchor();

	/** Höchstens {@code tolerance} Blöcke von einem Feld entfernt stehen. 0 = genau auf dem Feld. */
	static Goal near(Pos pos, double tolerance) {
		return new Goal() {
			@Override
			public double distance(double x, double y, double z) {
				double dx = pos.x() + 0.5 - x;
				double dy = pos.y() - y;
				double dz = pos.z() + 0.5 - z;
				return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - tolerance);
			}

			@Override
			public Pos anchor() {
				return pos;
			}
		};
	}

	/**
	 * So stehen, dass ein liegendes Item eingesammelt wird. Das Spiel sammelt ein, was den um einen
	 * Block zur Seite und einen halben nach oben und unten vergrößerten Körper berührt: Das Item
	 * darf also auch schräg daneben und bis zu zwei Blöcke über den Füßen liegen (etwa auf einem
	 * Reststamm oder auf Laub) und muss selbst nicht betretbar sein.
	 */
	static Goal pickup(double itemX, double itemY, double itemZ) {
		Pos anchor = new Pos((int) Math.floor(itemX), (int) Math.floor(itemY + 0.2), (int) Math.floor(itemZ));
		return new Goal() {
			@Override
			public double distance(double x, double y, double z) {
				double horizontal = Math.max(0, Math.max(Math.abs(itemX - x), Math.abs(itemZ - z)) - 1.15);
				double above = itemY - y;
				double vertical = above < -0.5 ? -0.5 - above : Math.max(0, above - 2.0);
				return horizontal + vertical;
			}

			@Override
			public Pos anchor() {
				return anchor;
			}
		};
	}

	/**
	 * So stehen, dass der Block in Reichweite der Hand ist, gemessen von den Augen wie im Spiel.
	 * Die Felder in, auf und direkt unter dem Block zählen nicht: von dort ließe er sich nicht
	 * setzen, und beim Abbauen stünde der Bot auf dem Block, den er entfernt.
	 */
	static Goal withinReach(Pos block, double reach) {
		return new Goal() {
			@Override
			public double distance(double x, double y, double z) {
				double dx = block.x() + 0.5 - x;
				double dy = block.y() + 0.5 - (y + EYE_HEIGHT);
				double dz = block.z() + 0.5 - z;
				double distance = Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach);
				boolean sameColumn = Math.floor(x) == block.x() && Math.floor(z) == block.z();
				// Ab knapp zwei Blöcke darunter ragt der Kopf in das Feld des Blocks.
				if (sameColumn && y > block.y() - 1.9 && y <= block.y() + 1.5) {
					return Math.max(distance, 1.0);
				}
				return distance;
			}

			@Override
			public Pos anchor() {
				return block;
			}
		};
	}

	/**
	 * Möglichst genau unter (oder neben) einem Block stehen, egal wie hoch er hängt.
	 * Für Blöcke, an die der Bot nur durch Hochbauen kommt.
	 */
	static Goal beneath(Pos block) {
		return new Goal() {
			@Override
			public double distance(double x, double y, double z) {
				double dx = block.x() + 0.5 - x;
				double dz = block.z() + 0.5 - z;
				double horizontal = Math.max(0, Math.sqrt(dx * dx + dz * dz) - 1.0);
				// Oberhalb des Blocks zu stehen hilft nicht.
				return y >= block.y() ? horizontal + (y - block.y()) + 1 : horizontal;
			}

			@Override
			public Pos anchor() {
				return block;
			}
		};
	}
}
