package rpg.engine.client;

import rpg.engine.core.io.DataDir;
import rpg.engine.server.ServerConfig;
import rpg.engine.server.ServerHost;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * In-process authoritative server for the desktop client — the same {@link ServerHost} code the
 * dedicated CLI and the Android client run (single source of truth, no re-implementation).
 *
 * <p>Content comes from the host folder ({@code data/host}): the single {@code *.rmap} is
 * auto-selected, every {@code *.pak} is streamed to clients during the handshake and Lua scripts
 * next to the map run in the world ({@link ServerConfig} resolves all of it exactly like on the
 * dedicated server). The server ticks at the configured rate (default 20 Hz), so timed entities —
 * patrols, periodic globals such as the demo sky conductor — move and fire just like on the
 * dedicated server.
 */
final class DesktopLocalServer {

    private final ServerHost host;
    private Path hostDir;
    private int tickRate = ServerConfig.DEFAULT_TICK_HZ;

    DesktopLocalServer() {
        this.host = new ServerHost(System.err::println);
    }

    /**
     * Host folder ({@code data/host}): holds the auto-discovered {@code *.rmap} map, Lua scripts
     * next to it and the {@code *.pak} packs streamed to clients during the handshake.
     */
    void setHostDir(Path dir) { this.hostDir = dir; }
    Path hostDir() { return hostDir; }

    /** World tick rate in Hz (default 20). Set before {@link #start}. */
    void setTickRate(int hz) { this.tickRate = hz; }

    /** Path of the single automatically-selected host map, or null. */
    Path autoMapPath() {
        if (hostDir == null) return null;
        List<Path> maps = DataDir.listIn(hostDir, ".rmap");
        return maps.isEmpty() ? null : maps.get(0);
    }

    /** Starts the server from the host folder configured via {@link #setHostDir}. */
    synchronized void start(int port) throws IOException {
        Path dataDir = hostDir != null ? hostDir.getParent() : DataDir.root();
        ServerHost.Config cfg = ServerConfig.resolve(dataDir, port, tickRate, System.err::println);
        host.start(cfg);
    }

    void stop() { host.stop(); }
    boolean isRunning() { return host.isRunning(); }
}