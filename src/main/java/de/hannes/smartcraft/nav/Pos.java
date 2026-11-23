package de.hannes.smartcraft.nav;

/** Blockposition der Füße. Eigener Typ ohne Spielklassen, damit die Suche ohne Spiel testbar bleibt. */
public record Pos(int x, int y, int z) {
	public double distanceTo(Pos other) {
		return Math.sqrt(distanceSqr(other.x, other.y, other.z));
	}

	public double distanceSqr(int ox, int oy, int oz) {
		double dx = x - ox;
		double dy = y - oy;
		double dz = z - oz;
		return dx * dx + dy * dy + dz * dz;
	}
}
