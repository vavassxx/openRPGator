package rpg.engine.server;

import rpg.engine.runtime.*;
import rpg.engine.core.component.*;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.network.*;
import rpg.engine.pak.PakStreamer;
import rpg.engine.world.InteractRequestedEvent;
import rpg.engine.script.UiSink;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Headless authoritative server host. Owns one {@link GameRuntime}, accepts TCP clients,
 * broadcasts {@link Snapshot}s, routes {@link Input} into the world and pushes UI events
 * ({@link UiLayout}) to the players that Lua scripts target.
 *
 * <p>Instance-based so it can be embedded either from the CLI ({@link ServerMain}), a Swing
 * admin UI or the Android/desktop clients' local server, with lifecycle controlled through
 * {@link #start}/{@link #stop}. Uses only Java 17 APIs so the same class runs on Android.
 */
public final class ServerHost {

    /**
     * What a server run needs. Map/pak paths are already resolved (see {@link ServerConfig}).
     *
     * @param tickHz world tick rate in Hz (≡ 1000/tickHz ms per tick); 0 or negative falls back to
     *               the {@link ServerConfig#DEFAULT_TICK_HZ default}, always clamped to 1..240
     */
    public record Config(Path map, List<Path> paks, int port, int tickHz) {
        public Config {
            if (paks == null) paks = List.of();
            if (tickHz <= 0) tickHz = ServerConfig.DEFAULT_TICK_HZ;
            tickHz = Math.max(1, Math.min(240, tickHz));
        }
    }

    /** Receives human-readable operational messages (map load, listener address, pak list). */
    public interface Listener {
        void log(String line);
        default void error(String line) { log("[error] " + line); }
    }

    private final Listener listener;
    private final Map<Long, Client> clients = new ConcurrentHashMap<>();
    /**
     * One thread per connected client. A cached pool (not Java-21 virtual threads, which are
     * unavailable on Android) keeps this class runnable inside the Android client, where the same
     * server code is embedded via {@code LocalServerBackend}.
     */
    private ExecutorService exec;
    private volatile GameRuntime runtime;
    private volatile boolean running;
    private ServerSocket server;
    private ScheduledExecutorService tick;
    private static final String PLAYER_SPRITE = rpg.engine.runtime.Sprites.PLAYER;
    private volatile List<Path> pakFiles = List.of();
    /**
     * Serializes all access to {@link GameRuntime} (world mutate + snapshot broadcast). The ECS
     * is not thread-safe: world mutations come from the tick thread ({@code runtime.tick()},
     * Lua on_tick) and from per-client network threads ({@link #applyInput}, spawn/destroy,
     * dialog responses), while {@link #broadcastSnapshot} reads the whole world every tick.
     */
    private final Object worldLock = new Object();

    public ServerHost(Listener listener) {
        this.listener = listener == null ? line -> {} : listener;
    }

    public boolean isRunning() { return running; }
    public GameRuntime runtime() { return runtime; }
    public int port() { return server == null ? -1 : server.getLocalPort(); }

    public synchronized void start(Config cfg) throws IOException {
        if (running) return;
        runtime = new GameRuntime();
        runtime.setUiSink(uiSink());
        pakFiles = List.copyOf(cfg.paks());

        if (cfg.map() != null && Files.isRegularFile(cfg.map())) {
            try {
                runtime.loadMap(cfg.map());
                listener.log("Loaded map: " + cfg.map().getFileName());
            } catch (Exception e) {
                listener.error("Failed to load map " + cfg.map() + ": " + e.getMessage());
            }
        } else if (cfg.map() != null) {
            listener.error("Map file not found: " + cfg.map());
        }
        if (!pakFiles.isEmpty()) {
            listener.log("Will stream " + pakFiles.size() + " pak(s): "
                    + pakFiles.stream().map(p -> p.getFileName().toString()).toList());
        }

        long tickMs = Math.max(1, Math.round(1000.0 / cfg.tickHz()));
        tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(() -> {
            try {
                synchronized (worldLock) {
                    // Host-owned knowledge (who is connected) is handed to the script layer each
                    // tick; Lua decides what to do with it (HP, HUD, quests — all script-side).
                    runtime.setPlayers(clients.keySet());
                    runtime.tick();
                    // Entities move on their own (rat patrol, sky timer, Lua on_tick), not only
                    // in response to player input — broadcast the world every tick so clients
                    // see autonomous motion without the player having to move.
                    broadcastSnapshot();
                }
            } catch (Throwable t) { t.printStackTrace(); }
        }, 0, tickMs, TimeUnit.MILLISECONDS);
        listener.log("World tick @ " + cfg.tickHz() + " Hz (" + tickMs + " ms)");

        exec = Executors.newCachedThreadPool();
        server = new ServerSocket(cfg.port());
        server.setReuseAddress(true);
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "openrpg-dedicated-server");
        acceptor.setDaemon(true);
        acceptor.start();
        listener.log("RPG server listening on " + cfg.port());
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        exec.shutdownNow();
        if (tick != null) tick.shutdown();
        clients.clear();
        listener.log("Server stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                exec.submit(() -> client(s));
            } catch (IOException e) {
                if (running) listener.error("accept error: " + e.getMessage());
            }
        }
    }

    private void client(Socket s) {
        Client client = null;
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Packet hello = Protocol.read(in);
            if (!(hello instanceof Hello h)) return;

            Long entityId = spawnPlayer(h.name());
            client = new Client(entityId, s, out);
            if (!pakFiles.isEmpty()) PakStreamer.send(out, pakFiles);
            Protocol.write(out, new Welcome(entityId));
            // Register only after Welcome: the tick thread broadcasts snapshots to every client,
            // so a client must not be reachable before its handshake has completed.
            clients.put(entityId, client);

            while (running && !s.isClosed()) {
                Packet q = Protocol.read(in);
                if (q instanceof Input x) {
                    applyInput(entityId, x);
                } else if (q instanceof DialogResponse r) {
                    // Deliver under the world lock: Lua dialogs must not race the tick thread.
                    synchronized (worldLock) { runtime.respondDialog(r.dialogId(), r.choice()); }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (client != null) {
                clients.remove(client.entityId);
                client.dismiss();
                destroyPlayer(client.entityId);
            }
        }
    }

    private Long spawnPlayer(String name) {
        synchronized (worldLock) {
            Long id = runtime.world().spawn().value();
            runtime.world().entities().set(new EntityId(id), new Name(name));
            runtime.world().entities().set(new EntityId(id),
                    new Transform(new WorldPosition(0, 0, 0), 0));
            return id;
        }
    }

    private void destroyPlayer(Long id) {
        synchronized (worldLock) { runtime.world().entities().destroy(new EntityId(id)); }
    }

    private void applyInput(Long entityId, Input x) {
        synchronized (worldLock) {
        var e = new EntityId(entityId);
        var t = runtime.world().entities().get(e, Transform.class).orElseThrow();
        var desired = new WorldPosition(t.position().x() + x.dx() * 0.1,
                t.position().y() + x.dy() * 0.1, t.position().elevation());
        var moved = runtime.world().collision().move(e, desired);
        runtime.world().entities().set(e, new Transform(moved, t.rotation()));
        if (x.has(Input.INTERACT))
            runtime.world().interactTarget(moved, 2.0).ifPresent(target ->
                    runtime.world().events().emit(new InteractRequestedEvent(e, target)));
        broadcastSnapshot();
        }
    }

    private void broadcastSnapshot() {
        List<Snapshot.EntityState> states = runtime.world().entities().entities().stream()
                .map(id -> {
                    var reg = runtime.world().entities();
                    var t = reg.get(id, Transform.class).orElse(null);
                    if (t == null) return null;
                    String prefab = reg.get(id, Prefab.class).map(Prefab::value).orElse(null);
                    // Players carry a Name but no Prefab -> always the dedicated player sprite.
                    String sprite = (prefab == null || prefab.isBlank()) ? PLAYER_SPRITE : prefab;
                    double scale = reg.get(id, Scale.class).map(Scale::value).orElse(1.0);
                    return new Snapshot.EntityState(id.value(), t.position().x(),
                            t.position().y(), t.position().elevation(), sprite, scale);
                })
                .filter(Objects::nonNull)
                .toList();
        Snapshot snapshot = new Snapshot(states);
        for (Client c : clients.values()) c.send(snapshot);
    }

    private UiSink uiSink() {
        return new UiSink() {
            @Override public void broadcastNotify(String text) {
                for (Client c : clients.values()) c.send(UiLayout.notify(text));
            }
            @Override public void notifyTo(long playerEntityId, String text) {
                Client c = clients.get(playerEntityId);
                if (c != null) c.send(UiLayout.notify(text));
            }
            @Override public void dialogTo(long playerEntityId, long dialogId, String text,
                                           List<String> choices, UiSink.DialogCallback callback) {
                Client c = clients.get(playerEntityId);
                if (c != null) c.send(UiLayout.dialog(dialogId, text, choices));
            }
            @Override public void clearDialogs(long playerEntityId) { }
            @Override public void layoutTo(long playerEntityId, String layoutJson, List<String> strings) {
                Client c = clients.get(playerEntityId);
                if (c != null) c.send(UiLayout.layout(layoutJson, strings));
            }
        };
    }

    private static final class Client {
        final long entityId;
        final OutputStream out;
        volatile boolean dismissed;
        Client(long entityId, Socket s, OutputStream out) { this.entityId = entityId; this.out = out; }
        synchronized void send(Packet p) {
            if (dismissed) return;
            try { Protocol.write(out, p); } catch (IOException ignored) { dismissed = true; }
        }
        void dismiss() { dismissed = true; }
    }
}