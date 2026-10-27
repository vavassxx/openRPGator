package rpg.engine.map;

import org.junit.jupiter.api.Test;
import rpg.engine.core.math.WorldPosition;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RMapPortalIOTest {
    @Test void roundTripV3Portal() throws Exception {
        var p = Files.createTempFile("portal", ".rmap");
        var portal = new MapPortal("to_blackwood", new WorldPosition(4, 5, 0), 1.5,
                "blackwood", new WorldPosition(8, 9, 0));
        var map = new RMap("town", 32, 16, 16,
                List.of(new TileLayer("g", 16, 16, new int[256], false)), List.of(), List.of(portal));
        RMapIO.write(map, p);
        var read = RMapIO.read(p);
        assertEquals(List.of(portal), read.portals());
    }

    @Test void legacyV2StillHasNoPortals() throws Exception {
        var p = Files.createTempFile("legacy", ".rmap");
        var map = new RMap("old", 32, 2, 2,
                List.of(new TileLayer("g", 2, 2, new int[4], false)), List.of());
        RMapIO.write(map, p);
        byte[] bytes = Files.readAllBytes(p);
        bytes[7] = 2; // version int, big endian
        Files.write(p, bytes);
        assertTrue(RMapIO.read(p).portals().isEmpty());
    }
}
