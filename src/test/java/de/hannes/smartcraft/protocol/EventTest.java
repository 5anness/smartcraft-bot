package de.hannes.smartcraft.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventTest {
	@Test
	void encodesEnvelopeInSchemaOrder() {
		JsonObject data = new JsonObject();
		data.addProperty("block", "minecraft:oak_log");

		Event event = new Event("block_broken", 412, 88213, Priority.NORMAL, data);

		assertEquals(
				"{\"v\":1,\"type\":\"event\",\"name\":\"block_broken\",\"seq\":412,\"tick\":88213,"
						+ "\"prio\":\"normal\",\"data\":{\"block\":\"minecraft:oak_log\"}}",
				event.toJson().toString());
	}

	@Test
	void resultCarriesCommandIdStatusAndReason() {
		Event result = new Event("mine_result", 413, 88260, Priority.NORMAL, new JsonObject(),
				"c-17", Event.Status.FAILED, "out_of_reach");

		assertEquals(
				"{\"v\":1,\"type\":\"event\",\"name\":\"mine_result\",\"seq\":413,\"tick\":88260,"
						+ "\"prio\":\"normal\",\"cmd_id\":\"c-17\",\"status\":\"failed\",\"reason\":\"out_of_reach\",\"data\":{}}",
				event(result));
	}

	private static String event(Event event) {
		return event.toJson().toString();
	}
}
