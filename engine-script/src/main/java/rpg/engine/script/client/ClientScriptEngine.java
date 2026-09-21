package rpg.engine.script.client;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Mini-sandbox": the restricted Lua runtime that server-pushed UI scripts (packet {@code Script})
 * execute in on the <em>client</em>.
 *
 * <p>The host owns all meaning — the client remains a dumb renderer. The script only gets a tiny
 * {@code ui.*} API: rebuild the widget overlay ({@code ui.layout}), forward custom commands to the
 * server ({@code ui.send}), react to button presses ({@code ui.on_command}), be consulted when the
 * server pushes a layout ({@code ui.on_layout}) and show local toasts ({@code ui.notify}).
 *
 * <p>This is a real sandbox, not the engine's server runtime: dangerous libs ({@code io}, {@code os},
 * {@code package}, {@code debug}, {@code luajava}, file loaders) are stripped from the globals — a
 * pushed script cannot touch the filesystem or the host. All entry points are synchronized; on
 * desktop the network thread and the render/input thread may interleave them, so a single LuaJ
 * Globals instance is only ever used by one thread at a time.
 */
public final class ClientScriptEngine {
    /** Where the script's side effects land. Implemented by the embedding client. */
    public interface Sink {
        /** Forward a custom command to the server (wire packet {@code Cmd}). */
        void sendCommand(int code, String arg);
        /** Local toast/message (never leaves the client). */
        void notify(String text);
        /** Replace the widget overlay the client renders. */
        void layout(List<Map<String, Object>> widgets, List<String> strings);
    }

    private final Globals globals;
    private final Sink sink;
    private final List<LuaFunction> commandHandlers = new ArrayList<>();
    private final List<LuaFunction> layoutHandlers = new ArrayList<>();
    private volatile boolean loaded;
    private volatile List<Map<String, Object>> renderedWidgets = List.of();
    private volatile List<String> renderedStrings = List.of();

