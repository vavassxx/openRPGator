package rpg.engine.runtime;

import rpg.engine.map.MapEntity;
import rpg.engine.map.RMap;
import java.util.*;

/**
 * Server-side mapping from a map entity id to the numeric sprite index that is put on the wire
 * as {@code Snapshot.EntityState.resource()}. The index is the position of the id in the
 * alphabetically sorted list of distinct map entity ids, so both the server and the packer
 * (which stores sprites as {@code sprite/<index>.png}) agree without extra handshaking.
 *
 * Entities not present in the map (e.g. players spawned from {@code Hello}) resolve to -1,
 * which clients render using the special {@code sprite/player} entry.
 */
public final class Sprites {
    private Sprites() {}

    public static Map<String, Integer> byId(RMap map) {
        TreeSet<String> ids = new TreeSet<>();
        for (MapEntity e : map.entities()) ids.add(e.id());
        Map<String, Integer> out = new HashMap<>();
        int i = 0;
        for (String id : ids) out.put(id, i++);
        return out;
    }

    public static int resourceOf(Map<String, Integer> byId, String entityName) {
        return byId == null || entityName == null ? -1 : byId.getOrDefault(entityName, -1);
    }
}