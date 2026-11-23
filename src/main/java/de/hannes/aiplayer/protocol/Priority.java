package de.hannes.aiplayer.protocol;

/** Dringlichkeit eines Events. {@code HIGH} ist für Gefahren reserviert. */
public enum Priority {
	NORMAL("normal"),
	HIGH("high");

	private final String wireName;

	Priority(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}
}
