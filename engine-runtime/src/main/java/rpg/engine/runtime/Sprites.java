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

    /**
     * Prefab → sprite-index map that matches the client's sprite array regardless of gaps in
     * the {@code sprite/*} key set.
     *
     * <p>Prefabs that equal a pak key ({@code sprite/<prefab>} present in the packs) resolve to
     * the exact index that key has in the client's sorted array. Remaining prefabs (e.g. semantic
     * names like {@code npc} in hand-authored maps) are assigned to the still-unused pak keys in
     * sorted order, which reproduces the classic prefab-order look while keeping every index inside
     * the client array.
     *
     * @param pakIndex key → sorted-array-index map from {@code PakAssets.spriteKeyIndex}
     */
    public static Map<String, Integer> byPrefabInPak(RMap map, Map<String, Integer> pakIndex) {
        TreeSet<String> prefabs = new TreeSet<>();
        for (MapEntity e : map.entities()) {
            if (e.prefab() != null && !e.prefab().isBlank()) prefabs.add(e.prefab());
        }
        if (prefabs.isEmpty()) return Map.of();
        Map<String, Integer> out = new HashMap<>();
        for (String prefab : prefabs) {
            Integer idx = pakIndex.get(prefab);
            if (idx != null) out.put(prefab, idx);
        }
        // unused keys in the client's sorted order; leftover prefabs take them one by one
        List<String> unused = new ArrayList<>(pakIndex.keySet());
        for (String taken : out.keySet()) unused.remove(taken);
        unused.sort(Comparator.comparingInt(pakIndex::get));
        int i = 0;
        for (String prefab : prefabs) {
            if (out.containsKey(prefab)) continue;
            if (i >= unused.size()) break;
            out.put(prefab, pakIndex.get(unused.get(i++)));
        }
        return out;
    }

    public static int resourceOf(Map<String, Integer> byPrefab, String prefab) {
        return byPrefab == null || prefab == null ? -1 : byPrefab.getOrDefault(prefab, -1);
    }
}