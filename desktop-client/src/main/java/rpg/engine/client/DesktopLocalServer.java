package rpg.engine.client;

import rpg.engine.runtime.GameRuntime;
import rpg.engine.runtime.Sprites;
import rpg.engine.pak.PakStreamer;
import rpg.engine.core.component.*;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.network.*;
import rpg.engine.script.UiSink;
import rpg.engine.world.InteractRequestedEvent;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * In-process authoritative server for the desktop client.
 * Mirrors {@code dedicated-server/ServerMain} but runs inside the client JVM.
 * Owns a {@link GameRuntime}; Lua scripts, collision and triggers run here.
 */
final class DesktopLocalServer {

    private volatile boolean running;
    private ServerSocket server;
    private ExecutorService clients;
    private final Map<Long, ClientRecord> players = new ConcurrentHashMap<>();
    private final List<ClientRecord> clientList = new CopyOnWriteArrayList<>();
    private GameRuntime runtime;
    private Path resourceDir;
    private Map<String, Integer> sprites = Map.of();
    private record ClientRecord(long entityId, Socket socket, OutputStream out, String name) {}

    /** Asset root for future {@code .pak} client assets; unused by the current binary RMAP pipeline. */
    void setResourceDir(Path dir) { this.resourceDir = dir; }
    Path resourceDir() { return resourceDir; }

    /** Re-derives the entity-id → sprite mapping after {@code runtime().loadMap(...)}. */
    void refreshSprites() {
        sprites = (runtime != null && runtime.map() != null)
                ? Sprites.byId(runtime.map()) : Map.of();
    }

    private List<Path> localPaks() {
        if (resourceDir == null) return List.of();
        try (var st = Files.list(resourceDir)) {
            return st.filter(p -> p.toString().toLowerCase().endsWith(".pak"))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    synchronized void start(int port) throws IOException {
        if (running) return;
        if (runtime == null) {
            runtime = new GameRuntime();
            runtime.setUiSink(buildUiSink());
        }
        server = new ServerSocket(port);
        server.setReuseAddress(true);
        clients = Executors.newCachedThreadPool();
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "openrpg-local-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (clients != null) clients.shutdownNow();
        players.clear();
        clientList.clear();
        runtime = null; // rebuilt fresh on next start so map/world state does not persist
    }

    boolean isRunning() { return running; }
    GameRuntime runtime() { return runtime; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                clients.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) System.err.println("[local-server] accept error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        ClientRecord player = null;
        GameRuntime rt = runtime; // local ref; start() may replace runtime after handle begins
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
            player = new ClientRecord(entityId, s, out, h.name());
            players.put(entityId, player);
            clientList.add(player);
            List<Path> paks = localPaks();
            if (!paks.isEmpty()) PakStreamer.send(out, paks);
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
                players.remove(player.entityId());
                clientList.remove(player);
                if (rt != null) rt.world().entities().destroy(new EntityId(player.entityId()));
                broadcastSnapshot(rt);
            }
        }
    }

    private void applyInput(GameRuntime rt, long entityId, Input x) {
        EntityId id = new EntityId(entityId);
        var t = rt.world().entities().get(id, Transform.class).orElseThrow();
        var desired = new WorldPosition(t.position().x() + x.dx() * 0.1,
                t.position().y() + x.dy() * 0.1, t.position().elevation());
        var moved = runtime.world().collision().move(id, desired);
        runtime.world().entities().set(id, new Transform(moved, t.rotation()));
        if (x.has(Input.INTERACT))
            runtime.world().interactTarget(moved, 2.0).ifPresent(target ->
                    runtime.world().events().emit(new InteractRequestedEvent(id, target)));
        broadcastSnapshot(rt);
    }

    private void broadcastSnapshot(GameRuntime rt) {
        List<Snapshot.EntityState> states = rt.world().entities().entities().stream()
                .map(eid -> {
                    var t = rt.world().entities().get(eid, Transform.class).orElse(null);
                    if (t == null) return null;
                    String name = rt.world().entities().get(eid, Name.class)
                            .map(Name::value).orElse(null);
                    int resource = Sprites.resourceOf(sprites, name);
                    return new Snapshot.EntityState(eid.value(), t.position().x(),
                            t.position().y(), t.position().elevation(), resource);
                })
                .filter(Objects::nonNull)
                .toList();
        Snapshot snapshot = new Snapshot(states);
        for (ClientRecord c : clientList) send(c, snapshot);
    }

    private void send(ClientRecord c, Packet p) {
        try { Protocol.write(c.out(), p); } catch (IOException ignored) {}
    }

    private UiSink buildUiSink() {
        return new UiSink() {
            @Override public void broadcastNotify(String text) {
                for (ClientRecord c : clientList) send(c, new Notify(text));
            }
            @Override public void notifyTo(long playerEntityId, String text) {
                ClientRecord c = players.get(playerEntityId);
                if (c != null) send(c, new Notify(text));
            }
            @Override public void dialogTo(long playerEntityId, long dialogId, String text,
                                           List<String> choices, UiSink.DialogCallback callback) {
                ClientRecord c = players.get(playerEntityId);
                if (c != null) send(c, new Dialog(dialogId, text, choices));
            }
            @Override public void clearDialogs(long playerEntityId) { }
        };
    }
}