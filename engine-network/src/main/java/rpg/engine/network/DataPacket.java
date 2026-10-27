package rpg.engine.network;

/** Host-to-client application data channel (protocol type 16). */
public record DataPacket(String channel, String payload) implements Packet {
    public DataPacket {
        if (channel == null || channel.isBlank() || channel.length() > 128)
            throw new IllegalArgumentException("invalid data channel");
        if (payload == null) payload = "";
        if (payload.length() > 60000)
            throw new IllegalArgumentException("data payload exceeds 60000 characters");
    }

    @Override public byte type() { return 16; }
}
