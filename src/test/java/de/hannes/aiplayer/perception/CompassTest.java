package de.hannes.aiplayer.perception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompassTest {
	@Test
	void northIsNegativeZAndEastIsPositiveX() {
		assertEquals("N", Compass.towards(0, -5));
		assertEquals("E", Compass.towards(5, 0));
		assertEquals("S", Compass.towards(0, 5));
		assertEquals("W", Compass.towards(-5, 0));
		assertEquals("NE", Compass.towards(3, -3));
		assertEquals("SW", Compass.towards(-3, 3));
		assertEquals("NW", Compass.towards(-3, -3));
		assertEquals("HERE", Compass.towards(0, 0));
	}

	@Test
	void yawZeroLooksSouth() {
		assertEquals("S", Compass.facing(0));
		assertEquals("W", Compass.facing(90));
		assertEquals("N", Compass.facing(180));
		assertEquals("N", Compass.facing(-180));
		assertEquals("E", Compass.facing(-90));
		assertEquals("E", Compass.facing(270));
	}
}
