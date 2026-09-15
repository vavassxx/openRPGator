package rpg.engine.runtime;

import rpg.engine.map.*;
import rpg.engine.world.GameWorld;
import rpg.engine.script.lua.LuaRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GameRuntime {
    private final GameWorld world = new GameWorld();
    private final LuaRuntime scripts = new LuaRuntime();
    { scripts.bindWorld(world); }
    public GameWorld world() { return world; }
    public LuaRuntime scripts() { return scripts; }
    public void loadMap(Path path) throws IOException {
        RMap map = RMapIO.read(path);
        for (MapEntity e : map.entities()) {
            var id = world.spawn();
            world.entities().set(id, new rpg.engine.core.component.Name(e.id()));
            world.entities().set(id, new rpg.engine.core.component.Transform(e.position(), 0));
            if (e.script() != null) scripts.execute(Files.readString(Path.of(e.script())), e.script());
        }
    }
    public void executeScript(String source, String name) { scripts.execute(source, name); }
    public void tick() { world.step(); scripts.api().setTick(world.tick()); }
}
