package rpg.engine.server;

import rpg.engine.core.io.DataDir;

import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds a {@link ServerHost.Config} from the shared desktop data directory
 * (default {@code ~/.openrpgator/data}). Both the CLI ({@link ServerMain}) and the Swing server
 * admin resolve through this, so the server, the local server inside the client and the editors
 * all agree on where the host folder lives.
 *
 * <p>The map is no longer configured by hand: the server auto-discovers the single
 * {@code *.rmap} in {@code <data-dir>/host} (warnings for zero/multiple) and streams every
 * {@code *.pak} from the same folder. Lua scripts sitting next to the map are pulled in by
 * {@code GameRuntime.loadMap}.
 */
public final class ServerConfig {
    private ServerConfig() {}

    /** Default world tick rate in Hz used when the host does not pick one. */
    public static final int DEFAULT_TICK_HZ = 20;

    /** {@link #resolve(Path, int, int, Consumer)} at the default tick rate. */
    public static ServerHost.Config resolve(Path dataDir, int port, Consumer<String> warn) {
        return resolve(dataDir, port, DEFAULT_TICK_HZ, warn);
    }

    public static ServerHost.Config resolve(Path dataDir, int port, int tickHz, Consumer<String> warn) {
        Path hostDir = dataDir.resolve("host");
        java.util.function.Consumer<String> w = warn == null ? s -> {} : warn;

        List<Path> maps = DataDir.listIn(hostDir, ".rmap");
        Path map = null;
        if (maps.size() == 1) {
            map = maps.get(0);
        } else if (maps.isEmpty()) {
            w.accept("No map in " + hostDir + " — server starts without a map");
        } else {
            w.accept("Multiple maps in " + hostDir + "; using " + maps.get(0).getFileName());
            map = maps.get(0);
        }

        List<Path> paks = DataDir.listIn(hostDir, ".pak");
        return new ServerHost.Config(map, paks, port, tickHz);
    }
}