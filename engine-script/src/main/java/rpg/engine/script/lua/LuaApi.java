package rpg.engine.script.lua;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import rpg.engine.core.component.Transform;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.ecs.Name;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.world.GameWorld;

/** Public, engine-facing Lua API. Lua sees stable facades rather than ECS internals. */
public final class LuaApi {
    private GameWorld world;
    private long tick;
    private String version = "0.4.0";

    public void bindWorld(GameWorld world) { this.world = world; }
    public void setTick(long tick) { this.tick = tick; }
    public void setVersion(String version) { this.version = version; }

    public void install(Globals globals) {
        LuaTable engine = new LuaTable();
        engine.set("version", new ZeroArgFunction() { public LuaValue call() { return valueOf(version); }});
        engine.set("tick", new ZeroArgFunction() { public LuaValue call() { return valueOf(tick); }});
        engine.set("log", new OneArgFunction() { public LuaValue call(LuaValue value) { System.out.println("[Lua] " + value.tojstring()); return NONE; }});
        globals.set("engine", engine);

        LuaTable worldApi = new LuaTable();
        worldApi.set("count", new ZeroArgFunction() { public LuaValue call() { return valueOf(world == null ? 0 : world.entities().entities().size()); }});
        worldApi.set("spawn", new OneArgFunction() { public LuaValue call(LuaValue name) { return entityFacade(spawn(name.optjstring("entity"))); }});
        worldApi.set("destroy", new OneArgFunction() { public LuaValue call(LuaValue id) { destroy(id.tojstring()); return NONE; }});
        worldApi.set("get", new OneArgFunction() { public LuaValue call(LuaValue id) { EntityId e = parseId(id.tojstring()); return e == null ? NIL : entityFacade(e); }});
        globals.set("world", worldApi);
    }

    private EntityId spawn(String name) {
        EntityId id = world.spawn();
        world.entities().set(id, new Name(name));
        world.entities().set(id, new Transform(new WorldPosition(0, 0, 0), 0));
        return id;
    }
    private void destroy(String raw) { if (world != null) { EntityId id = parseId(raw); if (id != null) world.entities().destroy(id); } }
    private EntityId parseId(String raw) { try { return new EntityId(Long.parseLong(raw)); } catch (Exception e) { return null; } }

    private LuaTable entityFacade(EntityId id) {
        LuaTable t = new LuaTable();
        t.set("id", new ZeroArgFunction() { public LuaValue call() { return valueOf(id.value()); }});
        t.set("name", new ZeroArgFunction() { public LuaValue call() { return valueOf(world.entities().get(id, Name.class).map(Name::value).orElse("")); }});
        t.set("set_name", new OneArgFunction() { public LuaValue call(LuaValue value) { world.entities().set(id, new Name(value.tojstring())); return NONE; }});
        t.set("position", new ZeroArgFunction() { public LuaValue call() { return positionFacade(id); }});
        t.set("set_position", new ThreeArgFunction() { public LuaValue call(LuaValue x, LuaValue y, LuaValue z) { world.entities().set(id, new Transform(new WorldPosition(x.todouble(), y.todouble(), z.todouble()), 0)); return NONE; }});
        t.set("exists", new ZeroArgFunction() { public LuaValue call() { return valueOf(world.entities().entities().contains(id)); }});
        return t;
    }
    private LuaTable positionFacade(EntityId id) {
        LuaTable p = new LuaTable();
        var tr = world.entities().get(id, Transform.class).orElse(new Transform(new WorldPosition(0, 0, 0), 0));
        p.set("x", valueOf(tr.position().x())); p.set("y", valueOf(tr.position().y())); p.set("z", valueOf(tr.position().z()));
        return p;
    }
}
