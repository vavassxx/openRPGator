package rpg.engine.map;

import org.junit.jupiter.api.Test;
import rpg.engine.core.math.WorldPosition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RMapTest {

    private static RMap map(MapPortal... portals) {
        return new RMap("t", 32, 16, 16,
                List.of(new TileLayer("g", 16, 16, new int[256], false)),
                List.of(), List.of(portals));
    }

    @Test
    void rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new RMap("t", 0, 16, 16, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RMap("t", 32, 0, 16, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RMap("t", 32, 16, -1, List.of(), List.of()));
    }

    @Test
    void portalAtFindsTriggerContainingPosition() {
        var portal = new MapPortal("to_forest", new WorldPosition(4, 5, 0), 1.5,
                "forest", new WorldPosition(8, 9, 0));
        RMap map = map(portal);

        assertEquals(portal, map.portalAt(new WorldPosition(4, 5, 0)).orElseThrow());
        assertEquals(portal, map.portalAt(new WorldPosition(5.0, 6.0, 99)).orElseThrow()); // inside radius, elevation ignored
        assertTrue(map.portalAt(new WorldPosition(5.2, 6.0, 0)).isEmpty()); // just outside radius
        assertTrue(map.portalAt(new WorldPosition(-10, -10, 0)).isEmpty());
    }

    @Test
    void portalAtSelectsFirstMatchingWhenOverlapping() {
        var a = new MapPortal("a", new WorldPosition(0, 0, 0), 2.0, "a", new WorldPosition(1, 1, 0));
        var b = new MapPortal("b", new WorldPosition(0, 0, 0), 2.0, "b", new WorldPosition(2, 2, 0));
        assertEquals(a, map(a, b).portalAt(new WorldPosition(0, 0, 0)).orElseThrow());
    }

    @Test
    void mapWithoutPortalsHasNoLookup() {
        assertTrue(map().portalAt(new WorldPosition(0, 0, 0)).isEmpty());
    }

    @Test
    void backwardCompatibleConstructorDefaultsToNoPortals() {
        RMap legacy = new RMap("old", 32, 4, 4,
                List.of(new TileLayer("g", 4, 4, new int[16], false)), List.of());
        assertTrue(legacy.portals().isEmpty());
        assertTrue(legacy.portalAt(new WorldPosition(0, 0, 0)).isEmpty());
    }
}