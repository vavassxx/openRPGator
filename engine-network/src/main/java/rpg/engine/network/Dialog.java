package rpg.engine.network;

import java.util.List;

/**
 * Server→client modal dialog with optional choices. {@code dialogId} is echoed back
 * in a {@link DialogResponse}; {@code choices} may be empty for a plain message box.
 */
public record Dialog(long dialogId, String text, List<String> choices) implements Packet {
    public byte type() { return 6; }
    public boolean hasChoices() { return choices != null && !choices.isEmpty(); }
}