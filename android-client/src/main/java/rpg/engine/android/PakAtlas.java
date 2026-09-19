package rpg.engine.android;

import android.graphics.Bitmap;

import java.io.File;

import rpg.engine.pak.PakAssets;
import rpg.engine.pak.PakImage;

/**
 * Pak-backed texture atlas for the Android map editor. Loads {@code tile/*} and {@code sprite/*}
 * entries from the shared host folder ({@code data/host}, plus packs cached from remote servers)
 * and converts the raw RGBA rasters to {@link Bitmap}s. Indices match the runtime contract:
 * tile id / entity resource are indexes into the sorted {@code tile/*} / {@code sprite/*} arrays
 * (see {@link PakAssets}).
 */
final class PakAtlas {

    private PakAssets assets = PakAssets.empty();
    private Bitmap[] tiles = new Bitmap[0];
    private Bitmap[] sprites = new Bitmap[0];
    private String[] spriteKeys = new String[0];

    void loadDir(File[] pakFiles) {
        if (pakFiles == null || pakFiles.length == 0) {
            reset();
            return;
        }
        try {
            java.nio.file.Path[] paths = new java.nio.file.Path[pakFiles.length];
            for (int i = 0; i < pakFiles.length; i++) paths[i] = pakFiles[i].toPath();
            assets = PakAssets.fromPaks(paths);
            tiles = toBitmaps(assets.tileImages());
            sprites = toBitmaps(assets.spriteImages());
            spriteKeys = assets.spriteKeys();
        } catch (Exception e) {
            reset();
        }
    }

    private void reset() {
        assets = PakAssets.empty();
        tiles = new Bitmap[0];
        sprites = new Bitmap[0];
        spriteKeys = new String[0];
    }

    int tileCount() { return tiles.length; }
    int spriteCount() { return sprites.length; }
    String[] spriteKeys() { return spriteKeys; }

    Bitmap tile(int id) { return id >= 0 && id < tiles.length ? tiles[id] : null; }

    int spriteIndex(String prefab) {
        for (int i = 0; i < spriteKeys.length; i++) if (spriteKeys[i].equals(prefab)) return i;
        return -1;
    }

    Bitmap spriteImage(String prefab) {
        int i = spriteIndex(prefab);
        return i < 0 ? null : sprites[i];
    }

    /** Sprite by {@code Snapshot.EntityState.resource()} index (matches the sorted {@code sprite/*} order). */
    Bitmap spriteImageAt(int index) {
        return index >= 0 && index < sprites.length ? sprites[index] : null;
    }

    /** The special {@code sprite/player} entry, used for entities the server marks resource == -1. */
    Bitmap playerSprite() { return spriteImage("player"); }

    private static Bitmap[] toBitmaps(PakImage[] imgs) {
        Bitmap[] out = new Bitmap[imgs.length];
        for (int i = 0; i < imgs.length; i++) out[i] = toBitmap(imgs[i]);
        return out;
    }

    static Bitmap toBitmap(PakImage img) {
        int w = img.width(), h = img.height();
        byte[] rgba = img.rgba();
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int j = i * 4;
            int a = rgba[j + 3] & 0xff, r = rgba[j] & 0xff, g = rgba[j + 1] & 0xff, b = rgba[j + 2] & 0xff;
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }
}