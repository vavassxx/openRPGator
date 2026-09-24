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
 *   {"kind":"layout","id":-1,"layout":[{...widget...}],"strings":["HP 95/100"]}
 * </pre>
 *
 * <p>{@code kind="layout"} is the generic, host-driven widget surface: Lua scripts on the
 * server build a JSON <em>array</em> of widget descriptors (see {@link #layout(String, List)}
 * for the widget schema) and ship it together with the {@code strings} the widgets reference
 * by index. The client is a dumb renderer — it knows the widget schema, never the game
 * meaning behind it (HP, mana, quests are all just bars/labels assembled by the host).
 */
public record UiLayout(String kind, long dialogId, String json) implements Packet {

    public static final String KIND_NOTIFY = "notify";
    public static final String KIND_DIALOG = "dialog";
    /** Generic host-driven widget surface (array of widget objects + strings). */
    public static final String KIND_LAYOUT = "layout";

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

    /**
     * Generic host-driven widget surface.
     *
     * <p>{@code layoutJson} is a JSON <em>array</em> of widget descriptors; text widgets refer
     * to entries of {@code strings} by index ({@code "ref"}). Position/size fields are
     * fractions of the viewport (0..1); colors are RGB(A) arrays with components 0..1:
     * <pre>
     *   {"type":"panel","x":..,"y":..,"w":..,"h":..,"bg":[r,g,b,a]}
     *   {"type":"bar","x":..,"y":..,"w":..,"h":..,"value":n,"max":m,"fill":[r,g,b],"back":[r,g,b]}
     *   {"type":"text","x":..,"y":..,"ref":idx,"size":dp,"color":[r,g,b]}
     * </pre>
     */
    public static UiLayout layout(String layoutJson, List<String> strings) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < strings.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.string(strings.get(i)));
        }
        String json = Json.object(
                "kind", Json.string(KIND_LAYOUT),
                "id", Json.number(-1),
                "layout", layoutJson,
                "strings", sb.append(']').toString());
        return new UiLayout(KIND_LAYOUT, -1, json);
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

    /**
     * Widget descriptors of a {@link #KIND_LAYOUT} document (empty otherwise). Each entry is a
     * {@code Map} with {@code type}, geometry and value fields per the layout schema.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> layoutWidgets() {
        Object layout = object().get("layout");
        if (layout instanceof List) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : (List<Object>) layout) {
                if (o instanceof Map) out.add((Map<String, Object>) o);
            }
            return out;
        }
        return List.of();
    }
}