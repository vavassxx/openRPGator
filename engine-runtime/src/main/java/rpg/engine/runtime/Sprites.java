package rpg.engine.runtime;

import rpg.engine.map.MapEntity;
import rpg.engine.map.RMap;
import java.util.*;

/**
 * Server-side mapping from a map entity prefab to the numeric sprite index placed on the wire
 * as {@code Snapshot.EntityState.resource()}. The index is the prefab's position in the
 * alphabetically sorted list of distinct entity prefabs, so the server (which computes it from
 * the .rmap) and the packer (which stores sprites as {@code sprite/<prefab>.png}) agree without
 * extra handshaking.
 *
 * Prefab is the explicit texture binding authored in the map editor: each entity's visual is
 * chosen by assigning it a prefab that matches a {@code sprite/*} key in the asset pack.
 *
 * Entities without a prefab (e.g. players spawned from {@code Hello}) resolve to -1, which
 * clients render using the special {@code sprite/player} entry.
 */
public final class Sprites {
    private Sprites() {}

    public static Map<String, Integer> byPrefab(RMap map) {
        TreeSet<String> prefabs = new TreeSet<>();
        for (MapEntity e : map.entities()) {
            if (e.prefab() != null && !e.prefab().isBlank()) prefabs.add(e.prefab());
        }
        Map<String, Integer> out = new HashMap<>();
        int i = 0;
        for (String prefab : prefabs) out.put(prefab, i++);
        return out;
    }

    public static int resourceOf(Map<String, Integer> byPrefab, String prefab) {
        return byPrefab == null || prefab == null ? -1 : byPrefab.getOrDefault(prefab, -1);
    }
}