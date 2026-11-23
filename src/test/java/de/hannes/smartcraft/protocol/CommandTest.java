package de.hannes.smartcraft.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandTest {
	@Test
	void parsesCommandWithAndWithoutArgs() throws Exception {
		Command withArgs = Command.parse("{\"v\":1,\"type\":\"command\",\"id\":\"c-17\",\"name\":\"mine\",\"args\":{\"pos\":[1,2,3]}}");
		assertEquals("c-17", withArgs.id());
		assertEquals("mine", withArgs.name());
		assertEquals("{\"pos\":[1,2,3]}", withArgs.args().toString());

		Command withoutArgs = Command.parse("{\"v\":1,\"type\":\"command\",\"id\":\"c-1\",\"name\":\"ping\"}");
		assertEquals("{}", withoutArgs.args().toString());
	}

	@Test
	void rejectsBrokenInputWithReason() {
		assertReason("invalid_json", "{not json");
		assertReason("not_an_object", "[1,2]");
		assertReason("unsupported_version", "{\"v\":2,\"type\":\"command\",\"id\":\"a\",\"name\":\"ping\"}");
		assertReason("unsupported_version", "{\"type\":\"command\",\"id\":\"a\",\"name\":\"ping\"}");
		assertReason("not_a_command", "{\"v\":1,\"type\":\"event\",\"id\":\"a\",\"name\":\"ping\"}");
		assertReason("invalid_id", "{\"v\":1,\"type\":\"command\",\"id\":\"\",\"name\":\"ping\"}");
		assertReason("invalid_id", "{\"v\":1,\"type\":\"command\",\"id\":7,\"name\":\"ping\"}");
		assertReason("invalid_name", "{\"v\":1,\"type\":\"command\",\"id\":\"a\",\"name\":\"Go To\"}");
		assertReason("invalid_args", "{\"v\":1,\"type\":\"command\",\"id\":\"a\",\"name\":\"ping\",\"args\":[1]}");
	}

	private static void assertReason(String expected, String line) {
		Command.InvalidCommandException error = assertThrows(Command.InvalidCommandException.class, () -> Command.parse(line));
		assertEquals(expected, error.getMessage());
	}
}
