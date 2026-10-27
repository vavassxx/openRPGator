package rpg.engine.map;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionCatalogTest {
    @Test void loadsRmapExtensionCaseInsensitivelyAndSortsIds() throws Exception {
        Path dir = Files.createTempDirectory("region-catalog");
        var map = new RMap("test", 32, 2, 2,
                List.of(new TileLayer("ground", 2, 2, new int[4], false)), List.of());
        RMapIO.write(map, dir.resolve("zeta.RMAP"));
        RMapIO.write(map, dir.resolve("default.rmap"));
        var catalog = RegionCatalog.load(dir);
        assertEquals(List.of("default", "zeta"), catalog.ids().stream().toList());
        assertTrue(catalog.find("ZETA").isPresent());
    }
}
