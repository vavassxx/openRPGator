package rpg.engine.network;

/**
 * Client→server response to a {@link Dialog}. {@code choice} is 0-based
 * (matches the index in {@code Dialog.choices()}).
 */
public record DialogResponse(long dialogId, int choice) implements Packet {
    public byte type() { return 7; }
}