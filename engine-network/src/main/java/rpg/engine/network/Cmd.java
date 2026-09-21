package rpg.engine.network;

/**
 * Client→server custom command (protocol type 12).
 *
 * <p>The <em>value</em> of a command is chosen by the host: UI widgets carry a {@code cmd}
 * number and an optional payload string, and the client forwards them verbatim. No engine or
 * client code knows what 9001 or "sword" mean — only the host Lua that registered
 * {@code engine.on_command} does. This is the "no hardcoding" channel that lets the server open
 * inventories and similar screens driven entirely by host scripts.
 */
public record Cmd(int code, String arg) implements Packet {
    public Cmd { if (arg == null) arg = ""; }
    public byte type() { return 12; }
}