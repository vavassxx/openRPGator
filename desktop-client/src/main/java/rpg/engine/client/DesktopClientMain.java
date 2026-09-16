package rpg.engine.client;

import rpg.engine.render.desktop.LwjglRenderer;
import rpg.engine.runtime.GameRuntime;
import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.map.TileLayer;
import rpg.engine.network.Snapshot;
import rpg.engine.network.Welcome;
import rpg.engine.network.Input;
import rpg.engine.core.component.Transform;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Desktop game client — connects to a server, renders isometric tiles from an .rmap file,
 * receives snapshots for entity state, and sends keyboard input.
 *
 * Usage:
 *   openRPGator [options] [map.rmap]
 *     --local-server [port]       Start in-process local server (default port 27991)
 *     --connect host:port         Connect to server
 *     --name player               Player name (default: "player")
 *     --zoom factor               Initial tile zoom (default: auto-fit)
 */
public final class DesktopClientMain {

    private static final int DEFAULT_PORT = 27991;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final String DEFAULT_NAME = "player";

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".openrpgator");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("client.properties");

    public static void main(String[] args) throws Exception {
        // ── Parse arguments ──────────────────────────────────────
        Path mapPath = null;
        String connectTo = null;
        String playerName = null;
        Integer localPort = null;
        Double zoomArg = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--local-server" -> {
                    localPort = DEFAULT_PORT;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        try { localPort = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) { }
                    }
                }
                case "--connect" -> { if (i + 1 < args.length) connectTo = args[++i]; }
                case "--name" -> { if (i + 1 < args.length) playerName = args[++i]; }
                case "--zoom" -> {
                    if (i + 1 < args.length) {
                        try { zoomArg = Double.parseDouble(args[++i]); } catch (NumberFormatException ignored) { }
                    }
                }
                default -> { if (args[i].endsWith(".rmap") && Files.exists(Path.of(args[i]))) mapPath = Path.of(args[i]); }
            }
        }

        // ── Load saved config ────────────────────────────────────
        Properties cfg = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try (InputStream in = Files.newInputStream(CONFIG_FILE)) { cfg.load(in); }
        }
        if (playerName == null) playerName = cfg.getProperty("name", DEFAULT_NAME);
        if (connectTo == null) connectTo = cfg.getProperty("connect");

        // ── Start local server if requested ──────────────────────
        DesktopLocalServer localServer = null;
        if (localPort != null) {
            localServer = new DesktopLocalServer(s -> System.out.println("[local-server] " + s));
            localServer.start(localPort);
        }

        // ── Load map ─────────────────────────────────────────────
        GameRuntime runtime = new GameRuntime();
        RMap map = null;
        if (mapPath != null) {
            try {
                map = RMapIO.read(mapPath);
                System.out.println("Loaded map: " + map.name() + " (" + map.width() + "×" + map.height() + ", " + map.layers().size() + " layers)");
                runtime.loadMap(mapPath);
            } catch (Exception e) {
                System.err.println("Failed to load map: " + e.getMessage());
            }
        }

        // ── Networking state ─────────────────────────────────────
        final AtomicReference<Snapshot> latestSnapshot = new AtomicReference<>(new Snapshot(java.util.List.of()));
        final AtomicLong localPlayerId = new AtomicLong(-1);

        DesktopClientSession session = new DesktopClientSession(new DesktopClientSession.Listener() {
            @Override public void onConnected(Welcome w) {
                localPlayerId.set(w.entityId());
                System.out.println("Connected as entity #" + w.entityId());
            }
            @Override public void onSnapshot(Snapshot s) { latestSnapshot.set(s); }
            @Override public void onStatus(String s) { System.out.println("[network] " + s); }
        });

        if (connectTo != null) {
            String[] parts = connectTo.contains(":") ? connectTo.split(":", 2) : new String[]{connectTo, String.valueOf(DEFAULT_PORT)};
            String host = parts[0].isEmpty() ? DEFAULT_HOST : parts[0];
            int port;
            try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { port = DEFAULT_PORT; }
            session.connect(host, port, playerName);
            System.out.println("Connecting to " + host + ":" + port + " as " + playerName + " ...");
        }

        // ── Renderer ─────────────────────────────────────────────
        try (LwjglRenderer renderer = new LwjglRenderer(1280, 720, "openRPGator")) {
            if (zoomArg != null) renderer.setZoom(zoomArg);
            boolean autoFitted = zoomArg != null || map == null;

            while (!renderer.shouldClose()) {
                renderer.poll();
                int w = renderer.framebufferWidth(), h = renderer.framebufferHeight();

                // ── Keyboard → movement ──────────────────────────
                double dx = 0, dy = 0;
                int actions = 0;
                if (renderer.keyDown(GLFW_KEY_D) || renderer.keyDown(GLFW_KEY_RIGHT)) dx += 1;
                if (renderer.keyDown(GLFW_KEY_A) || renderer.keyDown(GLFW_KEY_LEFT)) dx -= 1;
                if (renderer.keyDown(GLFW_KEY_S) || renderer.keyDown(GLFW_KEY_DOWN)) dy += 1;
                if (renderer.keyDown(GLFW_KEY_W) || renderer.keyDown(GLFW_KEY_UP)) dy -= 1;
                if (renderer.keyDown(GLFW_KEY_J)) actions |= Input.PRIMARY;
                if (renderer.keyDown(GLFW_KEY_K)) actions |= Input.SECONDARY;
                if (renderer.keyDown(GLFW_KEY_L)) actions |= Input.INTERACT;
                if (renderer.keyDown(GLFW_KEY_I)) actions |= Input.INVENTORY;
                session.input(dx, dy, actions);

                // ── Zoom: +/- keys ───────────────────────────────
                if (renderer.keyDown(GLFW_KEY_EQUAL) || renderer.keyDown(GLFW_KEY_KP_ADD))
                    renderer.setZoom(renderer.zoom() * 1.02);
                if (renderer.keyDown(GLFW_KEY_MINUS) || renderer.keyDown(GLFW_KEY_KP_SUBTRACT))
                    renderer.setZoom(renderer.zoom() / 1.02);

                // ── Auto-fit map into view once ──────────────────
                if (!autoFitted && map != null) {
                    double diag = Math.max(map.width(), map.height());
                    double fit = Math.min(w / (diag * 32.0 + 32), h / (diag * 16.0 + 32));
                    renderer.setZoom(Math.max(0.25, Math.min(fit, 4.0)));
                    autoFitted = true;
                }

                // ── Camera ───────────────────────────────────────
                Snapshot snap = latestSnapshot.get();
                Snapshot.EntityState camTarget = null;
                long myId = localPlayerId.get();
                for (Snapshot.EntityState e : snap.entities()) {
                    if (camTarget == null) camTarget = e;
                    if (myId > 0 && e.id() == myId) { camTarget = e; break; }
                }
                if (camTarget != null) renderer.setCamera(camTarget.x(), camTarget.y());
                else if (map != null) renderer.setCamera(map.width() / 2.0, map.height() / 2.0);

                // ── Render ───────────────────────────────────────
                renderer.begin(w, h);
                renderer.applyCamera();

                if (map != null && !map.layers().isEmpty()) {
                    TileLayer ground = map.layers().get(0);
                    for (int y = 0; y < ground.height(); y++)
                        for (int x = 0; x < ground.width(); x++)
                            renderer.tile(x, y, ground.tiles()[y * ground.width() + x]);
                } else {
                    for (int y = 0; y < 30; y++)
                        for (int x = 0; x < 30; x++)
                            renderer.tile(x, y, (x + y) % 3);
                }

                for (Snapshot.EntityState e : snap.entities())
                    renderer.sprite(e.x(), e.y(), e.elevation(), 0);

                for (var eid : runtime.world().entities().entities())
                    runtime.world().entities().get(eid, Transform.class)
                        .ifPresent(t -> renderer.sprite(t.position().x(), t.position().y(), t.position().elevation(), 0));

                renderer.end();
                runtime.tick();

                // ── Window title with status ─────────────────────
                String title = "openRPGator";
                if (localServer != null && localServer.isRunning()) title += "  [local-server]";
                if (myId > 0) title += "  #" + myId;
                renderer.setTitle(title);
            }
        }

        // ── Cleanup ──────────────────────────────────────────────
        session.disconnect();
        if (localServer != null && localServer.isRunning()) localServer.stop();

        Files.createDirectories(CONFIG_DIR);
        cfg.setProperty("name", playerName);
        if (connectTo != null) cfg.setProperty("connect", connectTo);
        try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) { cfg.store(out, "openRPGator client config"); }
    }
}