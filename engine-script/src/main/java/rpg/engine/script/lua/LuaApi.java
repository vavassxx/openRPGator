package rpg.engine.script.lua;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import static org.luaj.vm2.LuaValue.*;
import rpg.engine.core.component.Transform;
import rpg.engine.core.component.Trigger;
import rpg.engine.core.component.Scale;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.component.Name;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.RMap;
import rpg.engine.script.UiSink;
import rpg.engine.world.GameWorld;
import rpg.engine.world.TriggerEnterEvent;
import rpg.engine.world.TriggerExitEvent;
import rpg.engine.world.InteractRequestedEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Public, engine-facing Lua API v0.5. Lua sees stable facades rather than ECS internals.
 *
 * Beyond the read/write world surface (spawn/find/all/get_near/tile), scripts can register
 * persistent handlers — {@code engine.on_tick} and per-entity {@code on_tick/on_enter/on_exit/on_interact} —
 * which the engine dispatches each tick or on trigger/interact events. Push UI (toast
 * notifications and choice dialogs) is routed through an optional {@link UiSink} installed by
 * the embedding server.
 */
public final class LuaApi {
    private volatile GameWorld world;
    private volatile RMap map;
    private volatile long tick;
    private volatile UiSink uiSink;
    private String version = "0.4.0";

    private final Map<EntityId, List<LuaFunction>> tickHandlers = new LinkedHashMap<>();
    private final Map<EntityId, List<LuaFunction>> enterHandlers = new LinkedHashMap<>();
    private final Map<EntityId, List<LuaFunction>> exitHandlers = new LinkedHashMap<>();
    private final Map<EntityId, List<LuaFunction>> interactHandlers = new LinkedHashMap<>();
    private final List<LuaFunction> globalTickHandlers = new ArrayList<>();

    private final AtomicLong dialogIds = new AtomicLong();
    private final Map<Long, LuaFunction> dialogCallbacks = new ConcurrentHashMap<>();

    public void bindWorld(GameWorld world) {
        this.world = world;
        world.events().on(TriggerEnterEvent.class, e -> dispatchEnter(e.entity(), e.trigger()));
        world.events().on(TriggerExitEvent.class, e -> dispatchExit(e.entity(), e.trigger()));
        world.events().on(InteractRequestedEvent.class, e -> dispatchInteract(e.player(), e.target()));
    }
    public void bindMap(RMap map) { this.map = map; }
    public void setTick(long tick) { this.tick = tick; }
    public void setVersion(String version) { this.version = version; }
    public void setUiSink(UiSink sink) { this.uiSink = sink; }

