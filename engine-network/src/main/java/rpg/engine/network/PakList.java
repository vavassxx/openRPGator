package rpg.engine.network;

import java.util.*;

/**
 * Server→client handshake header listing the {@code .pak} asset packs the client must load
 * before the game starts. Sent before {@link Welcome}. The client may skip re-downloading
 * values it already has cached (by name + size); the server still streams every pack —
 * caching happens purely on the client side.
 *
 * @param packs {@code [name, sizeBytes]} pairs in stream order
 */
public record PakList(List<PakSeq> packs) implements Packet {
    public byte type() { return 8; }

    public record PakSeq(String name, int sizeBytes) {}
}