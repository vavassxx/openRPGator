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
 * ({@link Notify}, {@link Dialog}) to the players that Lua scripts target.
 *
 * <p>Instance-based so it can be embedded either from the CLI ({@link ServerMain}) or from a
 * Swing admin UI, with lifecycle controlled through {@link #start}/{@link #stop}.
 */
public final class ServerHost {

    /** What a server run needs. Map/pak paths are already resolved (see {@link ServerConfig}). */
    public record Config(Path map, List<Path> paks, int port) {
        public Config { if (paks == null) paks = List.of(); }
    }

    /** Receives human-readable operational messages (map load, listener address, pak list). */
    public interface Listener {
        void log(String line);
        default void error(String line) { log("[error] " + line); }
    }

    private final Listener listener;
    private final Map<Long, Client> clients = new ConcurrentHashMap<>();
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private volatile GameRuntime runtime;
    private volatile boolean running;
    private ServerSocket server;
    private ScheduledExecutorService tick;
    private Map<String, Integer> sprites = Map.of();
    private volatile List<Path> pakFiles = List.of();

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
        sprites = Map.of();
        pakFiles = List.copyOf(cfg.paks());

        if (cfg.map() != null && Files.isRegularFile(cfg.map())) {
            try {
                runtime.loadMap(cfg.map());
                sprites = Sprites.byPrefab(runtime.map());
                listener.log("Loaded map: " + cfg.map().getFileName()
                        + " (" + sprites.size() + " prefabs)");
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

        tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(() -> {
            try { runtime.tick(); } catch (Throwable t) { t.printStackTrace(); }
        }, 0, 50, TimeUnit.MILLISECONDS);

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
            clients.put(entityId, client);
            if (!pakFiles.isEmpty()) PakStreamer.send(out, pakFiles);
            Protocol.write(out, new Welcome(entityId));

            while (running && !s.isClosed()) {
                Packet q = Protocol.read(in);
                if (q instanceof Input x) {
                    applyInput(entityId, x);
                } else if (q instanceof DialogResponse r) {
                    // Deliver on the next tick thread to stay thread-safe with Lua.
                    runtime.respondDialog(r.dialogId(), r.choice());
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
        Long id = runtime.world().spawn().value();
        runtime.world().entities().set(new EntityId(id), new Name(name));
        runtime.world().entities().set(new EntityId(id),
                new Transform(new WorldPosition(0, 0, 0), 0));
        return id;
    }

    private void destroyPlayer(Long id) {
        runtime.world().entities().destroy(new EntityId(id));
    }

    private void applyInput(Long entityId, Input x) {
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

    private void broadcastSnapshot() {
        List<Snapshot.EntityState> states = runtime.world().entities().entities().stream()
                .map(id -> {
                    var reg = runtime.world().entities();
                    var t = reg.get(id, Transform.class).orElse(null);
                    if (t == null) return null;
                    String prefab = reg.get(id, Prefab.class).map(Prefab::value)
                            .orElseGet(() -> reg.get(id, Name.class).map(Name::value).orElse(null));
                    int resource = Sprites.resourceOf(sprites, prefab);
                    return new Snapshot.EntityState(id.value(), t.position().x(),
                            t.position().y(), t.position().elevation(), resource);
                })
                .filter(Objects::nonNull)
                .toList();
        Snapshot snapshot = new Snapshot(states);
        for (Client c : clients.values()) c.send(snapshot);
    }

    private UiSink uiSink() {
        return new UiSink() {
            @Override public void broadcastNotify(String text) {
                for (Client c : clients.values()) c.send(new Notify(text));
            }
            @Override public void notifyTo(long playerEntityId, String text) {
                Client c = clients.get(playerEntityId);
                if (c != null) c.send(new Notify(text));
            }
            @Override public void dialogTo(long playerEntityId, long dialogId, String text,
                                           List<String> choices, UiSink.DialogCallback callback) {
                Client c = clients.get(playerEntityId);
                if (c != null) c.send(new Dialog(dialogId, text, choices));
            }
            @Override public void clearDialogs(long playerEntityId) { }
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