package de.hannes.smartcraft.nav;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSearchTest {
	/** Kleine Testwelt: Boden bei y=0 in einem Rechteck, alles andere Luft; einzelne Felder überschreibbar. */
	private static final class Grid implements CellSource {
		private final Map<Pos, Cell> cells = new HashMap<>();
		private final int size;

		Grid(int size) {
			this.size = size;
		}

		Grid set(int x, int y, int z, Cell cell) {
			cells.put(new Pos(x, y, z), cell);
			return this;
		}

		Grid fill(int x1, int y1, int z1, int x2, int y2, int z2, Cell cell) {
			for (int x = x1; x <= x2; x++) {
				for (int y = y1; y <= y2; y++) {
					for (int z = z1; z <= z2; z++) {
						set(x, y, z, cell);
					}
				}
			}
			return this;
		}

		@Override
		public Cell at(int x, int y, int z) {
			Cell override = cells.get(new Pos(x, y, z));
			if (override != null) {
				return override;
			}
			boolean inside = x >= 0 && x < size && z >= 0 && z < size;
			return y == 0 && inside ? Cell.FLOOR : Cell.OPEN;
		}
	}

	private static PathSearch search(CellSource grid, Pos start, Pos goal) {
		return search(grid, start, goal, Moves.Options.NONE);
	}

	private static PathSearch search(CellSource grid, Pos start, Pos goal, Moves.Options options) {
		PathSearch search = new PathSearch(grid, start, Goal.near(goal, 0), options);
		// Kleines Budget, damit auch das Weitersuchen über mehrere Aufrufe geprüft wird.
		while (search.advance(200_000) == PathSearch.State.RUNNING) {
			// weiter
		}
		return search;
	}

	@Test
	void walksStraightOnFlatGround() {
		PathSearch search = search(new Grid(10), new Pos(1, 1, 1), new Pos(6, 1, 1));
		assertEquals(PathSearch.State.FOUND, search.state());
		assertEquals(5, search.path().size());
		assertEquals(new Pos(6, 1, 1), search.path().getLast());
	}

	@Test
	void goesAroundWall() {
		Grid grid = new Grid(10).fill(4, 1, 0, 4, 3, 7, Cell.FLOOR);
		PathSearch search = search(grid, new Pos(1, 1, 1), new Pos(8, 1, 1));
		assertEquals(PathSearch.State.FOUND, search.state());
		assertTrue(search.path().stream().anyMatch(pos -> pos.z() >= 8), "muss um das Mauerende bei z=8 herum");
		assertFalse(search.path().stream().anyMatch(pos -> pos.x() == 4 && pos.z() <= 7));
	}

	@Test
	void stepsUpOneBlockButNotTwo() {
		Grid oneHigh = new Grid(10).fill(5, 1, 0, 9, 1, 9, Cell.FLOOR);
		PathSearch up = search(oneHigh, new Pos(1, 1, 1), new Pos(7, 2, 1));
		assertEquals(PathSearch.State.FOUND, up.state());

		Grid twoHigh = new Grid(10).fill(5, 1, 0, 9, 2, 9, Cell.FLOOR);
		PathSearch blocked = search(twoHigh, new Pos(1, 1, 1), new Pos(7, 3, 1));
		assertEquals(PathSearch.State.ENCLOSED, blocked.state());
	}

	@Test
	void dropsThreeBlocksButNotFour() {
		// Plateau bei x 0..4, davor geht es hinunter.
		Grid three = new Grid(10).fill(0, 1, 0, 4, 3, 9, Cell.FLOOR);
		PathSearch ok = search(three, new Pos(1, 4, 1), new Pos(8, 1, 1));
		assertEquals(PathSearch.State.FOUND, ok.state());

		Grid four = new Grid(10).fill(0, 1, 0, 4, 4, 9, Cell.FLOOR);
		PathSearch tooDeep = search(four, new Pos(1, 5, 1), new Pos(8, 1, 1));
		assertEquals(PathSearch.State.ENCLOSED, tooDeep.state());
		assertTrue(tooDeep.bestDistance() > 3, "bleibt oben auf dem Plateau");
	}

	@Test
	void dropsFarIntoWater() {
		Grid grid = new Grid(10).fill(0, 1, 0, 4, 8, 9, Cell.FLOOR).fill(5, 1, 0, 9, 1, 9, Cell.WATER);
		PathSearch search = search(grid, new Pos(1, 9, 1), new Pos(7, 1, 1));
		assertEquals(PathSearch.State.FOUND, search.state());
	}

	@Test
	void neverStepsOntoOrOverLava() {
		// Ein Lavagraben quer durch den Boden mit einer Lücke bei z=9.
		Grid grid = new Grid(10).fill(4, 0, 0, 4, 0, 8, Cell.DANGER);
		PathSearch search = search(grid, new Pos(1, 1, 1), new Pos(8, 1, 1));
		assertEquals(PathSearch.State.FOUND, search.state());
		List<Pos> path = search.path();
		assertFalse(path.stream().anyMatch(pos -> pos.x() == 4 && pos.z() <= 8));
		assertTrue(path.stream().anyMatch(pos -> pos.x() == 4 && pos.z() == 9));
	}

	@Test
	void swimsAcrossWater() {
		// Der Boden zwischen x=3 und x=6 ist ein ein Block tiefes Becken über die ganze Breite.
		Grid grid = new Grid(10).fill(3, 0, 0, 6, 0, 9, Cell.WATER).fill(3, -1, 0, 6, -1, 9, Cell.FLOOR);
		PathSearch search = search(grid, new Pos(1, 1, 1), new Pos(8, 1, 1));
		assertEquals(PathSearch.State.FOUND, search.state());
		assertTrue(search.path().stream().anyMatch(pos -> pos.y() == 0), "führt durchs Wasser");
	}

	@Test
	void climbsLadder() {
		// Vier Block hohe Mauer bei x=5..9, davor eine Leiter bei x=4.
		Grid grid = new Grid(10).fill(5, 1, 0, 9, 4, 9, Cell.FLOOR).fill(4, 1, 1, 4, 4, 1, Cell.CLIMB);
		PathSearch search = search(grid, new Pos(1, 1, 1), new Pos(7, 5, 1));
		assertEquals(PathSearch.State.FOUND, search.state());
		assertTrue(search.path().contains(new Pos(4, 3, 1)));
	}

	@Test
	void reportsEnclosedGoalWithClosestReachableField() {
		Grid grid = new Grid(10)
				.fill(6, 1, 3, 8, 2, 3, Cell.FLOOR).fill(6, 1, 5, 8, 2, 5, Cell.FLOOR)
				.fill(6, 1, 4, 6, 2, 4, Cell.FLOOR).fill(8, 1, 4, 8, 2, 4, Cell.FLOOR);
		PathSearch search = search(grid, new Pos(1, 1, 4), new Pos(7, 1, 4));
		assertEquals(PathSearch.State.ENCLOSED, search.state());
		assertEquals(2.0, search.bestDistance(), 0.01);
		assertEquals(new Pos(5, 1, 4), search.bestPos());
	}

	@Test
	void givesUpEarlyWhenSearchStopsGettingCloser() {
		// Ziel hoch in der Luft über einer großen Ebene: unerreichbar, aber die Ebene ist riesig.
		PathSearch search = search(new Grid(400), new Pos(200, 1, 200), new Pos(210, 12, 200));
		assertEquals(PathSearch.State.STALLED, search.state());
		assertTrue(search.expanded() < PathSearch.STALL_LIMIT + 500, "bricht kurz nach der Stillstandsgrenze ab");
		assertEquals(new Pos(210, 1, 200), search.bestPos());
	}

	@Test
	void walksOverSlabsAndCarpets() {
		// Eine Reihe niedriger Blöcke quer über den Weg, mit Mauern an beiden Seiten: der Weg führt darüber.
		Grid grid = new Grid(10).fill(4, 1, 0, 4, 1, 9, Cell.LOW);
		PathSearch search = search(grid, new Pos(1, 1, 1), new Pos(8, 1, 1));
		assertEquals(PathSearch.State.FOUND, search.state());
		assertTrue(search.path().contains(new Pos(4, 1, 1)), "geht über die Platte statt außen herum");
	}

	@Test
	void doesNotJumpOntoSlabOneLevelUp() {
		// Ein Block hoch geht; liegt auf der Kante noch eine Platte, sind es anderthalb Blöcke.
		Grid wall = new Grid(10).fill(5, 1, 0, 9, 1, 9, Cell.FLOOR);
		Grid slabOnTop = new Grid(10).fill(5, 1, 0, 9, 1, 9, Cell.FLOOR).fill(5, 2, 0, 5, 2, 9, Cell.LOW);
		assertEquals(PathSearch.State.FOUND, search(wall, new Pos(1, 1, 1), new Pos(7, 2, 1)).state());
		// Auf eine Platte, die eine Etage höher liegt, sind es anderthalb Blöcke: nicht direkt.
		PathSearch viaSlab = search(slabOnTop, new Pos(1, 1, 1), new Pos(5, 2, 1));
		assertEquals(PathSearch.State.ENCLOSED, viaSlab.state());
	}

	@Test
	void digsThroughWallOnlyWhenAllowed() {
		// Ringmauer um das Ziel, zwei hoch, mit Dach: ohne Abbauen kein Weg.
		Grid grid = new Grid(12).fill(5, 1, 3, 9, 3, 7, Cell.FLOOR).fill(6, 1, 4, 8, 2, 6, Cell.OPEN);
		Pos start = new Pos(1, 1, 5);
		Pos goal = new Pos(7, 1, 5);
		assertEquals(PathSearch.State.ENCLOSED, search(grid, start, goal).state());

		PathSearch digging = search(grid, start, goal, new Moves.Options(true, false));
		assertEquals(PathSearch.State.FOUND, digging.state());
		assertTrue(digging.path().contains(new Pos(5, 1, 5)), "geht gerade durch die Mauer");
	}

	@Test
	void digsDownIntoRoomFromAbove() {
		// Nether-Läufe 25 und 28: Fels bis y=10, darin ein Raum (y 1..4), der Bot oben drauf. Mit Graben und Sturz bis 5
		// muss er hinein (Treppe durch den Fels, dann Decke weg und hinunterspringen).
		Grid grid = new Grid(20).fill(0, 1, 0, 19, 10, 19, Cell.FLOOR).fill(6, 1, 6, 12, 4, 12, Cell.OPEN);
		Pos start = new Pos(9, 11, 9);
		PathSearch search = new PathSearch(grid, start, Goal.near(new Pos(9, 1, 9), 1.5), new Moves.Options(true, true, true, 5));
		while (search.advance(200_000) == PathSearch.State.RUNNING) {
			// weiter
		}
		assertEquals(PathSearch.State.FOUND, search.state(), "best " + search.bestPos());
	}

	@Test
	void bridgesGapOnlyWhenAllowed() {
		// Ein Graben quer durch die ganze Welt, zwei breit und bodenlos.
		Grid grid = new Grid(10).fill(4, -20, 0, 5, 0, 9, Cell.OPEN);
		Pos start = new Pos(1, 1, 1);
		Pos goal = new Pos(8, 1, 1);
		assertEquals(PathSearch.State.ENCLOSED, search(grid, start, goal).state());
		assertEquals(PathSearch.State.FOUND, search(grid, start, goal, new Moves.Options(false, true)).state());
	}

	@Test
	void pillarsUpOnlyWhenAllowed() {
		// Plattform vier Blöcke über dem Boden, ohne Aufgang.
		Grid grid = new Grid(10).fill(5, 4, 0, 9, 4, 9, Cell.FLOOR);
		Pos start = new Pos(1, 1, 1);
		Pos goal = new Pos(7, 5, 1);
		assertTrue(search(grid, start, goal).state() != PathSearch.State.FOUND);
		assertEquals(PathSearch.State.FOUND, search(grid, start, goal, new Moves.Options(false, true)).state());
	}

	@Test
	void directSearchReachesGoalHighAboveOpenGround() {
		// Log-Auswertung 13.11.: Ziele senkrecht über dem Bot scheiterten trotz Graben und Bauen oft. Die normale Suche läuft
		// erst die Ebene ab (ein Schritt kostet 1, ein Block Säule 8) und kommt nicht an; die direkte baut sich gleich hoch.
		Moves.Options edits = new Moves.Options(true, true);
		Pos start = new Pos(200, 1, 200);
		Pos goal = new Pos(203, 21, 200);
		PathSearch normal = search(new Grid(400), start, goal, edits);
		assertTrue(normal.state() != PathSearch.State.FOUND, "normale Suche: " + normal.state());

		PathSearch direct = new PathSearch(new Grid(400), start, Goal.near(goal, 0), edits);
		direct.setWeight(20);
		while (direct.advance(200_000) == PathSearch.State.RUNNING) {
			// weiter
		}
		assertEquals(PathSearch.State.FOUND, direct.state(), "best " + direct.bestPos());
		assertTrue(direct.expanded() < 1000, "geht geradewegs hinauf: " + direct.expanded());
	}

	@Test
	void doesNotEnterUnloadedChunks() {
		Grid grid = new Grid(10).fill(5, -5, 0, 9, 5, 9, Cell.UNLOADED);
		PathSearch search = search(grid, new Pos(1, 1, 1), new Pos(8, 1, 1));
		assertEquals(PathSearch.State.ENCLOSED, search.state());
		assertEquals(4, search.path().getLast().x());
	}
}