    public void install(Globals globals) {
        LuaTable engine = new LuaTable();
        engine.set("version", new ZeroArgFunction() { public LuaValue call() { return valueOf(version); }});
        engine.set("tick", new ZeroArgFunction() { public LuaValue call() { return valueOf(tick); }});
        engine.set("log", new OneArgFunction() { public LuaValue call(LuaValue value) { System.out.println("[Lua] " + value.tojstring()); return NONE; }});
        engine.set("on_tick", new OneArgFunction() { public LuaValue call(LuaValue fn) { register(globalTickHandlers, null, fn); return NONE; }});
        engine.set("notify", new OneArgFunction() {
            public LuaValue call(LuaValue text) {
                if (uiSink != null) uiSink.broadcastNotify(text.optjstring(""));
                return NONE;
            }
        });
        engine.set("dialog", new ArgsLib() {
            public LuaValue callImpl(Varargs args) {
                LuaValue target = args.arg(1);
                String text = args.arg(2).tojstring();
                LuaValue choicesTable = args.arg(3);
                LuaValue callback = args.arg(4);
                if (uiSink != null) {
                    long targetId = targetIdOf(target);
                    if (targetId == -1) return NONE;
                    long dialogId = dialogIds.incrementAndGet();
                    if (callback.isfunction()) dialogCallbacks.put(dialogId, callback.checkfunction());
                    List<String> choices = new ArrayList<>();
                    for (int i = 1; i <= choicesTable.length(); i++) choices.add(choicesTable.get(i).tojstring());
                    uiSink.dialogTo(targetId, dialogId, text, choices, choice -> respondDialog(dialogId, choice));
                }
                return NONE;
            }
        });
        globals.set("engine", engine);

        LuaTable worldApi = new LuaTable();
        worldApi.set("count", new ZeroArgFunction() { public LuaValue call() { return valueOf(count()); }});
        worldApi.set("size", new ZeroArgFunction() { public LuaValue call() { return sizeTable(); }});
        worldApi.set("spawn", new OneArgFunction() { public LuaValue call(LuaValue name) { return entityFacade(spawn(name.optjstring("entity"))); }});
        worldApi.set("destroy", new OneArgFunction() { public LuaValue call(LuaValue id) { destroy(id.tojstring()); return NONE; }});
        worldApi.set("get", new OneArgFunction() { public LuaValue call(LuaValue id) { EntityId e = parseId(id.tojstring()); return e == null ? NIL : entityFacade(e); }});
        worldApi.set("find", new OneArgFunction() { public LuaValue call(LuaValue name) { return find(name.tojstring()); }});
        worldApi.set("all", new ZeroArgFunction() { public LuaValue call() { return allEntities(); }});
        worldApi.set("get_near", new ArgsLib() {
            public LuaValue callImpl(Varargs args) {
                double x = args.arg1().todouble(), y = args.arg(2).todouble(), r = args.narg() >= 3 ? args.arg(3).todouble() : 1.0;
                return nearEntities(x, y, r);
            }
        });
        worldApi.set("tile", new ArgsLib() {
            public LuaValue callImpl(Varargs args) {
                int x = args.arg(1).checkint(), y = args.arg(2).checkint();
                int layer = args.narg() >= 3 ? args.arg(3).checkint() : 0;
                return valueOf(tileAt(x, y, layer));
            }
        });
        worldApi.set("set_tile", new ArgsLib() {
            public LuaValue callImpl(Varargs args) {
                int x = args.arg(1).checkint(), y = args.arg(2).checkint();
                int id = args.arg(3).checkint();
                int layer = args.narg() >= 4 ? args.arg(4).checkint() : 0;
                return setTileAt(x, y, layer, id) ? LuaBoolean.TRUE : FALSE;
            }
        });
        globals.set("world", worldApi);
    }

    /** Loads a chunk that is conceptually owned by {@code owner}; exposes {@code entity}/{@code self}
     *  as facades of that owner for the duration of the chunk, then restores the previous globals. */
    public LuaValue executeOwned(Globals globals, String source, String chunkName, EntityId owner) {
        LuaValue prevEntity = globals.get("entity");
        LuaValue prevSelf = globals.get("self");
        if (owner != null) {
            LuaValue me = entityFacade(owner);
            globals.set("entity", me);
            globals.set("self", me);
        }
        try {
            return globals.load(source, chunkName == null ? "chunk" : chunkName).call();
        } finally {
            globals.set("entity", prevEntity);
            globals.set("self", prevSelf);
        }
    }

    // ── Dispatch (called by LuaRuntime on the server tick thread) ──
    public void dispatchTick() {
        long t = tick;
        for (LuaFunction fn : List.copyOf(globalTickHandlers)) safeCall("on_tick", fn, valueOf(t));
        for (Map.Entry<EntityId, List<LuaFunction>> e : List.copyOf(tickHandlers.entrySet())) {
            EntityId id = e.getKey();
            if (world == null || !world.entities().entities().contains(id)) continue;
            LuaValue me = entityFacade(id);
            for (LuaFunction fn : List.copyOf(e.getValue())) safeCall("on_tick", fn, me);
        }
    }

    public void dispatchEnter(EntityId actor, EntityId trigger) { dispatchTo(enterHandlers, "on_enter", actor, trigger); }
    public void dispatchExit(EntityId actor, EntityId trigger) { dispatchTo(exitHandlers, "on_exit", actor, trigger); }
    public void dispatchInteract(EntityId actor, EntityId trigger) { dispatchTo(interactHandlers, "on_interact", actor, trigger); }

