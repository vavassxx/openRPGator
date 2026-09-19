package rpg.engine.map;

import rpg.engine.core.math.WorldPosition;

/** Map entity: {@code prefab} is the sprite name ({@code sprite/<prefab>} key expected in the pak). */
public record MapEntity(String id, String prefab, WorldPosition position, String script, double scale) {
    public MapEntity(String id, String prefab, WorldPosition position, String script) {
        this(id, prefab, position, script, 1.0);
    }
}
