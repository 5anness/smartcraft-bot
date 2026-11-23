package de.hannes.aiplayer.perception;

/** Himmelsrichtungen in acht Stufen. In Minecraft ist Norden -z und Osten +x. */
public final class Compass {
	private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

	private Compass() {
	}

	/** Richtung eines Versatzes, z. B. vom Bot zu einem Mob. */
	public static String towards(double dx, double dz) {
		if (dx == 0 && dz == 0) {
			return "HERE";
		}
		double degrees = Math.toDegrees(Math.atan2(dx, -dz));
		return NAMES[(int) Math.floorMod(Math.round(degrees / 45.0), 8L)];
	}

	/** Blickrichtung aus dem Yaw-Winkel des Spiels (0 = Süden, 90 = Westen). */
	public static String facing(float yaw) {
		double radians = Math.toRadians(yaw);
		return towards(-Math.sin(radians), Math.cos(radians));
	}
}
