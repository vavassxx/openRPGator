package rpg.engine.script.lua;

import org.junit.jupiter.api.Test;
import rpg.engine.core.component.Name;
import rpg.engine.core.component.Transform;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.script.PlayerStore;
import rpg.engine.world.GameWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerKeyTest {

    /** Records every save key; persists per key so load_player round-trips. */
    private static final class RecordingStore implements PlayerStore {
        final List<String> keys = new ArrayList<>();
        final Map<String, String> saves = new java.util.HashMap<>();
        @Override public String load(String userKey) { return saves.get(userKey); }
        @Override public void save(String userKey, String json) {
            keys.add(userKey);
            saves.put(userKey, json);
        }
    }

    private LuaRuntime runtime(String name) {
        GameWorld world = new GameWorld();
        LuaRuntime scripts = new LuaRuntime();
        scripts.bindWorld(world);
        EntityId player = world.spawn();
        world.entities().set(player, new Name(name));
        world.entities().set(player, new Transform(new WorldPosition(0, 0, 0), 0));
        scripts.globals().set("me", scripts.globals().load("return world.get(" + player.value() + ")", "get_me").call());
        return scripts;
    }

    @Test
    void saveAndLoadUseCaseInsensitiveKey() {
        RecordingStore store = new RecordingStore();
        LuaRuntime scripts = runtime("Astra");
        scripts.api().setPlayerStore(store);

        scripts.execute("engine.save_player(me, {hp = 80})", "save");
        assertEquals(List.of("astra"), store.keys, "key must be lowercased");

        // Reading with a differently-cased name still finds the same save via the same key.
        LuaRuntime other = runtime("ASTRA");
        other.api().setPlayerStore(store);
        other.execute("h = engine.load_player(me).hp", "load");
        assertEquals(80, other.globals().get("h").toint());
    }

    @Test
    void saveWithoutPlayerStoreIsNoop() {
        LuaRuntime scripts = runtime("Player");
        scripts.execute("engine.save_player(me, {hp = 1})", "save"); // must not throw
        assertTrue(true);
    }
}