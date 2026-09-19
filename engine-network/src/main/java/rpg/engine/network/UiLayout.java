package rpg.engine.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server→client UI layout packet (protocol type 10).
 *
 * <p>Replaces the hand-rolled {@link Notify}/{@link Dialog} pair with the "layout + strings"
 * model: the server sends a JSON document describing the UI layout — whose string slots are
 * <em>index references</em> into the {@code strings} array — together with the actual strings.
 * The client is a dumb renderer: it lays out widgets per {@code layout} and fills text from
 * {@code strings}.
 *
 * <p>Document shape:
 * <pre>
 *   {"kind":"dialog","id":3,"layout":{"body":0,"choices":[1,2]},"strings":["text","A","B"]}
 *   {"kind":"notify","id":-1,"layout":{"body":0},"strings":["toast"]}
 * </pre>
 */
public record UiLayout(String kind, long dialogId, String json) implements Packet {

    public static final String KIND_NOTIFY = "notify";
    public static final String KIND_DIALOG = "dialog";

    public byte type() { return 10; }

    public static UiLayout notify(String text) {
        String json = Json.object(
                "kind", Json.string(KIND_NOTIFY),
                "id", Json.number(-1),
                "layout", Json.object("body", Json.number(0)),
                "strings", Json.array(Json.string(text)));
        return new UiLayout(KIND_NOTIFY, -1, json);
    }

    public static UiLayout dialog(long dialogId, String text, List<String> choices) {
        StringBuilder layout = new StringBuilder("{\"body\":").append(Json.number(0)).append(",\"choices\":[");
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) layout.append(',');
            layout.append(Json.number(i + 1));
        }
        layout.append("]}");
        StringBuilder strings = new StringBuilder("[");
        strings.append(Json.string(text));
        for (String c : choices) strings.append(',').append(Json.string(c));
        strings.append(']');
        String json = Json.object(
                "kind", Json.string(KIND_DIALOG),
                "id", Json.number(dialogId),
                "layout", layout.toString(),
                "strings", strings.toString());
        return new UiLayout(KIND_DIALOG, dialogId, json);
    }

    /** Parsed document (see class javadoc for shape). */
    public Map<String, Object> object() { return Json.parseObject(json); }

    /** Strings referenced by the layout. */
    @SuppressWarnings("unchecked")
    public List<String> strings() {
        Object strings = object().get("strings");
        if (!(strings instanceof List)) return List.of();
        return (List<String>) strings;
    }

    /** Body string index from the layout (default 0). */
    @SuppressWarnings("unchecked")
    public int bodyIndex() {
        Object layout = object().get("layout");
        if (layout instanceof Map) {
            Object body = ((Map<String, Object>) layout).get("body");
            if (body instanceof Number n) return n.intValue();
        }
        return 0;
    }

    /** Choice string indices from the layout (empty when the dialog has no choices). */
    @SuppressWarnings("unchecked")
    public List<Integer> choiceIndices() {
        Object layout = object().get("layout");
        if (layout instanceof Map) {
            Object choices = ((Map<String, Object>) layout).get("choices");
            if (choices instanceof List) {
                List<Integer> out = new ArrayList<>();
                for (Object o : (List<Object>) choices) {
                    if (o instanceof Number n) out.add(n.intValue());
                }
                return out;
            }
        }
        return List.of();
    }

    /** Resolved body text. */
    public String bodyText() {
        List<String> strings = strings();
        int idx = bodyIndex();
        return idx >= 0 && idx < strings.size() ? strings.get(idx) : "";
    }

    /** Resolved choice texts in layout order. */
    public List<String> choiceTexts() {
        List<String> strings = strings();
        List<String> out = new ArrayList<>();
        for (int idx : choiceIndices()) {
            if (idx >= 0 && idx < strings.size()) out.add(strings.get(idx));
        }
        return out;
    }
}