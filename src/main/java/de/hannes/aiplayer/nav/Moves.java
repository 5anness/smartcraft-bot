package de.hannes.aiplayer.nav;

/**
 * Die Bewegungsregeln des Bots: wo er stehen kann und welche Schritte von dort möglich sind.
 * Eine Position meint immer den Block, in dem die Füße sind.
 */
public final class Moves {
	/** Höchste Fallhöhe auf festen Boden. Darüber gibt es Fallschaden. */
	public static final int MAX_DROP = 3;
	/** Höchste Fallhöhe, wenn die Landung im Wasser ist. */
	public static final int MAX_DROP_INTO_WATER = 12;
	/** So hoch taucht die Wegfindung höchstens senkrecht auf. */
	private static final int MAX_SURFACE_SWIM = 24;

	private static final double COST_STRAIGHT = 1.0;
	private static final double COST_DIAGONAL = 1.414;
	private static final double COST_STEP_UP = 2.0;
	/** Schwimmen teuer: lieber um Wasser herum (Festungsläufe 9, 13, 14: nachts im Meer von Ertrunkenen erstochen). */
	private static final double COST_WATER = 4.0;
	private static final double COST_UNDERWATER = 5.0;
	private static final double COST_CLIMB = 2.0;
	/** Aufschlag für Felder direkt neben Lava, Feuer und Co.: lieber einen Bogen laufen. */
	private static final double COST_NEAR_DANGER = 6.0;
	/** Feld neben einem Abgrund, solange ein Ghast in der Nähe ist. Kit-Lauf 2: Ein Feuerball stieß den Bot auf Höhe 57
	 * über eine Kante in den Lavasee auf Höhe 32. */
	private static final double COST_EDGE = 6.0;
	/** Ab dieser Tiefe gilt eine Kante als gefährlich (Sturz oder Lava darunter). */
	private static final int EDGE_DEPTH = 4;
	/** Gesetzt vom PathFollower beim Start jeder Suche; nur auf dem Server-Thread benutzt. */
	private static boolean edgeCaution;
	/** Abbauen und Setzen dauern; ein Umweg von ein paar Blöcken ist billiger. */
	private static final double COST_DIG = 8.0;
	private static final double COST_PLACE = 6.0;

