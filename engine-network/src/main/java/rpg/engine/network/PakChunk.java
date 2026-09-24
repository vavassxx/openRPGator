package rpg.engine.network;

/**
 * Server→client chunk of a {@code .pak} asset pack being streamed during the handshake.
 * Each chunk is a byte slice of the raw file at {@code offset}. The client stitches them
 * in order (offsets are strictly monotonically increasing per {@code name}).
 */
public record PakChunk(String name, int offset, byte[] data) implements Packet {
    public byte type() { return 9; }
}