package rpg.engine.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rpg.engine.core.component.CircleCollider;
import rpg.engine.core.component.Name;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.MapEntity;
import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.map.TileLayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapLoadingTest {

    @TempDir
    Path dir;

    private static RMap mapWithCollisionLayer() {
        int[] ground = new int[4 * 4];
        int[] collision = new int[4 * 4];
        collision[1 * 4 + 2] = 7; // tile (2,1) blocked
        return new RMap("coll", 1, 4, 4,
                List.of(new TileLayer("ground", 4, 4, ground, false),
                        new TileLayer("collision", 4, 4, collision, true)),
                List.of(), List.of());
    }

    @Test
    void collisionGridIsInstalledFromCollisionLayer() throws Exception {
        Path map = dir.resolve("coll.rmap");
        RMapIO.write(mapWithCollisionLayer(), map);

        GameRuntime runtime = new GameRuntime("coll", 1L << 48);
        runtime.loadMap(map);

        var grid = runtime.world().collision().tileGrid();
        assertNotNull(grid, "tile grid must be installed from collision layer");
        assertFalse(grid.blockedAt(0, 0));
        assertTrue(grid.blockedAt(2, 1));

        var player = runtime.world().spawn();
        runtime.world().entities().set(player, new CircleCollider(0.35));
        assertTrue(runtime.world().collision().canOccupy(player, new WorldPosition(1.5, 1.5, 0)));
        assertFalse(runtime.world().collision().canOccupy(player, new WorldPosition(2.5, 1.5, 0)));
        // Finite world bounds come from the grid too.
        assertFalse(runtime.world().collision().canOccupy(player, new WorldPosition(-1, 1.5, 0)));
    }

    @Test
    void mapWithoutCollisionLayerHasNoGrid() throws Exception {
        Path map = dir.resolve("nocoll.rmap");
        RMapIO.write(new RMap("nocoll", 32, 4, 4,
                List.of(new TileLayer("g", 4, 4, new int[16], false)), List.of()), map);

        GameRuntime runtime = new GameRuntime("nocoll", 1L << 48);
        runtime.loadMap(map);
        assertNull(runtime.world().collision().tileGrid());
    }

    @Test
    void autoLoadsWorldLuaNextToTheMap() throws Exception {
        Path map = dir.resolve("world.rmap");
        RMapIO.write(new RMap("w", 32, 4, 4,
                List.of(new TileLayer("g", 4, 4, new int[16], false)), List.of()), map);
        Files.writeString(dir.resolve("world.lua"), "loaded_world_flag = 123");

        GameRuntime runtime = new GameRuntime("w", 1L << 48);
        runtime.loadMap(map);
        assertEquals(123, runtime.scripts().globals().get("loaded_world_flag").toint());
    }

    @Test
    void explicitWorldScriptOverridesAutoload() throws Exception {
        Path map = dir.resolve("override.rmap");
        RMapIO.write(new RMap("ov", 32, 4, 4,
                List.of(new TileLayer("g", 4, 4, new int[16], false)), List.of()), map);
        Files.writeString(dir.resolve("world.lua"), "which = 'auto'");
        Path custom = dir.resolve("custom.lua");
        Files.writeString(custom, "which = 'custom'");

        GameRuntime runtime = new GameRuntime("ov", 1L << 48);
        runtime.setWorldScript(custom);
        runtime.loadMap(map);
        assertEquals("custom", runtime.scripts().globals().get("which").tojstring());
    }

    @Test
    void missingWorldLuaIsSilentlyIgnored() throws Exception {
        Path map = dir.resolve("plain.rmap");
        RMapIO.write(new RMap("p", 32, 4, 4,
                List.of(new TileLayer("g", 4, 4, new int[16], false)), List.of()), map);

        GameRuntime runtime = new GameRuntime("p", 1L << 48);
        runtime.loadMap(map); // must not throw
        assertTrue(runtime.scripts().globals().get("nothing_ran").isnil());
    }

    @Test
    void spawnsMapEntitiesAndLoadsTheirScripts() throws Exception {
        Path scriptsDir = dir.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(scriptsDir.resolve("mob.lua"), "spawned_mob = entity.name()");
        RMap map = new RMap("e", 32, 4, 4,
                List.of(new TileLayer("g", 4, 4, new int[16], false)),
                List.of(new MapEntity("wolf_1", "wolf", new WorldPosition(1, 1, 0), "scripts/mob.lua", 1.0)),
                List.of());
        Path mapPath = dir.resolve("ent.rmap");
        RMapIO.write(map, mapPath);

        GameRuntime runtime = new GameRuntime("e", 1L << 48);
        runtime.loadMap(mapPath);

        assertEquals("wolf_1", runtime.scripts().globals().get("spawned_mob").tojstring());
        var names = runtime.world().entities().entities().stream()
                .filter(id -> runtime.world().entities().get(id, Name.class).isPresent())
                .count();
        assertEquals(1, names, "exactly the map entity should exist");
    }
}