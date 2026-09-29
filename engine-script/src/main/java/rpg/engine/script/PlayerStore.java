package rpg.engine.script;

/** Persistent per-user JSON storage exposed to server-side Lua. */
public interface PlayerStore {
    /** Returns the previously saved JSON document, or null when the user has no save. */
    String load(String userKey);
    /** Replaces the user's JSON document. Implementations must be safe to call on the tick thread. */
    void save(String userKey, String json);
}
