package rpg.engine.map;

import java.util.*;
import rpg.engine.core.math.WorldPosition;

/** Immutable map asset. The runtime derives the authoritative collision grid from its collision layers. */
public record RMap(String name, int tileSize, int width, int height,
                   List<TileLayer> layers, List<MapEntity> entities, List<MapPortal> portals) {
    public RMap {
        if (width <= 0 || height <= 0 || tileSize <= 0)
            throw new IllegalArgumentException("invalid map dimensions: " + tileSize + "x" + width + "x" + height);
        layers = List.copyOf(layers);
        entities = List.copyOf(entities);
        portals = portals == null ? List.of() : List.copyOf(portals);
    }

    /** Backward-compatible constructor for maps/builders that predate region portals. */
    public RMap(String name, int tileSize, int width, int height,
                List<TileLayer> layers, List<MapEntity> entities) {
        this(name, tileSize, width, height, layers, entities, List.of());
    }

    /**
     * Resolves the first portal whose circular trigger contains {@code position}.
     * Portals are 2D triggers: elevation is intentionally ignored so stairs and slopes
     * work under one trigger.
     */
    public Optional<MapPortal> portalAt(WorldPosition position) {
        for (MapPortal portal : portals) {
            double dx = position.x() - portal.position().x();
            double dy = position.y() - portal.position().y();
            if (dx * dx + dy * dy <= portal.radius() * portal.radius()) return Optional.of(portal);
        }
        return Optional.empty();
    }
}