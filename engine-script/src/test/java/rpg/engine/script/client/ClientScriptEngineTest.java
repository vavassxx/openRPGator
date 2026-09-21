package rpg.engine.script.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientScriptEngineTest {

    private static final class Sink implements ClientScriptEngine.Sink {
        final List<Integer> cmds = new ArrayList<>();
        final List<String> args = new ArrayList<>();
        final List<String> toasts = new ArrayList<>();
        List<Map<String, Object>> laid = List.of();
        List<String> strings = List.of();
        @Override public void sendCommand(int code, String arg) { cmds.add(code); args.add(arg); }
        @Override public void notify(String text) { toasts.add(text); }
        @Override public void layout(List<Map<String, Object>> widgets, List<String> strings) {
            laid = widgets;
            this.strings = strings;
        }
    }

    @Test
    void commandHandlerForwardsViaSend() {
        Sink sink = new Sink();
        ClientScriptEngine e = new ClientScriptEngine(sink);
        assertTrue(e.load("demo", "ui.on_command(function(cmd, arg) ui.send(cmd, arg) end)"));
        assertTrue(e.isLoaded());
        e.invokeCommand(9002, "potion");
        assertEquals(List.of(9002), sink.cmds);
        assertEquals(List.of("potion"), sink.args);
    }

    @Test
    void layoutPassesThroughWithoutHandlers() {
        Sink sink = new Sink();
        ClientScriptEngine e = new ClientScriptEngine(sink);
        e.load("demo", "return");
        List<Map<String, Object>> widgets = List.of(Map.of("type", "bar", "value", 42.0));
        List<String> strings = List.of("HP 42/100");
        e.onLayout(widgets, strings);
        assertEquals(widgets, e.renderedWidgets());
        assertEquals(strings, e.renderedStrings());
    }

    @Test
    void onLayoutHandlerOverridesServerLayout() {
        Sink sink = new Sink();
        ClientScriptEngine e = new ClientScriptEngine(sink);
        e.load("demo",
                "ui.on_layout(function(w, s) ui.layout({{type='text', ref=0}}, {'OVERRIDE'}) end)");
        e.onLayout(List.of(Map.of("type", "bar")), List.of("server"));
        assertEquals("OVERRIDE", e.renderedStrings().get(0));
        assertEquals("text", e.renderedWidgets().get(0).get("type"));
        assertEquals("OVERRIDE", sink.strings.get(0));
    }

    @Test
    void scriptCanIgnoreServerLayoutAndBuildScreens() {
        Sink sink = new Sink();
        ClientScriptEngine e = new ClientScriptEngine(sink);
        e.load("demo",
                "local open = false\n"
                        + "ui.on_layout(function(w, s) if not open then ui.layout(w, s) end end)\n"
                        + "ui.on_command(function(cmd, arg)\n"
                        + "  if cmd == 9001 then open = true; ui.layout({{type='panel', x=0.1}}, {'Inv'})\n"
                        + "  elseif cmd == 9003 then open = false; ui.clear() end\n"
                        + "end)\n");
        // Server HP layout while the screen is closed → passes through.
        e.onLayout(List.of(Map.of("type", "bar")), List.of("HP"));
        assertEquals("HP", e.renderedStrings().get(0));
        // Open the local inventory screen.
        e.invokeCommand(9001, "");
        assertEquals("Inv", e.renderedStrings().get(0));
        assertEquals("panel", e.renderedWidgets().get(0).get("type"));
        // A server HP tick while the screen is open must NOT clobber it.
        e.onLayout(List.of(Map.of("type", "bar")), List.of("HP"));
        assertEquals("Inv", e.renderedStrings().get(0));
        // Close → cleared until the next server layout arrives.
        e.invokeCommand(9003, "");
        assertTrue(e.renderedWidgets().isEmpty());
    }

    @Test
    void widgetsAndStringsComeBackAsJavaLists() {
        Sink sink = new Sink();
        ClientScriptEngine e = new ClientScriptEngine(sink);
        e.load("demo", "ui.layout({{type='button', x=0.26, y=0.09, w=0.11, h=0.05, cmd=9001},"
                + " {type='bar', value=95, max=100}}, {'Inv', 'HP'})");
        assertEquals(2, e.renderedWidgets().size());
        assertEquals("button", e.renderedWidgets().get(0).get("type"));
        assertEquals(9001, e.renderedWidgets().get(0).get("cmd"));
        assertEquals(95, e.renderedWidgets().get(1).get("value"));
        assertEquals(List.of("Inv", "HP"), e.renderedStrings());
    }

    @Test
    void dangerousLibsAreStripped() {
        Sink sink = new Sink();
        ClientScriptEngine e = new ClientScriptEngine(sink);
        assertFalse(e.load("bad", "local t = require('io')"));
        assertFalse(e.load("bad", "os.execute('echo hi')"));
        assertFalse(e.isLoaded());
    }

    @Test
    void notificationsStayLocal() {
        Sink sink = new Sink();
        ClientScriptEngine e = new ClientScriptEngine(sink);
        e.load("demo", "ui.notify('local toast')");
        assertEquals(List.of("local toast"), sink.toasts);
    }
}