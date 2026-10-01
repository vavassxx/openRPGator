package rpg.engine.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Server-owned lifecycle wrapper for one independently simulated region.
 *
 * <p>The region id is a server/configuration identity; the contained
 * {@link GameRuntime} owns the actual map, ECS world and region-local Lua runtime.
 * Player/session state must not be stored here.
 */
public final class RegionRuntime {
    private final String id;
    private final GameRuntime runtime;

    public RegionRuntime(String id, long entityIdBase) {
        this.id = normalizeId(id);
        this.runtime = new GameRuntime(this.id, entityIdBase);
    }

    public String id() { return id; }
    public GameRuntime runtime() { return runtime; }
    public rpg.engine.world.GameWorld world() { return runtime.world(); }
    public rpg.engine.script.lua.LuaRuntime scripts() { return runtime.scripts(); }
    public rpg.engine.map.RMap map() { return runtime.map(); }

    public void loadMap(Path path) throws IOException { runtime.loadMap(Objects.requireNonNull(path, "path")); }
    public void tick() { runtime.tick(); }

    private static String normalizeId(String raw) {
        if (raw == null) throw new IllegalArgumentException("region id is null");
        String id = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9._-]*"))
            throw new IllegalArgumentException("invalid region id: " + raw);
        return id;
    }
}
