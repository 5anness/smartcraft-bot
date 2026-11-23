package de.hannes.aiplayer.perception;

import com.google.gson.JsonObject;
import de.hannes.aiplayer.protocol.Priority;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Fasst Serien gleichartiger Events zusammen, damit Log und Modell nicht überlaufen.
 * Das erste Event einer Serie geht sofort raus. Alles Gleichartige in der folgenden Sekunde
 * wird gesammelt und danach als ein Event mit {@code count} (und summiertem {@code amount}) gemeldet.
 */
public final class EventCoalescer {
	private static final int WINDOW_TICKS = 20;

	private static final class Window {
		long end;
		String name;
		Priority prio;
		JsonObject latest;
		int absorbed;
		double amount;
	}

	private final Map<String, Window> windows = new HashMap<>();

	/**
	 * @param key    was als „gleichartig“ gilt, z. B. Event-Name plus Schadensart
	 * @param amount Menge, die beim Zusammenfassen summiert wird (0, wenn es keine gibt)
	 * @return {@code true}, wenn der Aufrufer das Event sofort senden soll
	 */
	public boolean admit(String key, long tick, String name, Priority prio, JsonObject data, double amount) {
		Window window = windows.get(key);
		if (window == null) {
			window = new Window();
			window.end = tick + WINDOW_TICKS;
			windows.put(key, window);
			return true;
		}
		window.name = name;
		window.prio = prio;
		window.latest = data;
		window.absorbed++;
		window.amount += amount;
		return false;
	}

	/** Meldet abgelaufene Serien. Einmal pro Tick aufrufen. */
	public void flush(long tick, EventBus bus) {
		Iterator<Window> it = windows.values().iterator();
		while (it.hasNext()) {
			Window window = it.next();
			if (window.end > tick) {
				continue;
			}
			it.remove();
			if (window.absorbed == 0) {
				continue;
			}
			if (window.absorbed > 1) {
				window.latest.addProperty("count", window.absorbed);
			}
			if (window.amount > 0) {
				window.latest.addProperty("amount", GameJson.round1(window.amount));
			}
			bus.emit(window.name, tick, window.prio, window.latest);
		}
	}

	public void clear() {
		windows.clear();
	}
}
