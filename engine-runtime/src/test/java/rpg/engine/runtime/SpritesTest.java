package rpg.engine.runtime;

import org.junit.jupiter.api.Test;

import rpg.engine.map.MapEntity;
import rpg.engine.map.RMap;
import rpg.engine.map.TileLayer;
import rpg.engine.core.math.WorldPosition;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprite-index contract between server and client: server computes prefab → resource indices
 * that must land inside the client's sorted {@code sprite/*} array even when the pak key set
 * has gaps or the map uses semantic prefabs.
 */
class SpritesTest {

    /** The client's array order for the current basic.pak (sprite/7 missing). */
    private static Map<String, Integer> pakIndex() {
        Map<String, Integer> m = new HashMap<>();
        m.put("0", 0); m.put("1", 1); m.put("2", 2); m.put("3", 3);
        m.put("4", 4); m.put("5", 5); m.put("6", 6); m.put("8", 7);
        m.put("9", 8); m.put("player", 9);
        return m;
    }

    private static RMap mapWith(String... prefabs) {
        List<MapEntity> es = new ArrayList<>();
        int i = 0;
        for (String p : prefabs) es.add(new MapEntity("e" + i++, p, new WorldPosition(0, 0, 0), null));
        return new RMap("t", 32, 10, 10, List.of(new TileLayer("ground", 10, 10, new int[100], false)), es);
    }

    @Test void prefabEqualsPakKeyResolvesExactIndex() {
        Map<String, Integer> m = Sprites.byPrefabInPak(mapWith("7", "player"), pakIndex());
        assertEquals(9, m.get("player"));   // sprite/player -> its exact array index
        assertEquals(0, m.get("7"));        // sprite/7 absent: key skipped, prefab takes first free slot
        assertNull(m.get("8"));             // unclaimed pak keys stay out of the map
    }

    @Test void semanticPrefabsMapIntoUnusedKeysInOrder() {
        Map<String, Integer> m = Sprites.byPrefabInPak(mapWith("npc", "portal", "item"), pakIndex());
        assertEquals(0, m.get("item"));    // sorted-prefab assignment: item, npc, portal -> 0, 1, 2
        assertEquals(1, m.get("npc"));
        assertEquals(2, m.get("portal"));
        assertEquals(3, m.size());
    }

    @Test void everyAssignedIndexIsInsideTheClientArray() {
        Map<String, Integer> m = Sprites.byPrefabInPak(
                mapWith("conductor", "item", "mob", "npc", "portal", "prop", "7"), pakIndex());
        int n = pakIndex().size();
        for (int v : m.values()) {
            assertTrue(v >= 0 && v < n, "index " + v + " must be a valid client sprite slot");
        }
        assertEquals(7, m.size());
    }

    @Test void emptyPrefabsYieldEmptyMap() {
        assertEquals(Map.of(), Sprites.byPrefabInPak(mapWith(), pakIndex()));
    }

    @Test void noPaksFallsBackToPrefabOrder() {
        RMap map = mapWith("npc", "portal");
        Map<String, Integer> m = Sprites.byPrefab(map);
        assertEquals(1, m.get("portal"));
        assertEquals(0, m.get("npc"));
    }
}