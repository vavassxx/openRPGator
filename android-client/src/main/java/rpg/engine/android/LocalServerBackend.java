package rpg.engine.android;

import rpg.engine.runtime.GameRuntime;
import rpg.engine.runtime.Sprites;
import rpg.engine.core.component.Name;
import rpg.engine.core.component.Prefab;
import rpg.engine.core.component.Transform;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.network.*;
import rpg.engine.script.UiSink;
import rpg.engine.world.InteractRequestedEvent;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Android embedded authoritative server (full engine).
 *
 * Owns a {@link GameRuntime}: Lua scripts, collision, triggers and the deterministic tick
 * (20 Hz) all run here, mirroring {@code dedicated-server/ServerMain} and the desktop local
 * server. Lifts the {@code Input} from each client into the world with collision resolution,
 * broadcasts {@link Snapshot}s and routes Lua push-UI ({@link Notify}/{@link Dialog}) to the
 * owning player. Uses only Java 17 APIs for Android compatibility.
 */
final class LocalServerBackend {
    interface Listener { void status(String message); }
    private final Listener listener;
    private volatile boolean running;
    private ServerSocket server;
    private ExecutorService clients;
    private ScheduledExecutorService tick;
    private final Map<Long, Player> players = new ConcurrentHashMap<>();
    private final List<Player> clientList = new CopyOnWriteArrayList<>();
    private volatile GameRuntime runtime;
    private Path mapFile;
    private Map<String, Integer> sprites = Map.of();

    LocalServerBackend(Listener listener) { this.listener = listener; }

    synchronized void start(int port, Path map) throws IOException {
        if (running) return;
        mapFile = map;
        runtime = new GameRuntime();
        runtime.setUiSink(uiSink());
        if (mapFile != null && Files.isRegularFile(mapFile)) {
            try {
                runtime.loadMap(mapFile);
                sprites = Sprites.byPrefab(runtime.map());
                listener.status("Local server listening on " + port + " (" + mapFile.getFileName() + ")");
            } catch (Exception e) {
                listener.status("Local server: map load failed: " + e.getMessage());
            }
        } else {
            listener.status("Local server listening on " + port + " (no map)");
        }
        server = new ServerSocket(port);
        server.setReuseAddress(true);
        clients = Executors.newCachedThreadPool();
        tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(() -> {
            try { runtime.tick(); } catch (Throwable t) { t.printStackTrace(); }
        }, 0, 50, TimeUnit.MILLISECONDS);
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "openrpg-android-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (clients != null) clients.shutdownNow();
        if (tick != null) tick.shutdown();
        players.clear();
        clientList.clear();
        server = null;
        clients = null;
        runtime = null;
        listener.status("Local server stopped");
    }

    boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                clients.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) listener.status("Local server error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        Player player = null;
        GameRuntime rt = runtime; // local ref; stop() may null the field mid-handshake
        try (Socket s = socket) {
            if (rt == null) return;
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Packet hello = Protocol.read(in);
            if (!(hello instanceof Hello h)) return;
            long entityId = rt.world().spawn().value();
            EntityId id = new EntityId(entityId);
            rt.world().entities().set(id, new Name(h.name()));
            rt.world().entities().set(id, new Transform(new WorldPosition(0, 0, 0), 0));
            player = new Player(entityId, h.name(), s, out);
            players.put(entityId, player);
            clientList.add(player);
            Protocol.write(out, new Welcome(entityId));
            broadcastSnapshot(rt);
            while (running && !s.isClosed()) {
                Packet q = Protocol.read(in);
                if (q instanceof Input x) {
                    applyInput(rt, entityId, x);
                } else if (q instanceof DialogResponse r) {
                    rt.respondDialog(r.dialogId(), r.choice());
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (player != null) {
                players.remove(player.id);
                clientList.remove(player);
                if (rt != null) rt.world().entities().destroy(new EntityId(player.id));
                broadcastSnapshot(rt);
            }
        }
    }

    private void applyInput(GameRuntime rt, long entityId, Input x) {
        EntityId id = new EntityId(entityId);
        var t = rt.world().entities().get(id, Transform.class).orElseThrow();
        var desired = new WorldPosition(t.position().x() + x.dx() * 0.1,
                t.position().y() + x.dy() * 0.1, t.position().elevation());
        var moved = rt.world().collision().move(id, desired);
        rt.world().entities().set(id, new Transform(moved, t.rotation()));
        if (x.has(Input.INTERACT))
            rt.world().interactTarget(moved, 2.0).ifPresent(target ->
                    rt.world().events().emit(new InteractRequestedEvent(id, target)));
        broadcastSnapshot(rt);
    }

    private void broadcastSnapshot(GameRuntime rt) {
        List<Snapshot.EntityState> states = rt.world().entities().entities().stream()
                .map(eid -> {
                    var t = rt.world().entities().get(eid, Transform.class).orElse(null);
                    if (t == null) return null;
                    String prefab = rt.world().entities().get(eid, Prefab.class)
                            .map(Prefab::value).orElse(null);
                    if (prefab == null) prefab = rt.world().entities().get(eid, Name.class)
                            .map(Name::value).orElse(null);
                    int resource = Sprites.resourceOf(sprites, prefab);
                    return new Snapshot.EntityState(eid.value(), t.position().x(),
                            t.position().y(), t.position().elevation(), resource);
                })
                .filter(Objects::nonNull)
                .toList();
        Snapshot snapshot = new Snapshot(states);
        for (Player c : clientList) send(c, snapshot);
    }

    private void send(Player c, Packet p) {
        try { Protocol.write(c.out, p); } catch (IOException ignored) {}
    }

    private UiSink uiSink() {
        return new UiSink() {
            @Override public void broadcastNotify(String text) {
                for (Player c : clientList) send(c, new Notify(text));
            }
            @Override public void notifyTo(long playerEntityId, String text) {
                Player c = players.get(playerEntityId);
                if (c != null) send(c, new Notify(text));
            }
            @Override public void dialogTo(long playerEntityId, long dialogId, String text,
                                           List<String> choices, UiSink.DialogCallback callback) {
                Player c = players.get(playerEntityId);
                if (c != null) send(c, new Dialog(dialogId, text, choices));
            }
            @Override public void clearDialogs(long playerEntityId) { }
        };
    }

    private static final class Player {
        final long id; final String name; final Socket socket; final OutputStream out;
        Player(long id, String name, Socket socket, OutputStream out) { this.id = id; this.name = name; this.socket = socket; this.out = out; }
    }
}