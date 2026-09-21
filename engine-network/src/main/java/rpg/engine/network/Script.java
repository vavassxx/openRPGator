package rpg.engine.network;

/**
 * Server→client Lua script push (protocol type 11).
 *
 * <p>The payload is the raw Lua source of a small <em>client-side</em> UI script ("mini-sandbox"):
 * it runs in a restricted client runtime and drives the custom HUD (inventory, spellbooks, …)
 * that the host composes. The script talks only through the narrow {@code ui.*} API — it can
 * rebuild the widget overlay ({@code ui.layout}), forward custom commands to the server
 * ({@code ui.send} → {@link Cmd}) and react to button presses ({@code ui.on_command}). The map
 * meaning (HP, items, …) stays host-side; the client remains a dumb renderer.
 */
public record Script(String name, String source) implements Packet {
    public byte type() { return 11; }
}