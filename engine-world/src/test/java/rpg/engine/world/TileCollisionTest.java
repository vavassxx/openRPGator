package rpg.engine.world;

import org.junit.jupiter.api.Test;
import rpg.engine.core.component.BoxCollider;
import rpg.engine.core.component.CircleCollider;
import rpg.engine.core.component.PolygonCollider;
import rpg.engine.core.component.Transform;
import rpg.engine.core.math.Vec2;
import rpg.engine.core.math.WorldPosition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TileCollisionTest {

    /** 4x4 grid, tile size 1; only tile (2,1) is blocked. */
    private static TileGrid grid() {
        boolean[] blocked = new boolean[16];
        blocked[1 * 4 + 2] = true;
        return new TileGrid(4, 4, 1.0, blocked);
    }

    @Test
    void blockedAtIsOutOfBoundsSafe() {
        TileGrid g = grid();
        assertFalse(g.blockedAt(0, 0));
        assertTrue(g.blockedAt(2, 1));
        assertTrue(g.blockedAt(4, 0));   // right of grid
        assertTrue(g.blockedAt(0, -1));  // above grid
    }

    @Test
    void circleIsBlockedByTileAndByOutOfBounds() {
        var w = new GameWorld();
        w.collision().setTileGrid(grid());
        var e = w.spawn();
        w.entities().set(e, new CircleCollider(0.35));

        assertTrue(w.collision().canOccupy(e, new WorldPosition(1.5, 1.5, 0)));
        assertFalse(w.collision().canOccupy(e, new WorldPosition(2.5, 1.5, 0))); // inside blocked tile
        assertTrue(w.collision().canOccupy(e, new WorldPosition(2.5, 2.5, 0)));
        assertFalse(w.collision().canOccupy(e, new WorldPosition(-1, 1.5, 0)));  // outside world
        assertFalse(w.collision().canOccupy(e, new WorldPosition(3.8, 1.5, 0))); // circle crosses border
    }

    @Test
    void boxIsBlockedByTileOverlap() {
        var w = new GameWorld();
        w.collision().setTileGrid(grid());
        var e = w.spawn();
        w.entities().set(e, new BoxCollider(new Vec2(0.4, 0.4)));

        assertTrue(w.collision().canOccupy(e, new WorldPosition(1.5, 1.5, 0)));
        // Box AABB [2.1..2.9] overlaps blocked tile (2,1)
        assertFalse(w.collision().canOccupy(e, new WorldPosition(2.5, 1.5, 0)));
        assertTrue(w.collision().canOccupy(e, new WorldPosition(2.5, 2.5, 0)));
    }

    @Test
    void moveSlidesAlongBlockedRow() {
        boolean[] blocked = new boolean[16];
        for (int tx = 0; tx < 4; tx++) blocked[2 * 4 + tx] = true; // entire row y=2 blocked
        var w = new GameWorld();
        w.collision().setTileGrid(new TileGrid(4, 4, 1.0, blocked));
        var e = w.spawn();
        // Circle radius 0.35 snug below the wall: maxY = 1.99 clears the blocked row.
        w.entities().set(e, new Transform(new WorldPosition(0.5, 1.64, 0), 0));
        w.entities().set(e, new CircleCollider(0.35));

        // Walking right-and-up into the wall: the x axis slides, y stays pinned below it.
        WorldPosition moved = w.collision().move(e, new WorldPosition(3.5, 2.5, 0));
        assertEquals(3.5, moved.x(), 1e-9, "x should slide along the wall");
        assertEquals(1.64, moved.y(), 1e-9, "y should be stopped by the blocked row");
        assertTrue(w.collision().canOccupy(e, moved), "landed position must itself be occupiable");
    }

    @Test
    void moveRespectsFiniteBounds() {
        var w = new GameWorld();
        w.collision().setTileGrid(grid());
        var e = w.spawn();
        w.entities().set(e, new Transform(new WorldPosition(0.5, 2.0, 0), 0));
        w.entities().set(e, new CircleCollider(0.35));

        WorldPosition moved = w.collision().move(e, new WorldPosition(-0.5, 2.0, 0));
        assertEquals(0.5, moved.x(), 1e-9); // left border is impassable
        assertEquals(2.0, moved.y(), 1e-9);
    }

    @Test
    void polygonUsesConservativeAabb() {
        var w = new GameWorld();
        w.collision().setTileGrid(grid());
        var e = w.spawn();
        w.entities().set(e, new PolygonCollider(List.of(new Vec2(-0.5, 0), new Vec2(0.5, 0), new Vec2(0, 0.5))));

        assertFalse(w.collision().canOccupy(e, new WorldPosition(2.0, 0.9, 0))); // AABB reaches blocked tile (2,1)
        assertTrue(w.collision().canOccupy(e, new WorldPosition(0.5, 3.4, 0)));
    }

    @Test
    void removingGridRestoresUnboundedBehaviour() {
        var w = new GameWorld();
        w.collision().setTileGrid(grid());
        var e = w.spawn();
        w.entities().set(e, new CircleCollider(0.35));
        w.collision().setTileGrid(null);
        assertTrue(w.collision().canOccupy(e, new WorldPosition(100, 100, 0)));
        assertTrue(w.collision().canOccupy(e, new WorldPosition(2.5, 1.5, 0)));
    }

    @Test
    void invalidGridIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TileGrid(0, 4, 1, new boolean[16]));
        assertThrows(IllegalArgumentException.class, () -> new TileGrid(4, 4, -1, new boolean[16]));
        assertThrows(IllegalArgumentException.class, () -> new TileGrid(4, 4, 1, new boolean[8]));
        assertThrows(IllegalArgumentException.class, () -> new TileGrid(4, 4, 1, null));
    }
}