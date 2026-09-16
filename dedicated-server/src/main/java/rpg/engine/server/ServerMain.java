package rpg.engine.server;

import rpg.engine.runtime.*;
import rpg.engine.core.component.*;
import rpg.engine.core.ecs.*;
import rpg.engine.core.math.*;
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
 * Headless authoritative server. Hosts one {@link GameRuntime}, accepts TCP clients,
 * broadcasts {@link Snapshot}s, routes {@link Input} into the world and pushes UI events
 * ({@link Notify}, {@link Dialog}) to the players that Lua scripts target.
 */
public final class ServerMain {

    private static final Map<Long, Client> clients = new ConcurrentHashMap<>();
    private static final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private static volatile GameRuntime runtime;
    private static volatile boolean running = true;

    private static Map<String, Integer> sprites = Map.of();
    private static volatile List<Path> pakFiles = List.of();

    public static void main(String[] args) throws Exception {
        Path map = null;
        int port = 27800;
        List<Path> paks = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--map")) map = Path.of(args[++i]);
            else if (args[i].equals("--port")) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--pak")) paks.add(Path.of(args[++i]));
            else if (args[i].equals("--help")) { System.out.println("--map FILE --port PORT --pak FILE.pak"); return; }
        }
        if (!paks.isEmpty()) System.out.println("Will stream " + paks.size() + " pak(s): "
                + paks.stream().map(p -> p.getFileName()).toList());
        pakFiles = List.copyOf(paks);

        runtime = new GameRuntime();
        runtime.setUiSink(uiSink());
        if (map != null) {
            try {
                runtime.loadMap(map);
                sprites = Sprites.byId(runtime.map());
                System.out.println("Loaded map: " + map.getFileName()
                        + " (" + sprites.size() + " sprite ids)");
            } catch (Exception e) {
                System.err.println("Failed to load map: " + e.getMessage());
            }
        }

        ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(() -> {
            try { runtime.tick(); } catch (Throwable t) { t.printStackTrace(); }
        }, 0, 50, TimeUnit.MILLISECONDS);

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("RPG server listening on " + port);
            while (running) {
                Socket s = server.accept();
                exec.submit(() -> client(s));
            }
        } finally {
            tick.shutdown();
        }
    }

    private static void client(Socket s) {
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

    private static Long spawnPlayer(String name) {
        Long id = runtime.world().spawn().value();
        runtime.world().entities().set(new EntityId(id), new Name(name));
        runtime.world().entities().set(new EntityId(id),
                new Transform(new WorldPosition(0, 0, 0), 0));
        return id;
    }

    private static void destroyPlayer(Long id) {
        runtime.world().entities().destroy(new EntityId(id));
    }

    private static void applyInput(Long entityId, Input x) {
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

    private static void broadcastSnapshot() {
        List<Snapshot.EntityState> states = runtime.world().entities().entities().stream()
                .map(id -> {
                    var reg = runtime.world().entities();
                    var t = reg.get(id, Transform.class).orElse(null);
                    if (t == null) return null;
                    String name = reg.get(id, Name.class).map(Name::value).orElse(null);
                    int resource = Sprites.resourceOf(sprites, name);
                    return new Snapshot.EntityState(id.value(), t.position().x(),
                            t.position().y(), t.position().elevation(), resource);
                })
                .filter(Objects::nonNull)
                .toList();
        Snapshot snapshot = new Snapshot(states);
        for (Client c : clients.values()) c.send(snapshot);
    }

    private static UiSink uiSink() {
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