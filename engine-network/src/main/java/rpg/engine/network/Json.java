package rpg.engine.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON support for the network protocol (Java 17 / Android-compatible).
 *
 * <p>Two halves:
 * <ul>
 *   <li>String builders — {@link #string(String)}, {@link #number(long)},
 *       {@link #array(String...)}, {@link #object(String...)} — used by the server to build
 *       the {@code UiLayout} layout document.</li>
 *   <li>Parser — {@link #parse(String)} / {@link #parseObject(String)} — used by clients to
 *       render a layout. Produces {@code Map<String,Object>}, {@code List<Object>}, String,
 *       Long, Double, Boolean and {@code null}.</li>
 * </ul>
 */
public final class Json {
    private Json() {}

    // ── Builder ────────────────────────────────────────────────────

    public static String string(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String number(long v) { return Long.toString(v); }

    public static String boolean_(boolean v) { return v ? "true" : "false"; }

    public static String array(String... items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(items[i]);
        }
        return sb.append(']').toString();
    }

    /** Quote an arbitrary value returned by {@link #parse}. */
    public static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return string(s);
        if (v instanceof Boolean b) return boolean_(b);
        if (v instanceof Long l) return number(l);
        if (v instanceof Double d) return Double.toString(d);
        if (v instanceof Number n) return n.toString();
        if (v instanceof List<?> list) {
            String[] items = new String[list.size()];
            for (int i = 0; i < list.size(); i++) items[i] = value(list.get(i));
            return array(items);
        }
        if (v instanceof Map<?, ?> map) {
            String[] kv = new String[map.size() * 2];
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                kv[i++] = String.valueOf(e.getKey()); // object() quotes keys itself
                kv[i++] = value(e.getValue());
            }
            return object(kv);
        }
        return string(String.valueOf(v));
    }

    /** Builds {@code {"k1":v1,"k2":v2,...}} — keys and values alternate, even length. */
    public static String object(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) throw new IllegalArgumentException("Keys and values must alternate");
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(string(keysAndValues[i])).append(':').append(keysAndValues[i + 1]);
        }
        return sb.append('}').toString();
    }

    // ── Parser ─────────────────────────────────────────────────────

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("Trailing characters in JSON");
        return v;
    }

    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return i >= s.length(); }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        char peek() { return s.charAt(i); }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            switch (peek()) {
                case '{' -> { return parseObjectBody(); }
                case '[' -> { return parseArray(); }
                case '"' -> { return parseString(); }
                case 't' -> { expectKeyword("true"); return Boolean.TRUE; }
                case 'f' -> { expectKeyword("false"); return Boolean.FALSE; }
                case 'n' -> { expectKeyword("null"); return null; }
                default -> { return parseNumber(); }
            }
        }

        void expectKeyword(String kw) {
            for (int k = 0; k < kw.length(); k++) {
                if (i >= s.length() || s.charAt(i) != kw.charAt(k))
                    throw new IllegalArgumentException("Bad JSON keyword");
                i++;
            }
        }

        Map<String, Object> parseObjectBody() {
            if (peek() != '{') throw new IllegalArgumentException("Expected '{'");
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (i < s.length() && peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (i >= s.length() || peek() != ':') throw new IllegalArgumentException("Expected ':'");
                i++;
                m.put(key, parseValue());
                skipWs();
                if (i >= s.length()) throw new IllegalArgumentException("Unterminated object");
                char c = peek();
                i++;
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}'");
            }
            return m;
        }

        List<Object> parseArray() {
            if (peek() != '[') throw new IllegalArgumentException("Expected '['");
            i++;
            List<Object> list = new ArrayList<>();
            skipWs();
            if (i < s.length() && peek() == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (i >= s.length()) throw new IllegalArgumentException("Unterminated array");
                char c = peek();
                i++;
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']'");
            }
            return list;
        }

        String parseString() {
            skipWs();
            if (i >= s.length() || peek() != '"') throw new IllegalArgumentException("Expected string");
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("Bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            skipWs();
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new IllegalArgumentException("Expected value");
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0)
                    return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number: " + num);
            }
        }
    }
}