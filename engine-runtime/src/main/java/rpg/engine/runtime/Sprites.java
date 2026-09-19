package rpg.engine.runtime;

import rpg.engine.map.MapEntity;
import rpg.engine.map.RMap;

import java.util.Set;

/**
 * Sprite contract between server and client: an entity's texture is addressed by NAME — the
 * {@code sprite/*} key in the asset pack. Map entities carry the sprite name as their prefab
 * (e.g. a {@code rat} entity → {@code sprite/rat}); spawned players always use {@code player}.
 *
 * <p>There is deliberately no numeric index and no "unused key" fallback: a prefab that does not
 * exist in the pack resolves to {@code null} and the client draws a marker instead of silently
 * showing an unrelated texture. Pack compatibility is checked by {@code SpritesTest} against the
 * real {@code examples/host} map and {@code basic.pak}.
 */
public final class Sprites {
    private Sprites() {}

    /** {@code player} — sprite key used for entities spawned without a prefab (players). */
    public static final String PLAYER = "player";

    /**
     * The sprite key an entity prefab resolves to: the prefab itself when the client packs carry
     * {@code sprite/<prefab>}, otherwise {@code null} (no texture — render a marker/none).
     */
    public static String resolve(Set<String> pakSpriteKeys, String prefab) {
        if (prefab == null || prefab.isBlank()) return null;
        return pakSpriteKeys.contains(prefab) ? prefab : null;
    }

    /** All distinct non-blank entity prefabs of a map. */
    public static Set<String> prefabsOf(RMap map) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (MapEntity e : map.entities()) {
            if (e.prefab() != null && !e.prefab().isBlank()) out.add(e.prefab());
        }
        return out;
    }
}