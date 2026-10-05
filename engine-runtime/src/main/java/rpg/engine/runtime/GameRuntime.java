package rpg.engine.runtime;

import rpg.engine.map.*;
import rpg.engine.world.GameWorld;
import rpg.engine.script.lua.LuaRuntime;
import rpg.engine.script.UiSink;
import rpg.engine.script.PlayerStore;
import rpg.engine.core.ecs.EntityId;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.luaj.vm2.LuaError;

/** Region-local simulation runtime. Host/session state lives outside this class. */
public final class GameRuntime {
    private final String regionId;
    private final GameWorld world;
    private final LuaRuntime scripts = new LuaRuntime();
    private final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();
    private RMap map;

    public GameRuntime() { this("default", 0); }
    public GameRuntime(long entityIdBase) { this("default", entityIdBase); }

    /** Creates a runtime whose entity allocator is namespaced by the supplied base. */
    public GameRuntime(String regionId, long entityIdBase) {
        if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("regionId is blank");
        this.regionId = regionId.trim().toLowerCase(java.util.Locale.ROOT);
        world = new GameWorld(entityIdBase);
        scripts.api().setRegionId(this.regionId);
        scripts.bindWorld(world);
    }

    public String regionId() { return regionId; }
    public GameWorld world() { return world; }
    public LuaRuntime scripts() { return scripts; }
    public RMap map() { return map; }

    public void setUiSink(UiSink sink) { scripts.api().setUiSink(sink); }
    public void setPlayerStore(PlayerStore store) { scripts.api().setPlayerStore(store); }
    public void setPlayers(java.util.Collection<Long> playerIds) { scripts.api().setPlayers(playerIds); }

    public void respondDialog(long dialogId, int choice) {
        pendingActions.add(() -> scripts.api().respondDialog(dialogId, choice));
    }

    public void dispatchCommand(long playerEntityId, int code, String arg) {
        pendingActions.add(() -> scripts.api().dispatchCommand(playerEntityId, code, arg));
    }

    public void dispatchAction(long playerEntityId, String action) {
        pendingActions.add(() -> scripts.api().dispatchAction(playerEntityId, action));
    }

    public void dispatchDisconnect(long playerEntityId) {
        pendingActions.add(() -> scripts.api().dispatchDisconnect(playerEntityId));
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
