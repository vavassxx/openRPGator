package rpg.engine.script;

import java.util.List;

/**
 * Server-side sink for push UI events (notifications, choice dialogs) issued from Lua scripts.
 * The embedded server (dedicated-server, desktop local server) implements this and routes
 * the events to the network connection(s) that own the referenced player entity.
 *
 * Implementations must be thread-safe: Lua handlers run on the tick thread while client
 * connections are served on other threads. A no-op sink keeps the API usable without a network.
 */
public interface UiSink {
    /** Broadcasts a toast notification to all connected clients. */
    void broadcastNotify(String text);

    /** Sends a non-modal toast notification to a single player identified by their entity id. */
    void notifyTo(long playerEntityId, String text);

    /** Opens a modal dialog for the player. The answer arrives via {@link DialogCallback}. */
    void dialogTo(long playerEntityId, long dialogId, String text, List<String> choices, DialogCallback callback);

    /**
     * Pushes a host-driven widget layout (see {@code UiLayout.layout(String, List)}) to a single
     * player. The layout is a plain JSON widget array plus its strings — the client renders it
     * blindly, it never knows the game meaning. No-op by default.
     */
    default void layoutTo(long playerEntityId, String layoutJson, List<String> strings) { }

    /**
     * Pushes a small client-side Lua UI script ("mini-sandbox") to a player. The script runs in
     * a restricted client runtime ({@code ui.*} API only) and drives the custom HUD the host
     * composes. No-op by default.
     */
    default void scriptTo(long playerEntityId, String name, String source) { }

    /** Called by the network layer when a player disconnects, to discard pending dialogs. */
    void clearDialogs(long playerEntityId);

    /** Callback invoked (possibly on a connection thread) when the player answers a dialog. */
    interface DialogCallback {
        void onAnswer(int choice);
    }
}