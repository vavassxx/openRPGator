package rpg.engine.script.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.Reader;
import java.util.Objects;

/** Isolated LuaJ runtime. Engine-facing bindings are installed through LuaApi. */
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
    public void bindWorld(rpg.engine.world.GameWorld world) { api.bindWorld(world); api.install(globals); }
    public LuaValue execute(String source, String chunkName) {
        Objects.requireNonNull(source, "source");
        return globals.load(source, chunkName == null ? "chunk" : chunkName).call();
    }
    public LuaValue execute(Reader source, String chunkName) {
        return globals.load(source, chunkName == null ? "chunk" : chunkName).call();
    }
}
