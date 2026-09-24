package rpg.engine.network;

/**
 * Server→client ground map stream (protocol type 14).
 *
 * <p>Sent right after {@link Welcome}: the authoritative host pushes its own ground layer so the
 * client never has to read a {@code .rmap} file. Without a connection (or a server without a
 * map) no {@link MapPacket} arrives and the client shows an empty floor.
 *
 * @param width  map width in tiles
 * @param height map height in tiles
 * @param tiles  row-major ground tile ids ({@code tiles[y * width + x]})
 */
public record MapPacket(int width, int height, int[] tiles) implements Packet {
    public byte type() { return 14; }
}