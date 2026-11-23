package de.hannes.smartcraft.nav;

/** Was ein Block für die Wegfindung bedeutet. */
public enum Cell {
	/** Frei begehbar: Luft, Gras, Blumen und alles andere ohne Kollision. */
	OPEN,
	/** Block, auf dessen Oberseite man steht: volle Blöcke, Treppen, obere Platten, Ackerboden. */
	FLOOR,
	/** Niedriger Block, auf dem man innerhalb seines Feldes steht: untere Platte, Teppich, Schneeschicht, Bett. */
	LOW,
	/** Wasser ohne festen Block darin. */
	WATER,
	/** Leiter, Ranke oder Ähnliches. */
	CLIMB,
	/** Verletzt bei Berührung: Lava, Feuer, Magma, Kaktus, Lagerfeuer, Beerenstrauch, Pulverschnee. */
	DANGER,
	/** Kollision, aber nichts zum Draufstehen: Zaun, Zauntor, Tür, Mauer, Truhe, Glasscheibe. Wird umgangen. */
	OBSTACLE,
	/** Chunk nicht geladen; die Wegfindung fasst ihn nicht an. */
	UNLOADED;

	public boolean passable() {
		return this == OPEN || this == WATER || this == CLIMB;
	}
}