    public ClientScriptEngine(Sink sink) {
        this.sink = sink;
        globals = JsePlatform.standardGlobals();
        // Strip everything a pushed script must not touch: file/process/system access.
        globals.set("io", LuaValue.NIL);
        globals.set("os", LuaValue.NIL);
        globals.set("package", LuaValue.NIL);
        globals.set("debug", LuaValue.NIL);
        globals.set("require", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        globals.set("luajava", LuaValue.NIL);
        LuaTable ui = new LuaTable();
        ui.set("send", new TwoArgFunction() {
            public LuaValue call(LuaValue code, LuaValue arg) {
                sink.sendCommand(code.checkint(), arg.optjstring(""));
                return NONE;
            }
        });
        ui.set("notify", new OneArgFunction() {
            public LuaValue call(LuaValue text) { sink.notify(text.optjstring("")); return NONE; }
        });
        ui.set("layout", new TwoArgFunction() {
            public LuaValue call(LuaValue widgets, LuaValue strings) {
                List<Map<String, Object>> w = toJavaWidgets(widgets);
                List<String> s = toJavaStrings(strings);
                renderedWidgets = w;
                renderedStrings = s;
                sink.layout(w, s);
                return NONE;
            }
        });
        ui.set("clear", new ZeroArgFunction() {
            public LuaValue call() {
                renderedWidgets = List.of();
                renderedStrings = List.of();
                sink.layout(renderedWidgets, renderedStrings);
                return NONE;
            }
        });
        ui.set("on_command", new OneArgFunction() {
            public LuaValue call(LuaValue fn) {
                if (fn.isfunction()) commandHandlers.add((LuaFunction) fn);
                return NONE;
            }
        });
        ui.set("on_layout", new OneArgFunction() {
            public LuaValue call(LuaValue fn) {
                if (fn.isfunction()) layoutHandlers.add((LuaFunction) fn);
                return NONE;
            }
        });
        globals.set("ui", ui);
    }

    public boolean isLoaded() { return loaded; }

    /** Compiles and runs the pushed chunk. Clears previous handlers and screen state. */
    public synchronized boolean load(String name, String source) {
        commandHandlers.clear();
        layoutHandlers.clear();
        renderedWidgets = List.of();
        renderedStrings = List.of();
        try {
            globals.load(source == null ? "" : source,
                    name == null || name.isBlank() ? "client" : name).call();
            loaded = true;
            return true;
        } catch (LuaError e) {
            System.err.println("[ClientScript] " + name + " failed: " + e.getMessage());
            loaded = false;
            return false;
        }
    }

    /**
     * A server {@code UiLayout kind="layout"} arrived. Without {@code ui.on_layout} handlers the
     * layout passes through untouched (dumb renderer); with handlers, the script decides what to
     * do — e.g. re-apply ({@code ui.layout}), ignore while its own screen is open, or rebuild.
     */
    public synchronized void onLayout(List<Map<String, Object>> widgets, List<String> strings) {
        if (layoutHandlers.isEmpty()) {
            renderedWidgets = widgets;
            renderedStrings = strings;
            return;
        }
        LuaValue w = toLua(widgets);
        LuaValue s = toLua(strings);
        for (LuaFunction fn : List.copyOf(layoutHandlers)) {
            safeInvoke("ui.on_layout", fn, LuaValue.varargsOf(new LuaValue[]{w, s}));
        }
    }

    /** A widget button with a {@code cmd} was pressed on the client. */
    public synchronized void invokeCommand(int code, String arg) {
        for (LuaFunction fn : List.copyOf(commandHandlers)) {
            safeInvoke("ui.on_command", fn, LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(code), LuaValue.valueOf(arg == null ? "" : arg)}));
        }
    }

    /** The widget overlay the client must render right now. */
    public List<Map<String, Object>> renderedWidgets() { return renderedWidgets; }
    /** The strings referenced by {@link #renderedWidgets()}. */
    public List<String> renderedStrings() { return renderedStrings; }

    private void safeInvoke(String what, LuaFunction fn, Varargs args) {
        try {
            fn.invoke(args);
        } catch (LuaError e) {
            System.err.println("[ClientScript] " + what + " handler failed: " + e.getMessage());
        } catch (Throwable t) {
            System.err.println("[ClientScript] " + what + " handler failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toJavaWidgets(LuaValue v) {
        Object o = toJava(v);
        if (o instanceof List) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object e : (List<Object>) o) {
                if (e instanceof Map) out.add((Map<String, Object>) e);
            }
            return out;
        }
        return List.of();
    }

    private static List<String> toJavaStrings(LuaValue v) {
        Object o = toJava(v);
        if (o instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object e : (List<Object>) o) out.add(e == null ? "" : e.toString());
            return out;
        }
        return List.of();
    }

    /** Lua table (consecutive int keys) → Java list, otherwise → ordered map. Same shape as the
     *  {@code engine.layout} JSON the client parses, so widget schemas are interchangeable. */
    @SuppressWarnings("unchecked")
    private static Object toJava(LuaValue v) {
        if (v.isnil()) return null;
        if (v.isboolean()) return v.toboolean();
        if (v.isinttype()) return v.toint();
        if (v.isnumber()) return v.todouble();
        if (v.isstring()) return v.tojstring();
        if (v.istable()) {
            LuaTable t = v.checktable();
            int len = t.length();
            boolean array = true;
            for (int i = 1; i <= len; i++) {
                if (t.get(i).isnil()) { array = false; break; }
            }
            if (array && len > 0) {
                List<Object> out = new ArrayList<>(len);
                for (int i = 1; i <= len; i++) out.add(toJava(t.get(i)));
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (LuaValue k : t.keys()) out.put(k.tojstring(), toJava(t.get(k)));
            return out;
        }
        return null;
    }

    private static LuaValue toLua(Object o) {
        if (o instanceof List) {
            LuaTable t = new LuaTable();
            int i = 1;
            for (Object e : (List<?>) o) t.set(i++, toLua(e));
            return t;
        }
        if (o instanceof Map) {
            LuaTable t = new LuaTable();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) t.set(e.getKey().toString(), toLua(e.getValue()));
            return t;
        }
        if (o instanceof Boolean b) return LuaValue.valueOf(b);
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) ? LuaValue.valueOf((long) d) : LuaValue.valueOf(d);
        }
        return o instanceof String s ? LuaValue.valueOf(s) : LuaValue.NIL;
    }
}