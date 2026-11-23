package de.hannes.smartcraft.action;

import com.google.gson.JsonObject;
import de.hannes.smartcraft.protocol.Event.Status;
import net.minecraft.server.level.ServerPlayer;

/**
 * Eine Handlung des Bots mit Dauer. Der {@link ActionRunner} ruft {@link #tick} einmal pro
 * Server-Tick auf, bevor der Bot selbst tickt, und meldet am Ende genau ein Ergebnis.
 */
public abstract class Action {
	/**
	 * Ausgang einer Aktion.
	 *
	 * @param reason Grund aus der festen Liste der Aktion; {@code null} bei {@code OK}
	 * @param data   der bestätigte Zustand danach
	 */
	public record Outcome(Status status, String reason, JsonObject data) {
		public static Outcome ok(JsonObject data) {
			return new Outcome(Status.OK, null, data);
		}

		public static Outcome failed(String reason, JsonObject data) {
			return new Outcome(Status.FAILED, reason, data);
		}

		public static Outcome cancelled(String reason, JsonObject data) {
			return new Outcome(Status.CANCELLED, reason, data);
		}
	}

	/** Nach so vielen Ticks bricht der Runner mit {@code failed: timeout} ab. */
	public abstract int timeoutTicks();

	/** @return das Ergebnis, sobald die Aktion fertig ist; {@code null}, solange sie läuft */
	public abstract Outcome tick(ServerPlayer bot);

	/** Aufräumen bei jedem Ende, auch bei Abbruch: Tasten loslassen, nichts halb getan hinterlassen. */
	public abstract void release(ServerPlayer bot);

	/** Ob Schaden die Aktion abbricht. Kampf, Flucht und Essen laufen weiter, wenn der Bot getroffen wird. */
	/**
	 * Ob der Notwehr-Reflex während dieser Aktion zurückschlagen darf (Wege, Hütte): Drachenlauf 76 wurde neben Lava von
	 * Magmawürfeln zerlegt, während goto und shelter liefen, und schlug nie zurück.
	 */
	public boolean allowsSelfDefense() {
		return false;
	}

	public boolean cancelOnDamage() {
		return true;
	}

	/** Ob neu auftauchende Lava oder Feuer die Aktion abbricht. Der Eimer an der Lava läuft weiter. */
	public boolean cancelOnDanger() {
		return true;
	}

	/** Im Nether grenzt fast alles an Lava; Aktionen, die dort trotzdem weiterlaufen sollen, überschreiben das. */
	public boolean cancelOnDangerInNether() {
		return cancelOnDanger();
	}

	/** Zustand, der beim Abbruch von außen (Stopp, Gefahr, Timeout) ins Ergebnis kommt. */
	public JsonObject progress(ServerPlayer bot) {
		return new JsonObject();
	}
}
