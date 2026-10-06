package rpg.engine.world;

import rpg.engine.core.component.BoxCollider;
import rpg.engine.core.component.CircleCollider;
import rpg.engine.core.component.Collider;
import rpg.engine.core.component.PolygonCollider;
import rpg.engine.core.math.Vec2;
import rpg.engine.core.math.WorldPosition;

import java.util.List;

/**
 * Authoritative blocked-tile mask loaded from a map's collision layers.
 *
 * <p>The grid is immutable for the lifetime of the loaded map (the engine treats map assets as
 * immutable; runtime tile edits via {@code world.set_tile} target visual layers). Beyond the
 * grid's outer edge the world is closed: out-of-bounds positions are always blocked, which gives
 * every region finite world bounds for free.
 *
 * <p>Geometry is deliberately conservative: circles hit any blocked tile they touch,
 * boxes hit any blocked tile their AABB overlaps, and polygon colliders are approximated with
 * their axis-aligned bounding box. Walls are entity-level segments and are not part of the grid.
 */
public final class TileGrid {
    private final int width;
    private final int height;
    private final double tileSize;
    private final boolean[] blocked;

    public TileGrid(int width, int height, double tileSize, boolean[] blocked) {
        if (width <= 0 || height <= 0 || tileSize <= 0)
            throw new IllegalArgumentException("invalid tile grid dimensions");
        if (blocked == null || blocked.length != width * height)
            throw new IllegalArgumentException("bad blocked mask length");
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.blocked = java.util.Arrays.copyOf(blocked, blocked.length);
    }

    public int width() { return width; }
    public int height() { return height; }
    public double tileSize() { return tileSize; }

    /** True when the tile is blocked; tiles outside the grid are considered blocked. */
    public boolean blockedAt(int tileX, int tileY) {
        if (tileX < 0 || tileY < 0 || tileX >= width || tileY >= height) return true;
        return blocked[tileY * width + tileX];
    }

    /** True when the collider placed at {@code p} intersects a blocked tile or leaves the world. */
    public boolean overlaps(Collider collider, WorldPosition p) {
        if (collider instanceof CircleCollider c) return circleOverlaps(c.radius(), p.x(), p.y());
        if (collider instanceof BoxCollider b) return boxOverlaps(b.half().x(), b.half().y(), p.x(), p.y());
        if (collider instanceof PolygonCollider poly) {
            Vec2 box = polygonAabb(poly.vertices());
            return boxOverlaps(box.x(), box.y(), p.x(), p.y());
        }
        return false; // walls and unknown colliders are geometry, not tile actors
    }

    private double worldWidth() { return width * tileSize; }
    private double worldHeight() { return height * tileSize; }

    private boolean circleOverlaps(double radius, double cx, double cy) {
        if (cx - radius < 0 || cy - radius < 0 || cx + radius > worldWidth() || cy + radius > worldHeight())
            return true; // circle crosses the finite world border
        double r2 = radius * radius;
        int minTx = tileIndex(cx - radius), maxTx = tileIndex(cx + radius);
        int minTy = tileIndex(cy - radius), maxTy = tileIndex(cy + radius);
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                if (!blockedAt(tx, ty)) continue;
                double nearestX = clamp(cx, tx * tileSize, (tx + 1) * tileSize);
                double nearestY = clamp(cy, ty * tileSize, (ty + 1) * tileSize);
                double dx = cx - nearestX, dy = cy - nearestY;
                if (dx * dx + dy * dy <= r2) return true;
            }
        }
        return false;
    }

    private boolean boxOverlaps(double halfX, double halfY, double x, double y) {
        double minX = x - halfX, maxX = x + halfX;
        double minY = y - halfY, maxY = y + halfY;
        // The finite world bounds apply to the whole box, not just its centre.
        if (minX < 0 || minY < 0 || maxX > worldWidth() || maxY > worldHeight()) return true;
        int minTx = tileIndex(minX), maxTx = tileIndex(maxX);
        int minTy = tileIndex(minY), maxTy = tileIndex(maxY);
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                if (blockedAt(tx, ty)) return true;
            }
        }
        return false;
    }

    /** Axis-aligned half-extents of the polygon relative to its origin (conservative). */
    private static Vec2 polygonAabb(List<Vec2> vertices) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Vec2 v : vertices) {
            minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
            minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
        }
        return new Vec2(Math.max(0, (maxX - minX) * 0.5), Math.max(0, (maxY - minY) * 0.5));
    }

    /** Floor position/world-pixel to a tile index (safe for negative inputs). */
    private int tileIndex(double world) {
        return (int) Math.floor(world / tileSize);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}