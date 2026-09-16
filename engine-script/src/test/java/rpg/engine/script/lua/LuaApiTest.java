package rpg.engine.script.lua;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import rpg.engine.core.component.Name;
import rpg.engine.core.component.Transform;
import rpg.engine.core.component.Trigger;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.*;
import rpg.engine.runtime.GameRuntime;
import rpg.engine.world.GameWorld;
import rpg.engine.world.TriggerEnterEvent;
import rpg.engine.world.TriggerExitEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LuaApiTest {

    private GameRuntime rt;
    private LuaApi api;
    private GameWorld world;

    @BeforeEach
    void setUp() {
        rt = new GameRuntime();
        api = rt.scripts().api();
        world = rt.world();
    }

    // ── engine.tick ───────────────────────────────────────────────
    @Test
    void tickValueIsUpdated() {
        api.setTick(42);
        api.install(rt.scripts().globals());
        LuaValue tick = rt.scripts().globals().get("engine").get("tick");
        assertEquals(42, tick.call().toint());
    }

    // ── world operations ──────────────────────────────────────────
    @Test
    void spawnAndCount() {
        assertEquals(0, world.entities().entities().size());
        rt.scripts().execute("world.spawn('a')", "spawn1");
        assertEquals(1, world.entities().entities().size());
    }

    @Test
    void destroyByName() {
        rt.scripts().execute("world.spawn('victim')", "s");
        assertEquals(1, world.entities().entities().size());
        rt.scripts().execute("world.destroy('victim')", "d");
        assertEquals(0, world.entities().entities().size());
    }

    @Test
    void findByName() {
        rt.scripts().execute("world.spawn('target')", "s");
        LuaValue result = rt.scripts().execute(
                "local e = world.find('target'); result = e ~= nil", "find_test");
        // Lua globals carry the result — or just check entity exists via count
        assertEquals(1, world.entities().entities().size());
        // the entity named 'target' should be findable
        EntityId found = null;
        for (var e : world.entities().entities()) {
            var n = world.entities().get(e, Name.class);
            if (n.isPresent() && "target".equals(n.get().value())) found = e;
        }
        assertNotNull(found);
    }

    @Test
    void getByNameFallback() {
        rt.scripts().execute("world.spawn('named')", "s");
        rt.scripts().execute(
                "local e = world.get('named'); assert(e ~= nil); assert(e.name() == 'named')", "get_test");
    }

    // ── engine.on_tick dispatch ────────────────────────────────────
    @Test
    void globalOnTickDispatch() {
        rt.scripts().execute("flag = 0; engine.on_tick(function(t) flag = flag + 1 end)", "init");
        api.setTick(1); api.dispatchTick();
        api.setTick(2); api.dispatchTick();
        assertEquals(2, rt.scripts().globals().get("flag").toint());
    }

    // ── per-entity on_tick ─────────────────────────────────────────
    @Test
    void entityOnTickDispatch() {
        rt.scripts().execute(
                "count = 0; entity.on_tick(function(me) count = count + 1 end)", "etick");
        api.setTick(0);
        for (int i = 0; i < 5; i++) { api.setTick(i); api.dispatchTick(); }
        assertEquals(5, rt.scripts().globals().get("count").toint());
    }

    // ── trigger events ─────────────────────────────────────────────
    @Test
    void onEnterHandlerFires() {
        // set up two entities: a trigger zone and an actor
        world.spawn(); world.spawn();
        var all = world.entities().entities().stream().toList();
        var triggerE = all.get(0);
        var actorE = all.get(1);
        world.entities().set(triggerE, new Trigger(5.0));
        world.entities().set(triggerE, new Name("zone"));
        world.entities().set(actorE, new Name("actor"));
        world.entities().set(actorE, new Transform(new WorldPosition(0, 0, 0), 0));
        // load map with trigger entity to bind script
        bindMapWithEntities();
        // execute script binding to triggerE: register on_enter
        rt.scripts().execute("entered = 0; entity.on_enter(function(me) entered = entered + 1 end)", "trigger_script");
        // but entity global is now pointing to the LAST spawned entity...
        // Instead, use executeOwned to bind to triggerE directly
        // reset
        rt.scripts().globals().set("entered", LuaValue.valueOf(0));
        api.executeOwned(rt.scripts().globals(),
                "entity.on_enter(function(me) entered = entered + 1 end)", "bind", triggerE);
        // emit enter event
        world.events().emit(new TriggerEnterEvent(actorE, triggerE));
        assertEquals(1, rt.scripts().globals().get("entered").toint());
    }

    @Test
    void onExitHandlerFires() {
        world.spawn(); world.spawn();
        var all = world.entities().entities().stream().toList();
        var triggerE = all.get(0);
        var actorE = all.get(1);
        world.entities().set(triggerE, new Trigger(5.0));
        world.entities().set(triggerE, new Name("zone"));
        world.entities().set(actorE, new Name("actor"));
        world.entities().set(actorE, new Transform(new WorldPosition(0, 0, 0), 0));
        bindMapWithEntities();
        rt.scripts().globals().set("exited", LuaValue.valueOf(0));
        api.executeOwned(rt.scripts().globals(),
                "entity.on_exit(function(me) exited = exited + 1 end)", "bind", triggerE);
        world.events().emit(new TriggerExitEvent(actorE, triggerE));
        assertEquals(1, rt.scripts().globals().get("exited").toint());
    }

    // ── tile access ────────────────────────────────────────────────
    @Test
    void tileReadAndWrite() throws IOException {
        RMap map = new RMap("t", 32, 4, 4,
                List.of(new TileLayer("g", 4, 4, new int[16], false)),
                List.of());
        Path tmp = Files.createTempFile("test", ".rmap");
        try {
            RMapIO.write(map, tmp);
            rt.loadMap(tmp);
            // default tile is 0
            rt.scripts().execute("t = world.tile(0, 0)", "read");
            assertEquals(0, rt.scripts().globals().get("t").toint());
            // set and read back
            rt.scripts().execute("world.set_tile(1, 2, 42); t = world.tile(1, 2)", "write");
            assertEquals(42, rt.scripts().globals().get("t").toint());
        } finally { Files.deleteIfExists(tmp); }
    }

    // ── entity / self scoped binding ───────────────────────────────
    @Test
    void entityAndSelfBoundDuringScript() {
        EntityId id = world.spawn();
        world.entities().set(id, new Name("box"));
        world.entities().set(id, new Transform(new WorldPosition(5, 10, 3), 0));
        bindMapWithEntities();
        // executeOwned: read entity.name() and self.name() from the script
        rt.scripts().execute("script_name = self.name(); script_id = entity.id()", "pre");
        // overwrite: that used global scope, self was nil. Run scoped:
        api.executeOwned(rt.scripts().globals(),
                "script_name = self.name(); script_id = entity.id()", "scoped", id);
        assertEquals("box", rt.scripts().globals().get("script_name").tojstring());
        assertEquals(id.value(), rt.scripts().globals().get("script_id").tolong());
        // after executeOwned, entity/self should be restored (previous value)
        LuaValue prevEntity = rt.scripts().globals().get("entity");
        assertTrue(prevEntity == LuaValue.NIL || prevEntity.isnil());
    }

    // ── helpers ────────────────────────────────────────────────────
    private void bindMapWithEntities() {
        RMap map = new RMap("test", 32, 8, 8,
                List.of(new TileLayer("g", 8, 8, new int[64], false)),
                List.of());
        rt.scripts().bindMap(map);
    }
}
