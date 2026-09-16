package rpg.engine.script.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.map.RMap;
import rpg.engine.world.GameWorld;

import java.io.Reader;
import java.util.Objects;

/**
 * Isolated LuaJ runtime. Engine-facing bindings are installed through {@link LuaApi}.
 * After {@link #bindWorld} the runtime can execute scripts; {@link #tickDispatch} must
 * be called once per server tick to fire registered handlers.
 */
public final class LuaRuntime {
    private final Globals globals;
    private final LuaApi api;

    public LuaRuntime() {
        globals = JsePlatform.standardGlobals();
        api = new LuaApi();
        api.install(globals);
    }

    public Globals globals() { return globals; }
    public LuaApi api() { return api; }

    public void bindWorld(GameWorld world) { api.bindWorld(world); api.install(globals); }
    public void bindMap(RMap map) { api.bindMap(map); }

    /** Dispatches all registered per-tick and global tick handlers. Call once per server tick. */
    public void tickDispatch() { api.dispatchTick(); }

    /** Anonymous script, not bound to any entity. */
    public LuaValue execute(String source, String chunkName) {
        Objects.requireNonNull(source, "source");
        return api.executeOwned(globals, source, chunkName, null);
    }

    /** Script chunk executed with {@code entity}/{@code self} bound to {@code owner}. */
    public LuaValue loadEntityScript(String source, String chunkName, EntityId owner) {
        Objects.requireNonNull(source, "source");
        return api.executeOwned(globals, source, chunkName, owner);
    }

    public LuaValue execute(Reader source, String chunkName) {
        return globals.load(source, chunkName == null ? "chunk" : chunkName).call();
    }
}