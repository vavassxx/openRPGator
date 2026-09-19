package rpg.engine.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.pak.PakFile;

/**
 * Sprite contract: an entity's texture is addressed by NAME — the prefab string must equal a
 * {@code sprite/*} short key in the asset packs. There is deliberately no index and no
 * "unused key" fallback: unknown prefabs resolve to {@code null} (client draws a marker).
 */
class SpritesTest {

    @Test void resolveHonorsExactNames() {
        Set<String> keys = Set.of("player", "rat", "arch", "well");
        assertEquals("rat", Sprites.resolve(keys, "rat"));
        assertEquals("player", Sprites.resolve(keys, "player"));
        // Type-dispatched names (npc/item/mob/...) are NOT sprite keys -> nothing to draw.
        assertNull(Sprites.resolve(keys, "npc"));
        assertNull(Sprites.resolve(keys, "portal"));
    }

    @Test void resolveRejectsBlankAndNullPrefabs() {
        assertNull(Sprites.resolve(Set.of("player"), null));
        assertNull(Sprites.resolve(Set.of("player"), ""));
        assertNull(Sprites.resolve(Set.of("player"), "   "));
    }

    @Test void prefabsOfCollectsDistinctNames() {
        var mapWithout = new rpg.engine.map.RMap("t", 32, 2, 2,
                java.util.List.of(new rpg.engine.map.TileLayer("g", 2, 2, new int[4], false)),
                java.util.List.of(new rpg.engine.map.MapEntity("a", "rat", new rpg.engine.core.math.WorldPosition(0, 0, 0), null),
                        new rpg.engine.map.MapEntity("b", "rat", new rpg.engine.core.math.WorldPosition(1, 0, 0), null),
                        new rpg.engine.map.MapEntity("c", "well", new rpg.engine.core.math.WorldPosition(2, 0, 0), null)));
        assertEquals(new TreeSet<>(Set.of("rat", "well")), Sprites.prefabsOf(mapWithout));
    }

    /**
     * Content-coverage guard against the "type-prefab" footgun: every entity prefab in the shipped
     * town map must have a matching sprite in basic.pak, otherwise it would render as a marker.
     * Reads real files relative to the test working dir (Gradle: module dir → {@code ../examples}).
     */
    @Test
    void shippedMapPrefabsAllResolveInShippedPak() throws Exception {
        Path mapPath = Path.of("../examples/host/town.rmap"), pakPath = Path.of("../assets/basic.pak");
        Assumptions.assumeTrue(Files.isRegularFile(mapPath) && Files.isRegularFile(pakPath),
                "repo content not present in this checkout");
        RMap map = RMapIO.read(mapPath);
        PakFile pak = PakFile.readIndex(pakPath);
        Set<String> keys = new TreeSet<>();
        for (String n : pak.namesByPrefix("sprite/")) keys.add(n.substring(7));
        Set<String> missing = new TreeSet<>();
        for (String p : Sprites.prefabsOf(map))
            if (!keys.contains(p)) missing.add(p);
        assertTrue(missing.isEmpty(), "map prefabs without sprite in basic.pak: " + missing);
    }
}