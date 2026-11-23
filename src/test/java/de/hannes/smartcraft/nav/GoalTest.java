package de.hannes.smartcraft.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GoalTest {
	@Test
	void pickupCountsFieldAndDirectNeighbours() {
		Goal goal = Goal.pickup(5.5, 10, 5.5);
		assertEquals(0, goal.distance(5.5, 10, 5.5));
		assertEquals(0, goal.distance(6.5, 10, 5.5));
		assertEquals(0, goal.distance(6.5, 10, 6.5), "schräg daneben reicht auch");
		assertTrue(goal.distance(7.5, 10, 5.5) > 0);
	}

	@Test
	void pickupAllowsStandingOneStepBelowButNotAbove() {
		Goal goal = Goal.pickup(5.5, 10, 5.5);
		assertEquals(0, goal.distance(6.5, 9, 5.5), "Item liegt eine Stufe höher, etwa auf einem Reststamm");
		assertEquals(0, goal.distance(6.5, 8, 5.5));
		assertTrue(goal.distance(6.5, 7, 5.5) > 0);
		assertTrue(goal.distance(6.5, 11, 5.5) > 0, "von oben reicht der Bot nicht hinunter");
	}
}
