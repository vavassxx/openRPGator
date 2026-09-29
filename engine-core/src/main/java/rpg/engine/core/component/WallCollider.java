package rpg.engine.core.component;

import rpg.engine.core.math.Vec2;

/** A finite vertical wall segment standing on the ground plane. */
public record WallCollider(Vec2 start, Vec2 end, double thickness, double bottom, double height) implements Collider {
    public WallCollider {
        if (start == null || end == null) throw new IllegalArgumentException("wall endpoints cannot be null");
        if (thickness < 0 || bottom < 0 || height < 0) throw new IllegalArgumentException("invalid wall dimensions");
        if (start.x() == end.x() && start.y() == end.y()) throw new IllegalArgumentException("wall needs a non-zero segment");
    }
    public double top() { return bottom + height; }
}
