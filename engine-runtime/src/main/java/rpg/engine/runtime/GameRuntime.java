package rpg.engine.runtime;

import rpg.engine.map.*;
import rpg.engine.world.GameWorld;
import rpg.engine.script.lua.LuaRuntime;
import rpg.engine.script.UiSink;
import rpg.engine.core.ecs.EntityId;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /** Host-owned: tells the script layer which entity ids are live players this tick. */
    public void setPlayers(java.util.Collection<Long> playerIds) { scripts.api().setPlayers(playerIds); }

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
            world.entities().set(id, new rpg.engine.core.component.Scale(e.scale()));
            String sp = e.script();
            if (sp != null && !sp.isBlank()) {
                Path scriptPath = Path.of(sp);
                if (!scriptPath.isAbsolute() && base != null) scriptPath = base.resolve(sp);
                try {
                    scripts.loadEntityScript(readUtf8(scriptPath), scriptPath.toString(), id);
                } catch (LuaError ex) {
                    System.err.println("[Lua] script " + scriptPath + " failed: " + ex.getMessage());
                }
            }
        }
    }

    /**
     * UTF-8 file read that works on every Android API level.
     * {@code Files.readString} (Java 11) does not exist in Android's libcore and
     * throws NoSuchMethodError at runtime, so the script is read via plain java.io.
     */
    private static String readUtf8(Path path) throws IOException {
        try (InputStream in = new FileInputStream(path.toFile())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
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