package rpg.engine.editor.map;

import rpg.engine.pak.PakAssets;
import rpg.engine.pak.PakImage;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pak-backed visuals for the editor. Loads {@code tile/*} and {@code sprite/*} entries from one or
 * more {@code .pak} files and converts the raw RGBA rasters to AWT images for palettes and the
 * canvas. The zero-based indexes match the runtime contract: tile id / entity resource are indexes
 * into the sorted {@code tile/*} / {@code sprite/*} arrays.
 */
final class PakVisuals {

    private final List<Path> pakPaths = new ArrayList<>();
    private PakAssets assets = PakAssets.empty();
    private BufferedImage[] tileImages = new BufferedImage[0];
    private BufferedImage[] spriteImages = new BufferedImage[0];
    private String[] spriteKeys = new String[0];

    void add(Path pakPath) {
        if (pakPath != null && !pakPaths.contains(pakPath)) pakPaths.add(pakPath);
        reload();
    }

    void reload() {
        try {
            assets = pakPaths.isEmpty() ? PakAssets.empty()
                    : PakAssets.fromPaks(pakPaths.toArray(Path[]::new));
            tileImages = toImages(assets.tileImages());
            spriteImages = toImages(assets.spriteImages());
            spriteKeys = assets.spriteKeys();
        } catch (Exception e) {
            assets = PakAssets.empty();
            tileImages = new BufferedImage[0];
            spriteImages = new BufferedImage[0];
            spriteKeys = new String[0];
        }
    }

    List<Path> pakPaths() { return List.copyOf(pakPaths); }
    int tileCount() { return assets.tileCount(); }
    int spriteCount() { return assets.spriteCount(); }
    String[] spriteKeys() { return spriteKeys; }
    BufferedImage[] tileImages() { return tileImages; }
    BufferedImage[] spriteImages() { return spriteImages; }
    int spriteIndex(String prefab) {
        for (int i = 0; i < spriteKeys.length; i++) if (spriteKeys[i].equals(prefab)) return i;
        return -1;
    }
    BufferedImage tileImage(int id) {
        return id >= 0 && id < tileImages.length ? tileImages[id] : null;
    }
    BufferedImage spriteImage(int idx) {
        return idx >= 0 && idx < spriteImages.length ? spriteImages[idx] : null;
    }
    BufferedImage spriteImageFor(String prefab) {
        int i = spriteIndex(prefab);
        return i < 0 ? null : spriteImage(i);
    }

    private static BufferedImage[] toImages(PakImage[] src) {
        BufferedImage[] out = new BufferedImage[src.length];
        for (int i = 0; i < src.length; i++) out[i] = toAwt(src[i]);
        return out;
    }

    static BufferedImage toAwt(PakImage img) {
        BufferedImage out = new BufferedImage(img.width(), img.height(), BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[img.width() * img.height()];
        byte[] rgba = img.rgba();
        for (int i = 0; i < px.length; i++) {
            int j = i * 4;
            int a = rgba[j + 3] & 0xff, r = rgba[j] & 0xff, g = rgba[j + 1] & 0xff, b = rgba[j + 2] & 0xff;
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        out.setRGB(0, 0, img.width(), img.height(), px, 0, img.width());
        return out;
    }
}