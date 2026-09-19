package rpg.engine.pak;

import rpg.engine.network.PakList;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Client-side asset manager built from loaded {@link PakFile}s.
 * Provides zero-based arrays for tile raster images (indexed by tile id) and sprite raster
 * images (indexed by {@code Snapshot.EntityState.resource()}), both derived from the sorted
 * {@code tile/*} and {@code sprite/*} entries in the combined asset packs.
 */
public final class PakAssets {

    private final PakImage[] tiles;
    private final PakImage[] sprites;
    private final String[] spriteKeys;
    private final int playerSpriteIndex;
    private final int totalTileBytes;
    private final int totalSpriteBytes;

    private PakAssets(PakImage[] tiles, PakImage[] sprites, String[] spriteKeys,
                      int playerSpriteIndex, int totalTileBytes, int totalSpriteBytes) {
        this.tiles = tiles;
        this.sprites = sprites;
        this.spriteKeys = spriteKeys;
        this.playerSpriteIndex = playerSpriteIndex;
        this.totalTileBytes = totalTileBytes;
        this.totalSpriteBytes = totalSpriteBytes;
    }

    public PakImage tileImage(int id) { return id >= 0 && id < tiles.length ? tiles[id] : null; }
    public PakImage spriteImage(int id) { return id >= 0 && id < sprites.length ? sprites[id] : null; }
    public int tileCount() { return tiles.length; }
    public int spriteCount() { return sprites.length; }
    public int totalTileBytes() { return totalTileBytes; }
    public int totalSpriteBytes() { return totalSpriteBytes; }
    public PakImage[] tileImages() { return tiles; }
    public PakImage[] spriteImages() { return sprites; }
    /** Sorted short keys of {@code sprite/*} entries (e.g. {@code player}), index == sprite id. */
    public String[] spriteKeys() { return spriteKeys; }
    /** Index of the {@code sprite/player} entry (for entities with resource == -1), or -1. */
    public int playerSpriteIndex() { return playerSpriteIndex; }

    /** Empty assets — no packs loaded. */
    public static PakAssets empty() { return new PakAssets(new PakImage[0], new PakImage[0], new String[0], -1, 0, 0); }

    /**
     * Reads a pak file, loads {@code tile/*} and {@code sprite/*} entries into zero-based
     * arrays. Duplicate names across multiple paks are resolved by later-is-later (last wins).
     */
    public static PakAssets fromPaks(Path... pakFiles) throws IOException {
        TreeMap<String, PakImage> tileMap = new TreeMap<>();
        TreeMap<String, PakImage> spriteMap = new TreeMap<>();
        for (Path path : pakFiles) {
            if (!Files.exists(path)) continue;
            PakFile pak = PakFile.readIndex(path);
            for (PakFile.Entry e : pak.entries()) {
                PakImage img = PakFile.loadImage(path, e);
                if (e.name().startsWith("tile/")) {
                    tileMap.put(e.name().substring(5), img);
                } else if (e.name().startsWith("sprite/")) {
                    spriteMap.put(e.name().substring(7), img);
                }
            }
        }
        PakImage[] tiles = tileMap.values().toArray(PakImage[]::new);
        PakImage[] sprites = spriteMap.values().toArray(PakImage[]::new);
        String[] spriteKeys = spriteMap.keySet().toArray(String[]::new);
        int pIdx = -1;
        for (int i = 0; i < spriteKeys.length; i++) if ("player".equals(spriteKeys[i])) { pIdx = i; break; }
        int tBytes = 0, sBytes = 0;
        for (PakImage p : tiles) tBytes += p.bytes();
        for (PakImage p : sprites) sBytes += p.bytes();
        return new PakAssets(tiles, sprites, spriteKeys, pIdx, tBytes, sBytes);
    }

    /**
     * Maps each {@code sprite/*} short key (sorted union across paks) to its zero-based sprite
     * index — exactly the ordering the client builds from {@link #fromPaks}. Servers use this to
     * emit {@code Snapshot.EntityState.resource()} values that always match the client's sprite
     * array, even when a pak is missing some entries (e.g. {@code sprite/7} absent).
     */
    public static Map<String, Integer> spriteKeyIndex(Path... pakFiles) throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        for (Path path : pakFiles) {
            if (path == null || !Files.exists(path)) continue;
            for (String n : PakFile.readIndex(path).namesByPrefix("sprite/")) keys.add(n.substring(7));
        }
        Map<String, Integer> out = new HashMap<>();
        int i = 0;
        for (String k : keys) out.put(k, i++);
        return out;
    }

    /**
     * Quick scan: returns the number of tile and sprite entries that would be loaded from
     * these pak files, without decoding the actual RGBA rasters (index-only read).
     */
    public static int[] scanCounts(Path... pakFiles) throws IOException {
        int tiles = 0, sprites = 0;
        for (Path path : pakFiles) {
            if (!Files.exists(path)) continue;
            PakFile pak = PakFile.readIndex(path);
            for (PakFile.Entry e : pak.entries()) {
                if (e.name().startsWith("tile/")) tiles++;
                else if (e.name().startsWith("sprite/")) sprites++;
            }
        }
        return new int[]{tiles, sprites};
    }
}