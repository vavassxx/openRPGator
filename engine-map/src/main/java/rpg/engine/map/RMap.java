package rpg.engine.map;

import java.util.*;

public record RMap(String name, int tileSize, int width, int height,
                   List<TileLayer> layers, List<MapEntity> entities, List<MapPortal> portals) {
    public RMap {
        layers = List.copyOf(layers);
        entities = List.copyOf(entities);
        portals = portals == null ? List.of() : List.copyOf(portals);
    }

    /** Backward-compatible constructor for maps/builders that predate region portals. */
    public RMap(String name, int tileSize, int width, int height,
                List<TileLayer> layers, List<MapEntity> entities) {
        this(name, tileSize, width, height, layers, entities, List.of());
    }
}
