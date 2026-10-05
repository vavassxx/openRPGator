package rpg.engine.server;

import rpg.engine.runtime.*;
import rpg.engine.core.component.*;
import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.network.*;
import rpg.engine.pak.PakStreamer;
import rpg.engine.world.InteractRequestedEvent;
import rpg.engine.script.UiSink;
import rpg.engine.map.RegionCatalog;
import rpg.engine.map.MapPortal;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Headless authoritative server host. Owns connected player sessions and one or more
 * independently simulated regions. Persistent player state remains host/script-owned;
 * region migration moves only the region-local ECS representation.
 */
public final class ServerHost {
    public record Config(Path map, List<Path> paks, int port, int tickHz, Path dataDir) {
        public Config {
            if (paks == null) paks = List.of();
            if (tickHz <= 0) tickHz = ServerConfig.DEFAULT_TICK_HZ;
            tickHz = Math.max(1, Math.min(240, tickHz));
        }
    }

    public interface Listener {
        void log(String line);
        default void error(String line) { log("[error] " + line); }
    }

    private final Listener listener;
    private final Map<Long, Client> clients = new ConcurrentHashMap<>();
    private final RegionManager regions = new RegionManager();
    private ExecutorService exec;
    private volatile boolean running;
    private ServerSocket server;
    private ScheduledExecutorService tick;
    private volatile List<Path> pakFiles = List.of();
    private volatile Path fontDir;
    private final Object worldLock = new Object();
    private FilePlayerStore playerStore;

    public ServerHost(Listener listener) { this.listener = listener == null ? line -> {} : listener; }

