package rpg.engine.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void escapeRoundTrip() {
        String raw = "Привет, \"мир\"!\nТаб\t и \\ слэж";
        String built = Json.string(raw);
        assertEquals(raw, Json.parse(built));
    }

    @Test
    void parsesObjectGraph() {
        String json = Json.object(
                "a", Json.string("x"),
                "b", Json.number(5),
                "c", Json.boolean_(true),
                "d", Json.array(Json.string("1"), Json.number(2)),
                "e", Json.object("n", Json.number(0)));
        Map<String, Object> m = Json.parseObject(json);
        assertEquals("x", m.get("a"));
        assertEquals(5L, m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
        assertEquals(List.of("1", 2L), m.get("d"));
        assertEquals(Map.of("n", 0L), m.get("e"));
    }

    @Test
    void parsesUnicodeEscapesAndNumbers() {
        Map<String, Object> m = Json.parseObject("{\"n\":-12.5e2,\"u\":\"\\u0430\\u0431\"}");
        assertEquals(-1250.0, m.get("n"));
        assertEquals("аб", m.get("u"));
    }

    @Test
    void parsesEmptyContainersAndNull() {
        assertTrue(Json.parseObject("{}").isEmpty());
        assertTrue(Json.parse("[]") instanceof List<?> list && list.isEmpty());
        assertNull(Json.parseObject("{\"k\":null}").get("k"));
        Map<String, Object> m = Json.parseObject("{\"a\":[1,true]}");
        assertEquals(List.of(1L, Boolean.TRUE), m.get("a"));
    }

    @Test
    void valueRequotesParsedGraph() {
        Object v = Json.parse("{\"a\":[1,\"x\",null,\"ю\"]}");
        String re = Json.value(v);
        assertEquals(v, Json.parse(re));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} extra"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
    }
}