package rpg.engine.android;

import rpg.engine.server.ServerConfig;
import rpg.engine.server.ServerHost;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Android embedded authoritative server — the actual PC server code.
 *
 * <p>This is a thin Android wrapper around {@link ServerHost}, the same server the dedicated CLI
 * and the Swing admin console run (see {@code dedicated-server}). Server content comes from the
 * {@code data/host} folder: the single auto-selected {@code *.rmap}, every {@code *.pak} pack
 * streamed during the handshake and the Lua scripts next to the map. {@link ServerConfig}
 * resolves the host folder exactly like on the desktop, so the server, the local server and the
 * clients all agree on the host layout. Only Java 17 APIs (Android-compatible).
 */
final class LocalServerBackend {
    interface Listener { void status(String message); }
    private final Listener listener;
    private final ServerHost host;
    private int tickRate = ServerConfig.DEFAULT_TICK_HZ;

    LocalServerBackend(Listener listener) {
        this.listener = listener;
        this.host = new ServerHost(line -> listener.status(line));
    }

    /** World tick rate in Hz (default 20). Set before {@link #start}. */
    void setTickRate(int hz) { this.tickRate = hz; }

    /** Starts the server on the shared data dir; content auto-loads from {@code <data>/host}. */
    synchronized void start(int port, Path dataDir) throws IOException {
        ServerHost.Config cfg = ServerConfig.resolve(dataDir, port, tickRate, this.listener::status);
        host.start(cfg);
    }

    void stop() { host.stop(); }
    boolean isRunning() { return host.isRunning(); }
}