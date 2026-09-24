package rpg.engine.pak;

import rpg.engine.network.PakList;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Client-side asset manager built from loaded {@link PakFile}s.
 * Provides a zero-based array for tile rasters (indexed by tile id) and sprite rasters addressed
 * BY NAME (via {@link #spriteImage(String)}), both derived from the sorted {@code tile/*} and
 * {@code sprite/*} entries in the combined asset packs.
 */
public final class PakAssets {

    private final PakImage[] tiles;
    private final PakImage[] sprites;
    private final String[] spriteKeys;
    private final Map<String, Integer> spriteIndex;
    private final int playerSpriteIndex;
    private final int totalTileBytes;
    private final int totalSpriteBytes;

    private PakAssets(PakImage[] tiles, PakImage[] sprites, String[] spriteKeys,
                      Map<String, Integer> spriteIndex, int playerSpriteIndex,
                      int totalTileBytes, int totalSpriteBytes) {
        this.tiles = tiles;
        this.sprites = sprites;
        this.spriteKeys = spriteKeys;
        this.spriteIndex = spriteIndex;
        this.playerSpriteIndex = playerSpriteIndex;
        this.totalTileBytes = totalTileBytes;
        this.totalSpriteBytes = totalSpriteBytes;
    }

    public PakImage tileImage(int id) { return id >= 0 && id < tiles.length ? tiles[id] : null; }
    public PakImage spriteImage(int id) { return id >= 0 && id < sprites.length ? sprites[id] : null; }
    /** Sprite by NAME ({@code sprite/*} short key, e.g. {@code "player"}); {@code null} when absent. */
    public PakImage spriteImage(String key) {
        if (key == null) return null;
        Integer i = spriteIndex.get(key);
        return i == null ? null : sprites[i];
    }
    /** Index of a sprite by name, or -1 when the pak has no such entry. */
    public int spriteIndex(String key) {
        Integer i = key == null ? null : spriteIndex.get(key);
        return i == null ? -1 : i;
    }
    public int tileCount() { return tiles.length; }
    public int spriteCount() { return sprites.length; }
    public int totalTileBytes() { return totalTileBytes; }
    public int totalSpriteBytes() { return totalSpriteBytes; }
    public PakImage[] tileImages() { return tiles; }
    public PakImage[] spriteImages() { return sprites; }
    /** Sorted short keys of {@code sprite/*} entries (e.g. {@code player}); index == sprite id. */
    public String[] spriteKeys() { return spriteKeys; }
    /** Index of the {@code sprite/player} entry, or -1. */
    public int playerSpriteIndex() { return playerSpriteIndex; }

    /** Empty assets — no packs loaded. */
    public static PakAssets empty() {
        return new PakAssets(new PakImage[0], new PakImage[0], new String[0], Map.of(), -1, 0, 0);
    }

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
        Map<String, Integer> spriteIndex = new HashMap<>();
        for (int i = 0; i < spriteKeys.length; i++) spriteIndex.put(spriteKeys[i], i);
        int pIdx = spriteIndex.getOrDefault("player", -1);
        int tBytes = 0, sBytes = 0;
        for (PakImage p : tiles) tBytes += p.bytes();
        for (PakImage p : sprites) sBytes += p.bytes();
        return new PakAssets(tiles, sprites, spriteKeys, spriteIndex, pIdx, tBytes, sBytes);
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