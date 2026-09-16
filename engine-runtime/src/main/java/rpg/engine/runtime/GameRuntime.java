package rpg.engine.runtime;

import rpg.engine.map.*;
import rpg.engine.world.GameWorld;
import rpg.engine.script.lua.LuaRuntime;
import rpg.engine.script.UiSink;
import rpg.engine.core.ecs.EntityId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.luaj.vm2.LuaError;

public final class GameRuntime {
    private final GameWorld world = new GameWorld();
    private final LuaRuntime scripts = new LuaRuntime();
    private final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();
    private RMap map;
    { scripts.bindWorld(world); }
    public GameWorld world() { return world; }
    public LuaRuntime scripts() { return scripts; }
    public RMap map() { return map; }

    public void setUiSink(UiSink sink) { scripts.api().setUiSink(sink); }

    /** Thread-safe: queues a dialog response to be delivered on the next tick. */
    public void respondDialog(long dialogId, int choice) {
        pendingActions.add(() -> scripts.api().respondDialog(dialogId, choice));
    }

    public void loadMap(Path path) throws IOException {
        RMap m = RMapIO.read(path);
        this.map = m;
        scripts.bindMap(m);
        Path base = path.getParent();
        for (MapEntity e : m.entities()) {
            EntityId id = world.spawn();
            world.entities().set(id, new rpg.engine.core.component.Name(e.id()));
            world.entities().set(id, new rpg.engine.core.component.Prefab(e.prefab()));
            world.entities().set(id, new rpg.engine.core.component.Transform(e.position(), 0));
            String sp = e.script();
            if (sp != null && !sp.isBlank()) {
                Path scriptPath = Path.of(sp);
                if (!scriptPath.isAbsolute() && base != null) scriptPath = base.resolve(sp);
                try {
                    scripts.loadEntityScript(Files.readString(scriptPath), scriptPath.toString(), id);
                } catch (LuaError ex) {
                    System.err.println("[Lua] script " + scriptPath + " failed: " + ex.getMessage());
                }
            }
        }
    }

    public void executeScript(String source, String name) { scripts.execute(source, name); }

    public void tick() {
        Runnable t;
        while ((t = pendingActions.poll()) != null) t.run();
        world.step();
        scripts.api().setTick(world.tick());
        scripts.tickDispatch();
    }
}