    private void dispatchTo(Map<EntityId, List<LuaFunction>> into, String what, EntityId actor, EntityId trigger) {
        if (world == null) return;
        List<LuaFunction> handlers = into.get(trigger);
        if (handlers == null) return;
        LuaValue me = entityFacade(actor); // actor is the subject passed to handlers
        for (LuaFunction fn : List.copyOf(handlers)) safeCall(what, fn, me);
    }

    private void safeCall(String what, LuaFunction fn, Varargs args) {
        try {
            fn.invoke(args);
        } catch (LuaError e) {
            System.err.println("[Lua] " + what + " handler failed: " + e.getMessage());
        } catch (Throwable t) {
            System.err.println("[Lua] " + what + " handler failed: " + t);
        }
    }

    /** Invoked (via the UiSink) when a client answers the dialog; must run on the tick thread. */
    public void respondDialog(long dialogId, int choice) {
        LuaFunction fn = dialogCallbacks.remove(dialogId);
        if (fn == null) return;
        safeCall("dialog", fn, valueOf(choice + 1));
    }

    // ── World operations ──────────────────────────────────────────
    private int count() { return world == null ? 0 : world.entities().entities().size(); }

    private LuaTable sizeTable() {
        if (map == null) return null;
        LuaTable s = new LuaTable();
        s.set("width", valueOf(map.width()));
        s.set("height", valueOf(map.height()));
        s.set("tile_size", valueOf(map.tileSize()));
        return s;
    }

    private EntityId spawn(String name) {
        EntityId id = world.spawn();
        world.entities().set(id, new Name(name));
        world.entities().set(id, new Transform(new WorldPosition(0, 0, 0), 0));
        return id;
    }
    private void destroy(String raw) { if (world != null) { EntityId id = parseId(raw); if (id != null) world.entities().destroy(id); } }

    private LuaValue find(String name) {
        if (world == null) return NIL;
        for (EntityId e : world.entities().entities()) {
            var n = world.entities().get(e, Name.class);
            if (n.isPresent() && n.get().value().equals(name)) return entityFacade(e);
        }
        return NIL;
    }

    private LuaTable allEntities() {
        LuaTable t = new LuaTable();
        if (world == null) return t;
        int i = 1;
        for (EntityId e : world.entities().entities()) t.set(i++, entityFacade(e));
        return t;
    }

    private LuaTable nearEntities(double x, double y, double r) {
        LuaTable t = new LuaTable();
        if (world == null) return t;
        double rr = r * r;
        int i = 1;
        for (EntityId e : world.entities().entities()) {
            var tr = world.entities().get(e, Transform.class);
            if (tr.isEmpty()) continue;
            double dx = tr.get().position().x() - x, dy = tr.get().position().y() - y;
            if (dx * dx + dy * dy <= rr) t.set(i++, entityFacade(e));
        }
        return t;
    }

    private EntityId parseId(String raw) {
        try { return new EntityId(Long.parseLong(raw.trim())); } catch (Exception ignored) { }
        return findId(raw);
    }

    /** Extracts the entity id from a facade table (calls {@code facade.id()}) or a raw number. */
    private long targetIdOf(LuaValue target) {
        if (target.istable()) {
            LuaValue idFn = target.get("id");
            if (idFn.isfunction()) {
                try { return idFn.invoke().arg1().tolong(); } catch (LuaError ignored) { }
            }
            return -1;
        }
        return target.isnumber() ? target.tolong() : -1;
    }

    private EntityId findId(String name) {
        if (world == null) return null;
        for (EntityId e : world.entities().entities()) {
            var n = world.entities().get(e, Name.class);
            if (n.isPresent() && n.get().value().equals(name)) return e;
        }
        return null;
    }

    // ── Tile access ───────────────────────────────────────────────
    private int tileAt(int x, int y, int layer) {
        var l = layer(layer);
        if (l == null || x < 0 || y < 0 || x >= l.width() || y >= l.height()) return -1;
        return l.tiles()[y * l.width() + x];
    }

    private boolean setTileAt(int x, int y, int layer, int id) {
        var l = layer(layer);
        if (l == null || x < 0 || y < 0 || x >= l.width() || y >= l.height()) return false;
        l.tiles()[y * l.width() + x] = id;
        return true;
    }

