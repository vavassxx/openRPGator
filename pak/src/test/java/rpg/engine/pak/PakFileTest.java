package rpg.engine.pak;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PakFileTest {
    @TempDir Path tmp;

    private static PakImage img(int w, int h, int fill) {
        byte[] raw = new byte[w * h * 4];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (fill + i);
        return new PakImage(w, h, raw);
    }

    @Test
    void writeAndReadRoundtrip() throws Exception {
        Map<String, PakImage> named = new LinkedHashMap<>();
        named.put("sprite/player", img(2, 2, 10));
        named.put("tile/grass", img(1, 1, 40));
        named.put("ui/panel", img(4, 2, 70));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PakFile.write(bytes, named);

        Path file = tmp.resolve("t.pak");
        Files.write(file, bytes.toByteArray());

        PakFile index = PakFile.readIndex(file);
        assertEquals(3, index.entries().size());
        assertEquals("sprite/player", index.entries().get(0).name());
        assertEquals("tile/grass", index.entries().get(1).name());
        assertEquals("ui/panel", index.entries().get(2).name());

        PakImage grass = PakFile.loadImage(file, index.entry("tile/grass"));
        assertEquals(1, grass.width());
        assertEquals(1, grass.height());
        assertEquals(4, grass.bytes());
    }

    @Test
    void sortedResourceKeys() throws Exception {
        Map<String, PakImage> named = new LinkedHashMap<>();
        named.put("sprite/b", img(1, 1, 1));
        named.put("sprite/a", img(1, 1, 2));
        named.put("tile/x", img(1, 1, 3));
        Path file = tmp.resolve("s.pak");
        PakFile.write(file, named);
        PakFile index = PakFile.readIndex(file);
        assertEquals(java.util.List.of("sprite/a", "sprite/b"), index.namesByPrefix("sprite/"));
        assertEquals(java.util.List.of("tile/x"), index.namesByPrefix("tile/"));
    }

    @Test
    void rejectsGarbageAndWrongVersion() throws Exception {
        Path bad = tmp.resolve("bad.pak");
        Files.write(bad, new byte[]{'P', 'K', '0', '2', 0, 0, 0, 1, 0, 0, 0, 0});
        assertThrows(java.io.IOException.class, () -> PakFile.readIndex(bad));
        Path garbage = tmp.resolve("g.pak");
        Files.write(garbage, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(java.io.IOException.class, () -> PakFile.readIndex(garbage));
    }

    @Test
    void assetsLoadOnlyTileAndSprite() throws Exception {
        Map<String, PakImage> named = new LinkedHashMap<>();
        named.put("sprite/player", img(2, 2, 1));
        named.put("tile/5", img(1, 1, 2));
        named.put("ui/logo", img(1, 1, 3));
        Path file = tmp.resolve("b.pak");
        PakFile.write(file, named);

        PakAssets assets = PakAssets.fromPaks(file);
        assertEquals(1, assets.tileCount());
        assertEquals(1, assets.spriteCount());
        assertEquals(0, assets.playerSpriteIndex()); // "player" sorts as the only sprite key
        assertNotNull(assets.tileImage(0));
        assertNull(assets.spriteImage(1));
        assertNotNull(assets.spriteImage(0));
    }
}