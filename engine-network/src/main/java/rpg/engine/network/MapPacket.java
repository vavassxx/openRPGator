package rpg.engine.network;

/**
 * Server→client ground map stream (protocol type 14).
 *
 * <p>Sent right after {@link Welcome}: the authoritative host pushes its own ground layer so the
 * client never has to read a {@code .rmap} file. Without a connection (or a server without a
 * map) no {@link MapPacket} arrives and the client shows an empty floor.
 *
 * <p>The packet may also carry the region's initial camera zoom ({@code 0} = not specified).
 * It is appended after the tiles, so older clients that only know the three-argument form simply
 * ignore the extra bytes, while new clients read it when present. The same packet is re-sent on a
 * region transition, which makes the destination map and its zoom arrive together.
 *
 * @param width  map width in tiles
 * @param height map height in tiles
 * @param tiles  row-major ground tile ids ({@code tiles[y * width + x]})
 * @param zoom   initial camera zoom factor, or {@code 0} when the host did not set one
 */
public record MapPacket(int width, int height, int[] tiles, double zoom) implements Packet {
    public MapPacket {
        if (width < 0 || height < 0)
            throw new IllegalArgumentException("invalid map dimensions: " + width + "x" + height);
        if (tiles == null || (long) width * height != tiles.length)
            throw new IllegalArgumentException("bad map tile count");
        if (zoom != 0 && (!(zoom > 0) || !Double.isFinite(zoom)))
            throw new IllegalArgumentException("invalid camera zoom: " + zoom);
    }

    /** Backward-compatible map packet without a camera zoom. */
    public MapPacket(int width, int height, int[] tiles) {
        this(width, height, tiles, 0);
    }

    /** True when the host sent an explicit initial zoom for this map/region. */
    public boolean hasZoom() { return zoom > 0; }

    public byte type() { return 14; }
}
