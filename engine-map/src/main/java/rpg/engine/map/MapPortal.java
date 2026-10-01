package rpg.engine.map;

import rpg.engine.core.math.WorldPosition;

/** Server-side portal connecting one region to another region and destination position. */
public record MapPortal(
        String id,
        WorldPosition position,
        double radius,
        String targetRegion,
        WorldPosition targetPosition) {
    public MapPortal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("portal id is blank");
        if (position == null) throw new IllegalArgumentException("portal position is null");
        if (!Double.isFinite(radius) || radius <= 0) throw new IllegalArgumentException("invalid portal radius");
        if (targetRegion == null || targetRegion.isBlank()) throw new IllegalArgumentException("target region is blank");
        if (targetPosition == null) throw new IllegalArgumentException("target position is null");
    }
}