    private rpg.engine.map.TileLayer layer(int layer) {
        if (map == null || map.layers().isEmpty()) return null;
        if (layer < 0 || layer >= map.layers().size()) return null;
        return map.layers().get(layer);
    }

    // ── Entity facade ─────────────────────────────────────────────
    private LuaTable entityFacade(EntityId id) {
        LuaTable t = new LuaTable();
        t.set("id", new ZeroArgFunction() { public LuaValue call() { return valueOf(id.value()); }});
        t.set("name", new ZeroArgFunction() { public LuaValue call() { return valueOf(world.entities().get(id, Name.class).map(Name::value).orElse("")); }});
        t.set("set_name", new OneArgFunction() { public LuaValue call(LuaValue value) { world.entities().set(id, new Name(value.tojstring())); return NONE; }});
        t.set("exists", new ZeroArgFunction() { public LuaValue call() { return valueOf(world.entities().entities().contains(id)); }});
        t.set("position", new ZeroArgFunction() { public LuaValue call() { return positionFacade(id); }});
        t.set("set_position", new ThreeArgFunction() { public LuaValue call(LuaValue x, LuaValue y, LuaValue z) { world.entities().set(id, new Transform(new WorldPosition(x.todouble(), y.todouble(), z.todouble()), 0)); return NONE; }});
        t.set("set_trigger", new OneArgFunction() { public LuaValue call(LuaValue radius) { world.entities().set(id, new Trigger(radius.checkdouble())); return NONE; }});
        t.set("trigger_radius", new ZeroArgFunction() { public LuaValue call() { return world.entities().get(id, Trigger.class).map(r -> (LuaValue) valueOf(r.radius())).orElse(NIL); }});
        t.set("set_scale", new OneArgFunction() { public LuaValue call(LuaValue value) { world.entities().set(id, new Scale(Math.max(0.01, value.checkdouble()))); return NONE; }});
        t.set("scale", new ZeroArgFunction() { public LuaValue call() { return valueOf(world.entities().get(id, Scale.class).map(Scale::value).orElse(1.0)); }});
        t.set("on_tick", new OneArgFunction() { public LuaValue call(LuaValue fn) { register(tickHandlers, id, fn); return NONE; }});
        t.set("on_enter", new OneArgFunction() { public LuaValue call(LuaValue fn) { register(enterHandlers, id, fn); return NONE; }});
        t.set("on_exit", new OneArgFunction() { public LuaValue call(LuaValue fn) { register(exitHandlers, id, fn); return NONE; }});
        t.set("on_interact", new OneArgFunction() { public LuaValue call(LuaValue fn) { register(interactHandlers, id, fn); return NONE; }});
        return t;
    }

    private LuaTable positionFacade(EntityId id) {
        LuaTable p = new LuaTable();
        var tr = world.entities().get(id, Transform.class).orElse(new Transform(new WorldPosition(0, 0, 0), 0));
        p.set("x", LuaDouble.valueOf(tr.position().x()));
        p.set("y", LuaDouble.valueOf(tr.position().y()));
        p.set("z", LuaDouble.valueOf(tr.position().elevation()));
        return p;
    }

    private void register(List<LuaFunction> into, EntityId id, LuaValue fn) {
        if (!fn.isfunction()) return;
        into.add((LuaFunction) fn);
    }

    private void register(Map<EntityId, List<LuaFunction>> into, EntityId id, LuaValue fn) {
        if (id == null || !fn.isfunction()) return;
        into.computeIfAbsent(id, k -> new ArrayList<>()).add((LuaFunction) fn);
    }

    /**
     * LuaJ 3.0.1 quirk: {@link VarArgFunction#invoke} calls {@code onInvoke}, whose default
     * implementation calls {@code invoke} again — infinite recursion unless {@code onInvoke}
     * is overridden. Subclasses implement {@link #call(Varargs)} (the old contract); the
     * bridging here forwards {@code onInvoke} args to it so Lua-side calls land correctly.
     */
    private abstract static class ArgsLib extends VarArgFunction {
        abstract LuaValue callImpl(Varargs args);
        @Override public Varargs onInvoke(Varargs args) {
            return callImpl(args);
        }
    }
}