    private static void migrateLegacyPlayerStore(Path legacyDir, Path playerDir) {
        if (!Files.isDirectory(legacyDir)) return;
        try {
            Files.createDirectories(playerDir);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(legacyDir)) {
                for (Path file : files) {
                    Path target = playerDir.resolve(file.getFileName().toString());
                    if (Files.isRegularFile(file) && !Files.exists(target)) Files.move(file, target);
                }
            }
            try { Files.delete(legacyDir); } catch (DirectoryNotEmptyException ignored) { }
        } catch (IOException e) {
            System.err.println("[save] legacy player-store migration failed: " + e.getMessage());
        }
    }

    public boolean isRunning() { return running; }

    /** Compatibility accessor for single-region embedders; returns the default region if present. */
    public GameRuntime runtime() {
        Optional<RegionRuntime> r = regions.find("default");
        if (r.isPresent()) return r.get().runtime();
        return regions.all().stream().findFirst().map(RegionRuntime::runtime).orElse(null);
    }

    public RegionManager regions() { return regions; }
    public int port() { return server == null ? -1 : server.getLocalPort(); }

    public synchronized void start(Config cfg) throws IOException {
        if (running) return;
        regions.clear();
        Path hostDir = cfg.dataDir() == null ? null : cfg.dataDir().resolve("host");
        if (hostDir != null) Files.createDirectories(hostDir);

        if (cfg.dataDir() != null) {
            Path playerDir = hostDir.resolve("players");
            migrateLegacyPlayerStore(cfg.dataDir().resolve("players"), playerDir);
            playerStore = new FilePlayerStore(playerDir);
        }

        pakFiles = List.copyOf(cfg.paks());
        fontDir = hostDir == null ? null : hostDir.resolve("fonts");
        if (!Files.isDirectory(fontDir) && cfg.map() != null && cfg.map().getParent() != null)
            fontDir = cfg.map().getParent().resolve("fonts");

        Path regionDir = hostDir == null ? null : hostDir.resolve("regions");
        boolean loadedRegions = false;
        if (regionDir != null && Files.isDirectory(regionDir)) {
            try (var paths = Files.list(regionDir)) {
                loadedRegions = paths.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rmap"));
            }
        }

        if (loadedRegions) {
            try {
                regions.load(RegionCatalog.load(regionDir));
                listener.log("Loaded " + regions.ids().size() + " region(s) from " + regionDir);
            } catch (Exception e) {
                listener.error("Failed to load regions: " + e.getMessage());
                throw new IOException("region catalog load failed", e);
            }
        } else if (cfg.map() != null && Files.isRegularFile(cfg.map())) {
            RegionRuntime fallback = new RegionRuntime("default", 1L << 48);
            fallback.loadMap(cfg.map());
            regions.add(fallback);
            listener.log("Loaded legacy single map: " + cfg.map().getFileName());
        } else if (cfg.map() != null) {
            listener.error("Map file not found: " + cfg.map());
        }

        for (RegionRuntime region : regions.all()) {
            region.runtime().setUiSink(uiSink());
            region.runtime().setPlayerStore(playerStore);
            region.scripts().api().setRegionTransitionSink((ignored, id, target, x, y, z) ->
                    transitionPlayer(id, target, new WorldPosition(x, y, z)));
        }

        if (!pakFiles.isEmpty()) {
            listener.log("Will stream " + pakFiles.size() + " pak(s): " +
                    pakFiles.stream().map(p -> p.getFileName().toString()).toList());
        }

        long tickMs = Math.max(1, Math.round(1000.0 / cfg.tickHz()));
        tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(() -> {
            try {
                synchronized (worldLock) {
                    for (RegionRuntime region : regions.all())
                        region.runtime().setPlayers(playersIn(region.id()));
                    regions.tick(null);
                    broadcastSnapshots();
                }
            } catch (Throwable t) { t.printStackTrace(); }
        }, 0, tickMs, TimeUnit.MILLISECONDS);
        listener.log("World tick @ " + cfg.tickHz() + " Hz across " + regions.ids().size() + " region(s) (" + tickMs + " ms)");

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
        if (exec != null) exec.shutdownNow();
        if (tick != null) tick.shutdown();
        synchronized (worldLock) {
            for (Client c : new ArrayList<>(clients.values())) {
                RegionRuntime region = regions.find(c.regionId).orElse(null);
                if (region != null) region.runtime().scripts().api().dispatchDisconnect(c.entityId);
            }
            for (Client c : new ArrayList<>(clients.values())) destroyPlayer(c);
            clients.clear();
        }
        regions.clear();
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
            synchronized (worldLock) { client = spawnPlayer(h.name(), out, s); }
            if (!pakFiles.isEmpty()) PakStreamer.send(out, pakFiles);
            Protocol.write(out, new Welcome(client.entityId));
            sendFonts(out, fontDir);
            clients.put(client.entityId, client);
            sendMap(client);
            while (running && !s.isClosed()) {
                Packet q = Protocol.read(in);
                if (q instanceof Input x) applyInput(client.entityId, x);
                else if (q instanceof DialogResponse r) {
                    synchronized (worldLock) currentRuntime(client.entityId).respondDialog(r.dialogId(), r.choice());
                } else if (q instanceof Cmd c) {
                    synchronized (worldLock) currentRuntime(client.entityId).dispatchCommand(client.entityId, c.code(), c.arg());
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (client != null) {
                clients.remove(client.entityId);
                client.dismiss();
                synchronized (worldLock) {
                    RegionRuntime region = regions.find(client.regionId).orElse(null);
                    if (region != null) region.scripts().api().dispatchDisconnect(client.entityId);
                    destroyPlayer(client);
                }
            }
        }
    }

    private Client spawnPlayer(String name, OutputStream out, Socket socket) {
        RegionRuntime region = initialRegion();
        if (region == null) throw new IllegalStateException("server has no loaded regions");
        long id = region.world().spawn().value();
        EntityId e = new EntityId(id);
        region.world().entities().set(e, new Name(name));
        WorldPosition spawn = region.scripts().api().spawnPoint();
        region.world().entities().set(e, new Transform(spawn, 0));
        region.world().entities().set(e, new CircleCollider(0.35));
        return new Client(id, name, region.id(), socket, out);
    }

    private void destroyPlayer(Client c) {
        RegionRuntime region = regions.find(c.regionId).orElse(null);
        if (region != null) region.world().entities().destroy(new EntityId(c.entityId));
    }

    private RegionRuntime initialRegion() {
        return regions.find("default").orElseGet(() -> regions.all().stream().findFirst().orElse(null));
    }

    private GameRuntime currentRuntime(long entityId) {
        Client c = clients.get(entityId);
        if (c == null) throw new IllegalArgumentException("unknown player: " + entityId);
        return regions.require(c.regionId).runtime();
    }

    private Set<Long> playersIn(String regionId) {
        Set<Long> ids = new HashSet<>();
        for (Client c : clients.values()) if (regionId.equals(c.regionId) && !c.dismissed) ids.add(c.entityId);
        return ids;
    }

    private void applyInput(long entityId, Input x) {
        synchronized (worldLock) {
            Client c = clients.get(entityId);
            if (c == null) return;
            RegionRuntime region = regions.require(c.regionId);
            var e = new EntityId(entityId);
            var t = region.world().entities().get(e, Transform.class).orElseThrow();
            var desired = new WorldPosition(t.position().x() + x.dx() * 0.1,
                    t.position().y() + x.dy() * 0.1, t.position().elevation());
            var moved = region.world().collision().move(e, desired);
            region.world().entities().set(e, new Transform(moved, t.rotation()));
            if (x.has(Input.INTERACT))
                region.world().interactTarget(moved, 2.0).ifPresent(target ->
                        region.world().events().emit(new InteractRequestedEvent(e, target)));
            if (x.has(Input.PRIMARY)) region.runtime().dispatchAction(entityId, "primary");
            if (x.has(Input.SECONDARY)) region.runtime().dispatchAction(entityId, "secondary");
            if (x.has(Input.INVENTORY)) region.runtime().dispatchAction(entityId, "inventory");

            Optional<MapPortal> portal = regions.portalAt(region.id(), moved);
            portal.ifPresent(p -> transitionPlayer(entityId, p.targetRegion(), p.targetPosition()));
            broadcastSnapshots();
        }
    }

    private boolean transitionPlayer(long entityId, String targetRegion, WorldPosition target) {
        Client c = clients.get(entityId);
        if (c == null) return false;
        String from = c.regionId;
        String to = targetRegion == null ? null : targetRegion.trim().toLowerCase(Locale.ROOT);
        if (to == null || to.isBlank() || from.equals(to)) return false;
        RegionRuntime destination = regions.find(to).orElse(null);
        if (destination == null) return false;

        boolean moved = regions.migrate(new EntityId(entityId), from, to, target);
        if (!moved) return false;
        c.regionId = to;

        // Reconstruct only the host-owned session representation needed by the destination world.
        // Inventory/progression/etc. are deliberately untouched; scripts decide how persistent
        // state is restored or transformed.
        EntityId e = new EntityId(entityId);
        destination.world().entities().set(e, new Name(c.name));
        destination.world().entities().set(e, new CircleCollider(0.35));
        destination.runtime().setPlayers(playersIn(to));
        regions.find(from).ifPresent(r -> r.runtime().setPlayers(playersIn(from)));
        sendMap(c);
        listener.log("Player " + c.name + " transitioned " + from + " -> " + to);
        return true;
    }

    private void broadcastSnapshots() {
        Map<String, List<Snapshot.EntityState>> byRegion = new HashMap<>();
        for (RegionRuntime region : regions.all()) {
            var reg = region.world().entities();
            List<Snapshot.EntityState> states = reg.entities().stream().map(id -> {
                var t = reg.get(id, Transform.class).orElse(null);
                if (t == null) return null;
                String prefab = reg.get(id, Prefab.class).map(Prefab::value).orElse(null);
                String sprite = (prefab == null || prefab.isBlank()) ? rpg.engine.runtime.Sprites.PLAYER : prefab;
                double scale = reg.get(id, Scale.class).map(Scale::value).orElse(1.0);
                return new Snapshot.EntityState(id.value(), t.position().x(), t.position().y(),
                        t.position().elevation(), sprite, scale);
            }).filter(Objects::nonNull).toList();
            byRegion.put(region.id(), states);
        }
        for (Client c : clients.values()) {
            List<Snapshot.EntityState> states = byRegion.getOrDefault(c.regionId, List.of());
            c.send(new Snapshot(states));
        }
    }

    private UiSink uiSink() {
        return new UiSink() {
            @Override public void broadcastNotify(String text) { for (Client c : clients.values()) c.send(UiLayout.notify(text)); }
            @Override public void notifyTo(long id, String text) { Client c=clients.get(id); if(c!=null)c.send(UiLayout.notify(text)); }
            @Override public void dialogTo(long id,long dialogId,String text,List<String> choices,UiSink.DialogCallback cb) { Client c=clients.get(id); if(c!=null)c.send(UiLayout.dialog(dialogId,text,choices)); }
            @Override public void clearDialogs(long id) { }
            @Override public void layoutTo(long id,String json,List<String> strings) { Client c=clients.get(id); if(c!=null)c.send(UiLayout.layout(json,strings)); }
            @Override public void scriptTo(long id,String name,String source) { Client c=clients.get(id); if(c!=null)c.send(new Script(name,source)); }
        };
    }

    private void sendMap(Client c) {
        RegionRuntime region = regions.find(c.regionId).orElse(null);
        if (region == null || region.map() == null || region.map().layers().isEmpty()) return;
        var ground = region.map().layers().get(0);
        c.send(new MapPacket(ground.width(), ground.height(), ground.tiles()));
    }

    private void sendFonts(OutputStream out, Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var paths = Files.list(dir)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                String n=p.getFileName().toString(), lower=n.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".ttf") || lower.endsWith(".otf"))) continue;
                byte[] data=Files.readAllBytes(p);
                if(data.length>(1<<19)){listener.error("Font too large, skipped: "+n);continue;}
                Protocol.write(out,new Font(n,data));
            }
        } catch(IOException e){listener.error("Font streaming failed: "+e.getMessage());}
    }

    private static final class Client {
        final long entityId;
        final String name;
        volatile String regionId;
        final OutputStream out;
        volatile boolean dismissed;
        Client(long entityId,String name,String regionId,Socket socket,OutputStream out){this.entityId=entityId;this.name=name;this.regionId=regionId;this.out=out;}
        synchronized void send(Packet p){if(dismissed)return;try{Protocol.write(out,p);}catch(IOException ignored){dismissed=true;}}
        void dismiss(){dismissed=true;}
    }
}
