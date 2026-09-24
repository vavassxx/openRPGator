package rpg.engine.script.lua;

import org.junit.jupiter.api.*;
import rpg.engine.core.component.Name;
import rpg.engine.core.component.Transform;
import rpg.engine.core.component.Trigger;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.*;
import rpg.engine.script.UiSink;
import rpg.engine.world.GameWorld;
import rpg.engine.world.TriggerEnterEvent;
import rpg.engine.world.TriggerExitEvent;
import org.luaj.vm2.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LuaApiTest {

    private LuaRuntime scripts;
    private LuaApi api;
    private GameWorld world;
    private org.luaj.vm2.Globals globals;

    @BeforeEach
    void setUp() {
        scripts = new LuaRuntime();
        api = scripts.api();
        world = new GameWorld();
        scripts.bindWorld(world);
        globals = scripts.globals();
        bindMapWithEntities();
    }

    // ── engine.tick ───────────────────────────────────────────────
    @Test
    void tickValueIsUpdated() {
        api.setTick(42);
        assertEquals(42, globals.get("engine").get("tick").call().toint());
    }

    // ── world operations ──────────────────────────────────────────
    @Test
    void spawnAndCount() {
        assertEquals(0, world.entities().entities().size());
        scripts.execute("world.spawn('a')", "spawn1");
        assertEquals(1, world.entities().entities().size());
    }

    @Test
    void destroyByName() {
        scripts.execute("world.spawn('victim')", "s");
        assertEquals(1, world.entities().entities().size());
        scripts.execute("world.destroy('victim')", "d");
        assertEquals(0, world.entities().entities().size());
    }

    @Test
    void findByName() {
        scripts.execute("world.spawn('target')", "s");
        scripts.execute(
                "local e = world.find('target'); assert(e ~= nil and e.name() == 'target')", "find_test");
    }

    @Test
    void getByNameFallback() {
        scripts.execute("world.spawn('named')", "s");
        scripts.execute(
                "local e = world.get('named'); assert(e ~= nil); assert(e.name() == 'named')", "get_test");
    }

    // ── engine.on_tick dispatch ────────────────────────────────────
    @Test
    void globalOnTickDispatch() {
        scripts.execute("flag = 0; engine.on_tick(function(t) flag = flag + 1 end)", "init");
        api.setTick(1); api.dispatchTick();
        api.setTick(2); api.dispatchTick();
        assertEquals(2, globals.get("flag").toint());
    }

    // ── per-entity on_tick ─────────────────────────────────────────
    @Test
    void entityOnTickDispatch() {
        EntityId id = world.spawn();
        world.entities().set(id, new Name("ticker"));
        world.entities().set(id, new Transform(new WorldPosition(0, 0, 0), 0));
        api.executeOwned(globals,
                "count = 0; entity.on_tick(function(me) count = count + 1 end)", "etick", id);
        for (int i = 0; i < 5; i++) { api.setTick(i); api.dispatchTick(); }
        assertEquals(5, globals.get("count").toint());
    }

    // ── trigger events ─────────────────────────────────────────────
    @Test
    void onEnterHandlerFires() {
        var triggerE = world.spawn();
        var actorE = world.spawn();
        world.entities().set(triggerE, new Trigger(5.0));
        world.entities().set(triggerE, new Name("zone"));
        world.entities().set(actorE, new Name("actor"));
        world.entities().set(actorE, new Transform(new WorldPosition(0, 0, 0), 0));
        globals.set("entered", LuaValue.valueOf(0));
        api.executeOwned(globals,
                "entity.on_enter(function(me) entered = entered + 1 end)", "bind", triggerE);
        world.events().emit(new TriggerEnterEvent(actorE, triggerE));
        assertEquals(1, globals.get("entered").toint());
    }

    @Test
    void onExitHandlerFires() {
        var triggerE = world.spawn();
        var actorE = world.spawn();
        world.entities().set(triggerE, new Trigger(5.0));
        world.entities().set(triggerE, new Name("zone"));
        world.entities().set(actorE, new Name("actor"));
        world.entities().set(actorE, new Transform(new WorldPosition(0, 0, 0), 0));
        globals.set("exited", LuaValue.valueOf(0));
        api.executeOwned(globals,
                "entity.on_exit(function(me) exited = exited + 1 end)", "bind", triggerE);
        world.events().emit(new TriggerExitEvent(actorE, triggerE));
        assertEquals(1, globals.get("exited").toint());
    }

    // ── tile access ────────────────────────────────────────────────
    @Test
    void tileReadAndWrite() {
        scripts.execute("t = world.tile(0, 0)", "read");
        assertEquals(0, globals.get("t").toint());
        scripts.execute("world.set_tile(1, 2, 42); t = world.tile(1, 2)", "write");
        assertEquals(42, globals.get("t").toint());
    }

    // ── entity scale ───────────────────────────────────────────────
    @Test
    void entityScaleDefaultsToOneAndSettable() {
        scripts.execute(
                "local e = world.spawn('scaled');"
                + " assert(e.scale() == 1.0);"
                + " e.set_scale(2.5); assert(e.scale() == 2.5);"
                + " e.set_scale(0.1); assert(e.scale() == 0.1)", "scale_test");
    }

    // ── entity / self scoped binding ───────────────────────────────
    @Test
    void entityAndSelfBoundDuringScript() {
        EntityId id = world.spawn();
        world.entities().set(id, new Name("box"));
        world.entities().set(id, new Transform(new WorldPosition(5, 10, 3), 0));
        api.executeOwned(globals,
                "scoped_name = self.name(); scoped_id = entity.id()", "scoped", id);
        assertEquals("box", globals.get("scoped_name").tojstring());
        assertEquals(id.value(), globals.get("scoped_id").tolong());
        assertTrue(globals.get("entity").isnil());
        assertTrue(globals.get("self").isnil());
    }

    // ── trigger system через GameWorld.step ────────────────────────
    @Test
    void triggerSystemEmitsEnterViaStep() {
        var triggerE = world.spawn();
        var actorE = world.spawn();
        world.entities().set(triggerE, new Trigger(3.0));
        world.entities().set(triggerE, new Name("zone"));
        world.entities().set(actorE, new Name("actor"));
        world.entities().set(actorE, new Transform(new WorldPosition(1, 0, 0), 0));
        // actor wanders into trigger radius
        world.entities().set(triggerE, new Transform(new WorldPosition(0, 0, 0), 0));
        world.entities().set(actorE, new Transform(new WorldPosition(0.5, 0, 0), 0));
        globals.set("entered", LuaValue.valueOf(0));
        api.executeOwned(globals,
                "entity.on_enter(function(me) entered = entered + 1 end)", "bind", triggerE);
        world.step();
        assertEquals(1, globals.get("entered").toint());
    }

    // ── helpers ────────────────────────────────────────────────────
    private void bindMapWithEntities() {
        RMap map = new RMap("test", 32, 8, 8,
                List.of(new TileLayer("g", 8, 8, new int[64], false)),
                List.of());
        scripts.bindMap(map);
    }

    // ── push UI: engine.notify / engine.dialog / engine.layout ─────
    private static final class RecordingSink implements UiSink {
        final List<String> broadcasts = new ArrayList<>();
        final List<DialogCall> dialogs = new ArrayList<>();
        final List<LayoutCall> layouts = new ArrayList<>();
        final List<ScriptCall> scripts = new ArrayList<>();

        record DialogCall(long dialogId, long playerId, String text, List<String> choices, DialogCallback cb) {}
        record LayoutCall(long playerId, String layoutJson, List<String> strings) {}
        record ScriptCall(long playerId, String name, String source) {}

        @Override public void broadcastNotify(String text) { broadcasts.add(text); }
        @Override public void notifyTo(long playerEntityId, String text) {}
        @Override public void dialogTo(long playerEntityId, long dialogId, String text, List<String> choices, DialogCallback callback) {
            dialogs.add(new DialogCall(dialogId, playerEntityId, text, choices, callback));
        }
        @Override public void clearDialogs(long playerEntityId) {}
        @Override public void layoutTo(long playerEntityId, String layoutJson, List<String> strings) {
            layouts.add(new LayoutCall(playerEntityId, layoutJson, strings));
        }
        @Override public void scriptTo(long playerEntityId, String name, String source) {
            scripts.add(new ScriptCall(playerEntityId, name, source));
        }
    }

    @Test
    void notifyBroadcasts() {
        RecordingSink sink = new RecordingSink();
        api.setUiSink(sink);
        scripts.execute("engine.notify('hello world')", "notify_test");
        assertEquals(List.of("hello world"), sink.broadcasts);
    }

    @Test
    void layoutSendsWidgetSchemaWithStrings() {
        RecordingSink sink = new RecordingSink();
        api.setUiSink(sink);
        EntityId player = world.spawn();
        world.entities().set(player, new Name("player"));
        world.entities().set(player, new Transform(new WorldPosition(0, 0, 0), 0));
        scripts.execute(
                "engine.layout(world.get('player'), " +
                        "{{ type='bar', x=0.01, y=0.1, w=0.16, h=0.03, value=95, max=100 }," +
                        " { type='text', x=0.19, y=0.1, ref=1, color={1,1,1} }}, " +
                        "{'Здоровье', 'HP 95/100'})",
                "layout_test");
        assertEquals(1, sink.layouts.size());
        var l = sink.layouts.get(0);
        assertEquals(player.value(), l.playerId());
        assertEquals(List.of("Здоровье", "HP 95/100"), l.strings());
        assertTrue(l.layoutJson().contains("\"value\":95"), l.layoutJson());
        assertTrue(l.layoutJson().contains("\"max\":100"), l.layoutJson());
        assertTrue(l.layoutJson().contains("\"ref\":1"), l.layoutJson());
    }

    @Test
    void sendScriptRoutesToSink() {
        RecordingSink sink = new RecordingSink();
        api.setUiSink(sink);
        EntityId player = world.spawn();
        world.entities().set(player, new Name("player"));
        world.entities().set(player, new Transform(new WorldPosition(0, 0, 0), 0));
        scripts.execute(
                "engine.send_script(world.get('player'), 'ui.notify(1)', 'sandbox-demo')", "send_script_test");
        assertEquals(1, sink.scripts.size());
        var sc = sink.scripts.get(0);
        assertEquals(player.value(), sc.playerId());
        assertEquals("sandbox-demo", sc.name());
        assertEquals("ui.notify(1)", sc.source());
    }

    @Test
    void onCommandDispatchesToRegisteredHandler() {
        EntityId player = world.spawn();
        world.entities().set(player, new Name("player"));
        world.entities().set(player, new Transform(new WorldPosition(0, 0, 0), 0));
        scripts.execute(
                "last_cmd = -1; last_arg = ''; last_pid = -1;"
                        + " engine.on_command(function(p, code, arg) last_cmd = code; last_arg = arg; last_pid = p.id() end)",
                "cmd_test");
        api.dispatchCommand(player.value(), 9002, "sword");
        assertEquals(9002, globals.get("last_cmd").toint());
        assertEquals("sword", globals.get("last_arg").tojstring());
        assertEquals(player.value(), globals.get("last_pid").tolong());
    }

    @Test
    void playersAreHostProvided() {
        EntityId p1 = world.spawn();
        EntityId p2 = world.spawn();
        EntityId bystander = world.spawn();
        api.setPlayers(List.of(p1.value(), p2.value()));
        LuaValue n = globals.load("return #world.players()", "players_count").call();
        assertEquals(2, n.toint());
    }

    @Test
    void dialogRegistersCallbackAndResponds() {
        RecordingSink sink = new RecordingSink();
        api.setUiSink(sink);
        EntityId player = world.spawn();
        world.entities().set(player, new Name("player"));
        world.entities().set(player, new Transform(new WorldPosition(0, 0, 0), 0));
        scripts.execute(
                "answer = -1; engine.dialog(world.get('player'), 'choose', {'a','b'}, function(idx) answer = idx end)",
                "dialog_test");
        assertEquals(1, sink.dialogs.size());
        var d = sink.dialogs.get(0);
        assertEquals(player.value(), d.playerId());
        assertEquals("choose", d.text());
        assertEquals(List.of("a", "b"), d.choices());
        d.cb().onAnswer(1);
        assertEquals(2, globals.get("answer").toint());
    }

    @Test
    void dialogWithoutSinkIsNoop() {
        scripts.execute("engine.dialog(world.get('nosuch'), 'choose', {'a'}, function() end)", "dialog_noop");
        assertTrue(true);
    }
}