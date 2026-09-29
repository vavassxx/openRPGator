package rpg.engine.network;

/** Server-pushed TrueType/OpenType font resource for custom UI. */
public record Font(String name, byte[] data) implements Packet {
    public Font { data = data == null ? new byte[0] : data.clone(); }
    public byte type() { return 15; }
}