	private static final int[][] CARDINALS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
	private static final int[][] DIAGONALS = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};

	/** Wie der Bot auf einem Feld steht. */
	public enum Kind {
		/** Kein Platz zum Stehen. */
		NONE,
		GROUND,
		WATER,
		CLIMB
	}

	/**
	 * Was der Bot unterwegs an der Welt ändern darf.
	 *
	 * @param allowDig   Blöcke abbauen, die im Weg sind
	 * @param allowPlace Lücken überbrücken und sich hochbauen
	 */
	/** @param digFortress Festungsblöcke abbauen (nur {@code farm_spawner} auf dem Weg zum Spawner) */
	/**
	 * @param maxDrop wie tief der Weg hinunterspringen darf; Standard {@link #MAX_DROP}. farm_spawner erlaubt mehr, um in
	 *                einen Spawnerraum zu kommen (Nether-Lauf 25: 5 Blöcke unter ihm, unerreichbar mit 3).
	 */
	/** @param allowPillar mit allowPlace auch senkrecht hochbauen (1×1-Säule); Brücken gehen immer */
	public record Options(boolean allowDig, boolean allowPlace, boolean digFortress, int maxDrop, boolean allowPillar) {
		public static final Options NONE = new Options(false, false);

		public Options(boolean allowDig, boolean allowPlace, boolean digFortress, int maxDrop) {
			this(allowDig, allowPlace, digFortress, maxDrop, true);
		}

		/** Dasselbe ohne Hochbauen. */
		public Options withoutPillar() {
			return new Options(allowDig, allowPlace, digFortress, maxDrop, false);
		}

		public Options(boolean allowDig, boolean allowPlace) {
			this(allowDig, allowPlace, false);
		}

		public Options(boolean allowDig, boolean allowPlace, boolean digFortress) {
			this(allowDig, allowPlace, digFortress, MAX_DROP);
		}

		public boolean any() {
			return allowDig || allowPlace;
		}
	}

	@FunctionalInterface
	public interface Visitor {
		/** @param edited ob dieser Schritt erst nach Abbauen oder Setzen möglich ist */
		void visit(int x, int y, int z, double cost, boolean edited);
	}

	private Moves() {
	}

	public static Kind kind(CellSource cells, int x, int y, int z) {
		Cell feet = cells.at(x, y, z);
		if (feet == Cell.LOW) {
			// Steht einen halben Block höher, braucht also auch zwei Felder weiter oben noch Platz.
			return cells.at(x, y + 1, z).passable() && cells.at(x, y + 2, z).passable() ? Kind.GROUND : Kind.NONE;
		}
		if (!feet.passable()) {
			return Kind.NONE;
		}
		Cell head = cells.at(x, y + 1, z);
		if (!head.passable()) {
			return Kind.NONE;
		}
		Cell below = cells.at(x, y - 1, z);
		if (below == Cell.DANGER) {
			return Kind.NONE;
		}
		if (feet == Cell.WATER) {
			// Geschwommen wird an der Oberfläche. Mit dem Kopf unter Wasser plant die Suche keine Wege:
			// Sie rechnet keine Atemluft ein, und der Bot ist darin schon ertrunken.
			return head == Cell.WATER ? Kind.NONE : Kind.WATER;
		}
		if (head == Cell.WATER) {
			return Kind.NONE;
		}
		if (below == Cell.FLOOR) {
			return Kind.GROUND;
		}
		return feet == Cell.CLIMB ? Kind.CLIMB : Kind.NONE;
	}

	/**
	 * Ruft {@code visitor} für jedes Feld auf, das von hier mit einem Schritt erreichbar ist.
	 *
	 * @param edited ob dieses Feld selbst erst durch Abbauen oder Setzen entstanden ist; dann gelten
	 *               seine beiden Felder als frei und der Block darunter als Boden
	 */
	public static void neighbours(CellSource world, int x, int y, int z, Options options, boolean edited, Visitor visitor) {
		CellSource cells = edited ? afterEdit(world, x, y, z) : world;
		Kind here = kind(cells, x, y, z);
		if (here == Kind.NONE) {
			return;
		}
		boolean hereLow = cells.at(x, y, z) == Cell.LOW;
		boolean headroom = cells.at(x, y + 2, z).passable();
		boolean canEdit = here == Kind.GROUND && !hereLow;

		for (int[] d : CARDINALS) {
			int nx = x + d[0];
			int nz = z + d[1];
			boolean moved = false;

			Kind level = kind(cells, nx, y, nz);
			if (level != Kind.NONE) {
				visitor.visit(nx, y, nz, COST_STRAIGHT + extraCost(cells, level, nx, y, nz), false);
				moved = true;
			} else {
				// Einen Block hinauf: springen, aus dem Wasser steigen oder von der Leiter abtreten.
				// Auf einen niedrigen Block eine Etage höher sind es anderthalb Blöcke: zu hoch.
				Kind up = headroom ? kind(cells, nx, y + 1, nz) : Kind.NONE;
				if (up != Kind.NONE && (hereLow || cells.at(nx, y + 1, nz) != Cell.LOW)) {
					visitor.visit(nx, y + 1, nz, COST_STEP_UP + extraCost(cells, up, nx, y + 1, nz), false);
					moved = true;
				} else if (here != Kind.WATER && cells.at(nx, y, nz).passable() && cells.at(nx, y + 1, nz).passable()) {
					// Hinunter: nur wenn die Spalte frei ist und die Landung sicher.
					for (int drop = 1; drop <= MAX_DROP_INTO_WATER; drop++) {
						int ny = y - drop;
						Kind landing = kind(cells, nx, ny, nz);
						if (landing != Kind.NONE) {
							if (drop <= options.maxDrop() || landing == Kind.WATER) {
								visitor.visit(nx, ny, nz, COST_STRAIGHT + 0.5 * drop + extraCost(cells, landing, nx, ny, nz), false);
								moved = true;
							}
							break;
						}
						if (!cells.at(nx, ny, nz).passable()) {
							break;
						}
					}
				}
			}

			if (options.allowDig() && canEdit) {
				digMoves(cells, x, y, z, nx, nz, level != Kind.NONE, options.maxDrop(), visitor);
			}
			// Lücke überbrücken: einen Block in das Loch vor den Füßen setzen. Nicht über Lava.
			if (!moved && options.allowPlace() && canEdit
					&& cells.at(nx, y, nz).passable() && cells.at(nx, y + 1, nz).passable()
					&& (cells.at(nx, y - 1, nz) == Cell.OPEN || cells.at(nx, y - 1, nz) == Cell.WATER)) {
				visitor.visit(nx, y, nz, COST_STRAIGHT + COST_PLACE + placeExtra, true);
			}
		}

		// Diagonal nur auf gleicher Höhe und nur, wenn beide Ecken selbst begehbar sind;
		// sonst bliebe der Bot an der Kante hängen oder rutschte in ein Loch.
		for (int[] d : DIAGONALS) {
			int nx = x + d[0];
			int nz = z + d[1];
			Kind diagonal = kind(cells, nx, y, nz);
			if (diagonal != Kind.NONE && kind(cells, nx, y, z) != Kind.NONE && kind(cells, x, y, nz) != Kind.NONE) {
				visitor.visit(nx, y, nz, COST_DIAGONAL + extraCost(cells, diagonal, nx, y, nz), false);
			}
		}

		// Senkrecht: im Wasser tauchen und auftauchen, an Leitern klettern.
		Kind above = kind(cells, x, y + 1, z);
		if (above == Kind.WATER && here == Kind.WATER) {
			visitor.visit(x, y + 1, z, COST_WATER, false);
		} else if (above == Kind.NONE && cells.at(x, y + 1, z) == Cell.WATER && cells.at(x, y + 2, z) == Cell.WATER) {
			// Auftauchen: senkrecht durch eine Wassersäule bis zur Oberfläche (höchstens MAX_SURFACE_SWIM Blöcke, die Luft reicht
			// für etwa 15 s). Drachenlauf 75: unter dem Meeresgrund eingeschlossen, jeder Weg nach oben no_path, 20 Minuten fest.
			for (int up = 2; up <= MAX_SURFACE_SWIM; up++) {
				Cell cell = cells.at(x, y + up, z);
				if (cell == Cell.WATER) {
					continue;
				}
				if (kind(cells, x, y + up - 1, z) == Kind.WATER) {
					visitor.visit(x, y + up - 1, z, COST_UNDERWATER * up, false);
				}
				break;
			}
		} else if (above != Kind.NONE && cells.at(x, y + 1, z) == Cell.CLIMB && cells.at(x, y, z) == Cell.CLIMB) {
			visitor.visit(x, y + 1, z, COST_CLIMB, false);
		} else if (above == Kind.NONE && options.allowPlace() && options.allowPillar() && canEdit) {
			// Hochbauen: springen und einen Block unter sich setzen.
			int blocked = clearable(cells, x, y + 2, z, options);
			if (blocked >= 0) {
				visitor.visit(x, y + 1, z, COST_STEP_UP + COST_PLACE + COST_DIG * blocked, true);
			}
		}
		// Senkrecht hinunter graben: den Block unter sich abbauen, wenn darunter in erlaubter Tiefe sicher Boden ist.
		// Nether-Läufe 25 und 28: Eine Treppe durch den Fels fand die Suche nicht rechtzeitig, er saß über dem Spawner fest.
		if (options.allowDig() && canEdit && cells.at(x, y - 1, z) == Cell.FLOOR && cells.diggable(x, y - 1, z)) {
			if (cells.at(x, y - 2, z) == Cell.FLOOR) {
				visitor.visit(x, y - 1, z, COST_STRAIGHT + COST_DIG, true);
			} else {
				for (int drop = 2; drop <= options.maxDrop(); drop++) {
					int ny = y - drop;
					Kind landing = kind(cells, x, ny, z);
					if (landing == Kind.GROUND) {
						visitor.visit(x, ny, z, COST_STRAIGHT + COST_DIG + 0.5 * drop, true);
						break;
					}
					if (landing != Kind.NONE || !cells.at(x, ny, z).passable()) {
						break;
					}
				}
			}
		}
		Kind beneath = kind(cells, x, y - 1, z);
		if (beneath == Kind.WATER && here == Kind.WATER) {
			visitor.visit(x, y - 1, z, COST_UNDERWATER, false);
		} else if (beneath != Kind.NONE && cells.at(x, y - 1, z) == Cell.CLIMB) {
			visitor.visit(x, y - 1, z, COST_CLIMB, false);
		}
	}

	/** Schritte, die erst nach dem Abbauen von Blöcken gehen: geradeaus, als Stufe hinauf oder hinunter. */
	private static void digMoves(CellSource cells, int x, int y, int z, int nx, int nz, boolean levelIsOpen, int maxDrop,
			Visitor visitor) {
		Options dig = new Options(true, false);
		if (!levelIsOpen && cells.at(nx, y - 1, nz) == Cell.FLOOR) {
			int count = sum(clearable(cells, nx, y, nz, dig), clearable(cells, nx, y + 1, nz, dig));
			if (count > 0) {
				visitor.visit(nx, y, nz, COST_STRAIGHT + COST_DIG * count, true);
			}
		}
		if (cells.at(nx, y, nz) == Cell.FLOOR && kind(cells, nx, y + 1, nz) == Kind.NONE) {
			int count = sum(clearable(cells, x, y + 2, z, dig),
					sum(clearable(cells, nx, y + 1, nz, dig), clearable(cells, nx, y + 2, nz, dig)));
			if (count > 0) {
				visitor.visit(nx, y + 1, nz, COST_STEP_UP + COST_DIG * count, true);
			}
		}
		if (cells.at(nx, y - 2, nz) == Cell.FLOOR && kind(cells, nx, y - 1, nz) == Kind.NONE) {
			int count = sum(clearable(cells, nx, y + 1, nz, dig),
					sum(clearable(cells, nx, y, nz, dig), clearable(cells, nx, y - 1, nz, dig)));
			if (count > 0) {
				visitor.visit(nx, y - 1, nz, COST_STRAIGHT + 0.5 + COST_DIG * count, true);
			}
		}
		// Freiräumen und hinunterspringen: Die zwei Felder vor ihm abbauen, darunter ist Luft bis zu einer Landung.
		// Nether-Lauf 25: Aus einem Gang über dem Spawnerraum kam er sonst nicht hinunter.
		// Auch der Boden vor ihm darf weg (Dach über einem Raum); landen muss er dann mindestens 2 tiefer.
		if (kind(cells, nx, y - 1, nz) == Kind.NONE || cells.at(nx, y - 2, nz).passable()) {
			int count = sum(sum(clearable(cells, nx, y, nz, dig), clearable(cells, nx, y + 1, nz, dig)),
					clearable(cells, nx, y - 1, nz, dig));
			if (count > 0) {
				for (int drop = 2; drop <= maxDrop; drop++) {
					int ny = y - drop;
					Kind landing = kind(cells, nx, ny, nz);
					if (landing == Kind.GROUND) {
						visitor.visit(nx, ny, nz, COST_STRAIGHT + 0.5 * drop + COST_DIG * count, true);
						break;
					}
					if (landing != Kind.NONE || !cells.at(nx, ny, nz).passable()) {
						break;
					}
				}
			}
		}
	}

	/** @return 0, wenn das Feld schon frei ist; 1, wenn es sich freimachen lässt; -1, wenn nicht */
	private static int clearable(CellSource cells, int x, int y, int z, Options options) {
		if (cells.at(x, y, z).passable()) {
			return 0;
		}
		return options.allowDig() && cells.diggable(x, y, z) ? 1 : -1;
	}

	private static int sum(int a, int b) {
		return a < 0 || b < 0 ? -1 : a + b;
	}

	/** Die Welt, nachdem der Bot sich dieses Feld freigeräumt oder den Boden darunter gesetzt hat. */
	private static CellSource afterEdit(CellSource world, int x, int y, int z) {
		return new CellSource() {
			@Override
			public Cell at(int cx, int cy, int cz) {
				Cell cell = world.at(cx, cy, cz);
				if (cx != x || cz != z) {
					return cell;
				}
				if ((cy == y || cy == y + 1) && !cell.passable()) {
					return Cell.OPEN;
				}
				if (cy == y - 1 && cell.passable()) {
					return Cell.FLOOR;
				}
				return cell;
			}

			@Override
			public boolean diggable(int cx, int cy, int cz) {
				return world.diggable(cx, cy, cz);
			}
		};
	}

	/** Zuschlag je gesetztem Brückenblock (Nether: durch Netherrack graben statt über Lava und Abgründe bauen). */
	private static double placeExtra;

	/**
	 * Im Nether Brücken teuer machen: Drachenläufe 24, 28, 29, 30 bauten bis 200 Blöcke lange Brücken durch offene Höhlen,
	 * Lohen stießen sie herunter oder die Bausteine gingen aus. Netherrack zu graben ist sicherer und bringt Blöcke.
	 */
	public static void setPlaceExtra(double extra) {
		placeExtra = extra;
	}

	/** Kanten meiden (Ghast in der Nähe) für die nächsten Suchen. */
	public static void setEdgeCaution(boolean on) {
		edgeCaution = on;
	}

	/** Im End, solange der Drache lebt: um den Brunnen in der Mitte herum laufen (dort landet er und stößt). */
	private static boolean fountainCaution;
	private static final double COST_FOUNTAIN = 12.0; // End-Lauf 57: mit 4 lief er zwischen zwei Kristallen noch durch die Mitte
	private static final int FOUNTAIN_RADIUS = 16;

	public static void setFountainCaution(boolean on) {
		fountainCaution = on;
	}

	/** Ob neben (x, y, z) ein Abgrund von mindestens {@link #EDGE_DEPTH} Blöcken liegt. */
	private static boolean nextToEdge(CellSource cells, int x, int y, int z) {
		for (int[] d : CARDINALS) {
			int nx = x + d[0], nz = z + d[1];
			boolean deep = true;
			for (int dy = 0; dy <= EDGE_DEPTH && deep; dy++) {
				Cell cell = cells.at(nx, y - 1 - dy, nz);
				deep = cell == Cell.OPEN || cell == Cell.DANGER;
			}
			if (deep) {
				return true;
			}
		}
		return false;
	}

	private static double extraCost(CellSource cells, Kind kind, int x, int y, int z) {
		double cost = edgeCaution && nextToEdge(cells, x, y, z) ? COST_EDGE : 0;
		// End-Lauf 16: auf dem Weg zum ersten Kristall quer über die Mitte, zwei Treffer des Drachen, über eine Kante gestoßen.
		if (fountainCaution && x * x + z * z < FOUNTAIN_RADIUS * FOUNTAIN_RADIUS) {
			cost += COST_FOUNTAIN;
		}
		if (kind == Kind.WATER) {
			cost += cells.at(x, y + 1, z) == Cell.WATER ? COST_UNDERWATER - COST_STRAIGHT : COST_WATER - COST_STRAIGHT;
		}
		for (int[] d : CARDINALS) {
			if (cells.at(x + d[0], y, z + d[1]) == Cell.DANGER || cells.at(x + d[0], y - 1, z + d[1]) == Cell.DANGER) {
				return cost + COST_NEAR_DANGER;
			}
		}
		return cost;
	}
}
