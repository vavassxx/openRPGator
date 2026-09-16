package rpg.engine.network;

/**
 * Server→client one-shot toast notification. Clients show it non-modally and auto-dismiss.
 */
public record Notify(String text) implements Packet {
    public byte type() { return 5; }
}