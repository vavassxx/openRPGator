package rpg.engine.client;

import rpg.engine.render.desktop.LwjglRenderer;
import rpg.engine.runtime.GameRuntime;
import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.map.TileLayer;
import rpg.engine.network.*;
import rpg.engine.core.component.Transform;
import rpg.engine.core.io.DataDir;
import rpg.engine.pak.PakAssets;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Desktop game client — connects to a server, renders isometric tiles from an .rmap file,
 * receives snapshots for entity state, and sends keyboard input.
 *
 * Features: main menu, in-game HUD, toast notifications, modal choice dialogs, mouse UI.
 *
 * Usage:
 *   openRPGator [options] [map.rmap]
 *     --local-server [port]       Start in-process local server (default port 27991)
 *     --connect host:port         Connect to server
 *     --name player               Player name (default: "player")
 *     --zoom factor               Initial tile zoom (default: auto-fit)
 */
public final class DesktopClientMain implements DesktopClientSession.Listener {

    private static final int DEFAULT_PORT = 27991;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final String DEFAULT_NAME = "player";
    private static final int DEFAULT_TICK_HZ = 20;
    private static final long TOAST_DURATION_MS = 4000;

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".openrpgator");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("client.properties");

    enum Screen { MENU, SETTINGS, CONTROLS, LOADING, GAME }
    private volatile Screen screen = Screen.MENU;

    /** Rebinds the same handful of GLFW keys — the ONLY hard-coded game actions. */
    enum Action {
        MOVE_UP("Move up", LwjglRenderer.KEY_W),
        MOVE_DOWN("Move down", LwjglRenderer.KEY_S),
        MOVE_LEFT("Move left", LwjglRenderer.KEY_A),
        MOVE_RIGHT("Move right", LwjglRenderer.KEY_D),
        PRIMARY("Primary", LwjglRenderer.KEY_J),
        SECONDARY("Secondary", LwjglRenderer.KEY_K),
        INTERACT("Interact", LwjglRenderer.KEY_L),
        INVENTORY("Inventory", LwjglRenderer.KEY_I),
        CAMERA_FOLLOW("Camera follow", LwjglRenderer.KEY_C);
        final String label;
        final int dflt;
        Action(String label, int dflt) { this.label = label; this.dflt = dflt; }
    }
    private final Map<Action, Integer> binds = new EnumMap<>(Action.class);
    { for (Action a : Action.values()) binds.put(a, a.dflt); } // defaults, overridden by config
    private int rebindRow = -1; // Action.values() index while waiting for a key press

    private void resetControls() {
        for (Action a : Action.values()) binds.put(a, a.dflt);
        rebindRow = -1;
        addToast("Controls reset to defaults");
        saveConfig();
    }
    private int bind(Action a) { return binds.getOrDefault(a, 0); }

    private static final int FIELD_CAPACITY = 512;

    private static final Path PAK_CACHE_DIR = CONFIG_DIR.resolve("paks");

    private final LwjglRenderer renderer;
    private final DesktopClientSession session;

    private final List<Path> loadedPaks = new ArrayList<>();
    private volatile long pakReceived, pakTotal;
    private volatile boolean pakDownloading;
    private volatile String pakStatus = "";
    private volatile PakAssets pakAssets = PakAssets.empty();
    /** Set by the network thread when new assets landed; consumed by the render thread for GL upload. */
    private final AtomicBoolean applyAssetsPending = new AtomicBoolean(false);

    private GameRuntime runtime;
    private RMap map;
    private Path mapPath;
    private DesktopLocalServer localServer;
    private int localPort = DEFAULT_PORT;
    private int localTickRate = DEFAULT_TICK_HZ;
    private String connectHost = DEFAULT_HOST;
    private int connectPort = DEFAULT_PORT;
    private String playerName = DEFAULT_NAME;
    private Double zoomArg = null;

    private SettingsField nameField;
    private SettingsField tickRateField;
    private int focusedField; // index into settingsFields when on SETTINGS

    private final AtomicReference<Snapshot> latestSnapshot = new AtomicReference<>(new Snapshot(List.of()));
    private final AtomicLong localPlayerId = new AtomicLong(-1);
    private volatile String statusText = "Offline";
    private volatile boolean connected;
    /** When enabled, the camera continuously follows the local player. */
    private boolean cameraFollow = true;

    private final List<ToastRecord> toasts = new CopyOnWriteArrayList<>();
    private volatile ActiveDialog activeDialog;
    /** Host-driven widget overlay (UiLayout kind "layout"); rendered blindly. */
    private volatile List<UiWidget> hudWidgets = List.of();
    private volatile List<String> hudStrings = List.of();

    private record ToastRecord(String text, long timestamp) {}
    private record ActiveDialog(long dialogId, String text, List<String> choices) {}

    public static void main(String[] args) throws Exception {
        new DesktopClientMain(args);
    }

    private DesktopClientMain(String[] args) throws Exception {
        // ── Parse arguments ──────────────────────────────────────
        parseArgs(args);
        loadConfig();

        // The server host folder (~/.openrpgator/data/host) is created up-front, so starting the
        // local server or dropping maps/packs in later always finds the folder (legacy data/maps,
        // data/paks are migrated in too). ServerConfig.resolve also creates it, as a safety net.
        try {
            DataDir.ensure();
        } catch (IOException e) {
            System.err.println("openRPGator: cannot create data folder " + DataDir.root() + ": " + e.getMessage());
        }

        // ── Renderer ─────────────────────────────────────────────
        renderer = new LwjglRenderer(1280, 720, "openRPGator");
        session = new DesktopClientSession(this);

        if (zoomArg != null) renderer.setZoom(zoomArg);

        // ── Main loop ────────────────────────────────────────────
        while (!renderer.shouldClose()) {
            renderer.poll();

            // Upload client assets on the render thread (the GL context lives here). onConnected
            // builds PakAssets on the network thread and only sets the flag.
            if (applyAssetsPending.getAndSet(false)) {
                renderer.setTileImages(pakAssets.tileImages());
                renderer.setSpriteImages(pakAssets.spriteImages(), pakAssets.spriteKeys());
            }

            int w = renderer.framebufferWidth(), h = renderer.framebufferHeight();

            switch (screen) {
                case MENU -> renderMenu(w, h);
                case SETTINGS -> renderSettings(w, h);
                case CONTROLS -> renderControls(w, h);
                case LOADING -> renderLoading(w, h);
                case GAME -> renderGame(w, h);
            }

            if (!renderer.isClosed()) renderer.end();
        }

        // ── Cleanup ──────────────────────────────────────────────
        if (localServer != null) localServer.stop();
        session.disconnect();
        renderer.close();
        saveConfig();
    }

    // ── Argument parsing ──────────────────────────────────────────
    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--local-server" -> {
                    localPort = DEFAULT_PORT;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        try { localPort = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
                }
                case "--connect" -> { if (i + 1 < args.length) { connectTo(args[++i]); } }
                case "--name" -> { if (i + 1 < args.length) playerName = args[++i]; }
                case "--zoom" -> {
                    if (i + 1 < args.length)
                        try { zoomArg = Double.parseDouble(args[++i]); } catch (NumberFormatException ignored) {}
                }
                case "--local-tick-rate" -> {
                    if (i + 1 < args.length)
                        try { localTickRate = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
                }
            }
        }
    }

    private void connectTo(String target) {
        String[] parts = target.contains(":") ? target.split(":", 2) : new String[]{target, String.valueOf(DEFAULT_PORT)};
        connectHost = parts[0].isEmpty() ? DEFAULT_HOST : parts[0];
        try { connectPort = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { connectPort = DEFAULT_PORT; }
    }

    private void loadConfig() {
        Properties cfg = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try {
                try (InputStream in = Files.newInputStream(CONFIG_FILE)) { cfg.load(in); }
            } catch (IOException ignored) {}
        }
        playerName = cfg.getProperty("name", playerName);
        String savedTick = cfg.getProperty("localTick");
        try { if (savedTick != null) localTickRate = Integer.parseInt(savedTick); } catch (NumberFormatException ignored) {}
        String savedConnect = cfg.getProperty("connect");
        if (savedConnect != null && connectHost.equals(DEFAULT_HOST)) connectTo(savedConnect);
        for (Action a : Action.values()) {
            String saved = cfg.getProperty("bind." + a.name());
            if (saved != null) {
                try { binds.put(a, Integer.parseInt(saved)); } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Properties cfg = new Properties();
            cfg.setProperty("name", playerName);
            cfg.setProperty("localTick", String.valueOf(localTickRate));
            cfg.setProperty("connect", connectHost + ":" + connectPort);
            for (Action a : Action.values()) cfg.setProperty("bind." + a.name(), String.valueOf(bind(a)));
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) { cfg.store(out, "openRPGator client config"); }
        } catch (IOException ignored) {}
    }

    // ══════════════════════════════════════════════════════════════
    //  MENU SCREEN
    // ══════════════════════════════════════════════════════════════
    private void renderMenu(int w, int h) {
        renderer.begin(w, h);

        double cx = w / 2.0, cy = h / 2.0;

        renderer.text(cx - renderer.textWidth("openRPGator", 3) / 2, cy - 120, "openRPGator", 3, 0.9f, 0.8f, 0.3f);
        renderer.text(cx - renderer.textWidth("Desktop Client", 1) / 2, cy - 80, "Desktop Client", 1, 0.6f, 0.6f, 0.65f);

        double bw = 260, bh = 36, gap = 48, startY = cy - 30;
        renderer.text(cx - renderer.textWidth(playerName, 1) / 2, startY + 68, "Name: " + playerName, 1, 0.5f, 0.5f, 0.55f);

        boolean serverRunning = localServer != null && localServer.isRunning();
        if (serverRunning) {
            if (button(cx - bw/2, startY, bw, bh, "Stop Local Server (port " + localPort + ")"))
                stopLocalServer();
        } else {
            if (button(cx - bw/2, startY, bw, bh, "Local Server (port " + localPort + ")"))
                startLocalServer();
        }
        if (button(cx - bw/2, startY + gap, bw, bh, "Connect to " + connectHost + ":" + connectPort))
            connectRemote();
        if (button(cx - bw/2, startY + gap * 2, bw, bh, "Settings"))
            openSettings();
        if (button(cx - bw/2, startY + gap * 3, bw, bh, "Controls"))
            { rebindRow = -1; screen = Screen.CONTROLS; }
        if (button(cx - bw/2, startY + gap * 4, bw, bh, "Quit"))
            renderer.close();

        renderer.text(8, h - 16, statusText, 1, 0.4f, 0.7f, 0.4f);
    }

    private void openSettings() {
        if (nameField == null) {
            nameField = new SettingsField("Player name", playerName);
            tickRateField = new SettingsField("Local server tick rate (Hz)", String.valueOf(localTickRate));
        }
        focusedField = 0;
        screen = Screen.SETTINGS;
    }

    private void startLocalServer() {
        try {
            if (localServer == null) localServer = new DesktopLocalServer();
            localServer.setHostDir(DataDir.host());
            localServer.setTickRate(localTickRate);
            localServer.start(localPort);
            mapPath = localServer.autoMapPath();
            if (mapPath != null) addToast("Host map: " + mapPath.getFileName());
            statusText = "Local server on " + localPort + " @ " + localTickRate + " Hz - connecting...";
            beginConnect("127.0.0.1", localPort, playerName);
        } catch (Exception e) {
            statusText = "Failed: " + e.getMessage();
            addToast("Local server error: " + e.getMessage());
        }
    }

    private void stopLocalServer() {
        if (localServer != null) localServer.stop();
        connected = false;
        session.disconnect();
        localPlayerId.set(-1);
        statusText = "Local server stopped";
        screen = Screen.MENU;
        addToast("Local server stopped");
    }

    private void connectRemote() {
        statusText = "Connecting to " + connectHost + ":" + connectPort + "...";
        beginConnect(connectHost, connectPort, playerName);
    }

    private void beginConnect(String host, int port, String name) {
        synchronized (loadedPaks) { loadedPaks.clear(); }
        pakReceived = 0;
        pakTotal = 0;
        pakDownloading = false;
        pakStatus = "Connecting...";
        connected = false;
        screen = Screen.LOADING;
        session.connect(host, port, name);
    }

    // ══════════════════════════════════════════════════════════════
    //  SETTINGS SCREEN
    // ══════════════════════════════════════════════════════════════
    private void renderSettings(int w, int h) {
        renderer.begin(w, h);

        double cx = w / 2.0;
        double top = 80;
        renderer.text(cx - renderer.textWidth("Settings", 3) / 2, top, "Settings", 3, 0.9f, 0.8f, 0.3f);
        renderer.text(cx - renderer.textWidth("Local server uses the data/host folder automatically", 1) / 2,
                top + 40, "Local server uses the data/host folder automatically", 1, 0.6f, 0.6f, 0.65f);

        SettingsField[] fields = { nameField, tickRateField };
        double fw = 560, fh = 42, fX = cx - fw / 2, fY = top + 76;
        double gap = 62;

        for (int i = 0; i < fields.length; i++) {
            SettingsField f = fields[i];
            double y = fY + i * gap;
            boolean focused = i == focusedField;
            renderer.text(fX, y - 12, f.label(), 1, 0.6f, 0.6f, 0.65f);
            if (focused)
                renderer.rect(fX, y, fw, fh, 0.2f, 0.35f, 0.25f, 0.95f);
            else
                renderer.rect(fX, y, fw, fh, 0.1f, 0.12f, 0.16f, 0.95f);
            renderer.rect(fX, y, fw, 2, 0.4f, 0.6f, 0.4f, 1f);
            renderer.rect(fX, y + fh - 2, fw, 2, 0.4f, 0.6f, 0.4f, 1f);
            String display = f.value();
            if (focused) display += "_"; // block cursor
            renderer.text(fX + 10, y + (fh - 8) / 2, display, 1, 0.92f, 0.92f, 0.95f);
            if (renderer.mouseX() >= fX && renderer.mouseX() <= fX + fw
                    && renderer.mouseY() >= y && renderer.mouseY() <= y + fh
                    && renderer.mouseClicked(0)) {
                focusedField = i;
            }
        }

        updateSettingsFields();

        // ── Actions ───────────────────────────────────────────────
        double bw = 180, bh = 36, by = fY + fields.length * gap + 20;
        boolean saved = button(cx - bw / 2 - 10, by, bw, bh, "Apply");
        if (button(cx - bw / 2 + bw + 20, by, bw, bh, "Back")) { screen = Screen.MENU; return; }
        if (saved) applySettings();
        if (renderer.keyPressed(LwjglRenderer.KEY_ESCAPE)) { screen = Screen.MENU; return; }

        String help = "Tab = next field   |   Enter = apply   |   Esc = back";
        renderer.text(cx - renderer.textWidth(help, 1) / 2, by + bh + 20, help, 1, 0.4f, 0.4f, 0.45f);
        renderer.text(8, h - 16, statusText, 1, 0.4f, 0.7f, 0.4f);
    }

    private void updateSettingsFields() {
        SettingsField[] fields = { nameField, tickRateField };
        if (renderer.keyPressed(LwjglRenderer.KEY_TAB)) {
            focusedField = (focusedField + 1) % fields.length;
        }
        SettingsField f = fields[focusedField];
        if (renderer.keyPressed(LwjglRenderer.KEY_BACKSPACE) && !f.value().isEmpty()) {
            f.setValue(f.value().substring(0, f.value().length() - 1));
        }
        int c;
        while ((c = renderer.consumeChar()) != 0) {
            if (f.value().length() < FIELD_CAPACITY) f.setValue(f.value() + (char) c);
        }
        if (renderer.keyPressed(LwjglRenderer.KEY_ENTER)) { applySettings(); screen = Screen.MENU; }
    }

    private void applySettings() {
        playerName = nameField.value().isBlank() ? DEFAULT_NAME : nameField.value().trim();
        try {
            localTickRate = Integer.parseInt(tickRateField.value().trim());
            if (localTickRate < 1 || localTickRate > 240) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            localTickRate = DEFAULT_TICK_HZ;
        }
        statusText = "Settings saved";
        addToast("Settings applied");
        saveConfig();
    }

    private static final class SettingsField {
        private final String label;
        private String value;
        SettingsField(String label, String value) { this.label = label; this.value = value; }
        String label() { return label; }
        String value() { return value; }
        void setValue(String value) { this.value = value; }
    }

    // ══════════════════════════════════════════════════════════════
    //  CONTROLS SCREEN (view + rebind action keys)
    // ══════════════════════════════════════════════════════════════
    private void renderControls(int w, int h) {
        renderer.begin(w, h);

        double cx = w / 2.0, top = 80;
        renderer.text(cx - renderer.textWidth("Controls", 3) / 2, top, "Controls", 3, 0.9f, 0.8f, 0.3f);
        String hint = "Click a row, then press a key to rebind. Arrows always move too. Esc closes this screen.";
        renderer.text(cx - renderer.textWidth(hint, 1) / 2, top + 40, hint, 1, 0.6f, 0.6f, 0.65f);

        Action[] actions = Action.values();
        double fw = 560, fh = 34, fX = cx - fw / 2, fY = top + 76, gap = 47;
        for (int i = 0; i < actions.length; i++) {
            double y = fY + i * gap;
            boolean active = i == rebindRow;
            if (active)
                renderer.rect(fX, y, fw, fh, 0.2f, 0.35f, 0.25f, 0.95f);
            else
                renderer.rect(fX, y, fw, fh, 0.1f, 0.12f, 0.16f, 0.95f);
            renderer.rect(fX, y, fw, 2, 0.4f, 0.6f, 0.4f, 1f);
            renderer.text(fX + 12, y + (fh - 8) / 2, actions[i].label, 1, 0.9f, 0.9f, 0.95f);
            String keyTxt = active ? "press a key..." : renderer.keyName(bind(actions[i]));
            double tw = renderer.textWidth(keyTxt, 1);
            renderer.text(fX + fw - tw - 12, y + (fh - 8) / 2, keyTxt, 1,
                    active ? 0.95f : 0.6f, active ? 0.75f : 0.8f, active ? 0.4f : 0.55f);
            if (!active
                    && renderer.mouseX() >= fX && renderer.mouseX() <= fX + fw
                    && renderer.mouseY() >= y && renderer.mouseY() <= y + fh
                    && renderer.mouseClicked(0)) {
                rebindRow = i;
            }
        }

        // ── Rebind: consume any key pressed this frame ────────────
        if (rebindRow >= 0) {
            int k = renderer.consumeKey();
            if (k == LwjglRenderer.KEY_ESCAPE) {
                rebindRow = -1;                       // Esc cancels the rebind, stays on screen
            } else if (k != -1) {
                Action a = actions[rebindRow];
                boolean conflict = false;
                for (Action o : Action.values()) if (o != a && bind(o) == k) conflict = true;
                if (conflict) {
                    addToast("Key already assigned");
                } else {
                    binds.put(a, k);
                    addToast(a.label + " → " + renderer.keyName(k));
                    saveConfig();
                }
                rebindRow = -1;
            }
        }

        // ── Actions ───────────────────────────────────────────────
        double bw = 180, bh = 36, by = fY + actions.length * gap + 24;
        if (button(cx - bw * 1.1 - 6, by, bw, bh, "Reset to defaults")) resetControls();
        if (button(cx + 6, by, bw, bh, "Back")) { rebindRow = -1; screen = Screen.MENU; return; }
        if (renderer.keyPressed(LwjglRenderer.KEY_ESCAPE)) { rebindRow = -1; screen = Screen.MENU; return; }

        String hp = "Esc while rebinding = keep the old bind";
        renderer.text(cx - renderer.textWidth(hp, 1) / 2, by + bh + 20, hp, 1, 0.4f, 0.4f, 0.45f);
        renderer.text(8, h - 16, statusText, 1, 0.4f, 0.7f, 0.4f);
    }

    // ══════════════════════════════════════════════════════════════
    //  LOADING SCREEN (handshake + pak download progress)
    // ══════════════════════════════════════════════════════════════
    private void renderLoading(int w, int h) {
        renderer.begin(w, h);

        double cx = w / 2.0, cy = h / 2.0;
        renderer.text(cx - renderer.textWidth("openRPGator", 3) / 2, cy - 90, "openRPGator", 3, 0.9f, 0.8f, 0.3f);
        renderer.text(cx - renderer.textWidth(pakStatus, 1) / 2, cy - 46, pakStatus, 1, 0.7f, 0.75f, 0.8f);

        double bw = 420, bh = 26, bx = cx - bw / 2, by = cy - 16;
        renderer.rect(bx, by, bw, bh, 0.1f, 0.12f, 0.16f, 0.95f);
        renderer.rect(bx, by, bw, 2, 0.4f, 0.6f, 0.4f, 1f);
        renderer.rect(bx, by + bh - 2, bw, 2, 0.4f, 0.6f, 0.4f, 1f);

        if (pakDownloading && pakTotal > 0) {
            double frac = Math.min(1.0, (double) pakReceived / pakTotal);
            renderer.rect(bx + 3, by + 3, (bw - 6) * frac, bh - 6, 0.3f, 0.65f, 0.35f, 0.95f);
            String pct = (int) (frac * 100) + "% (" + (pakReceived / 1024) + "/" + (pakTotal / 1024) + " KiB)";
            renderer.text(cx - renderer.textWidth(pct, 1) / 2, by + (bh - 8) / 2, pct, 1, 0.92f, 0.95f, 0.9f);
        } else {
            renderer.text(cx - renderer.textWidth("establishing session...", 1) / 2,
                    by + (bh - 8) / 2, "establishing session...", 1, 0.6f, 0.65f, 0.7f);
        }

        renderer.text(cx - renderer.textWidth("ESC = cancel", 1) / 2, by + bh + 20, "ESC = cancel", 1, 0.4f, 0.4f, 0.45f);

        if (renderer.keyPressed(LwjglRenderer.KEY_ESCAPE)) {
            session.disconnect();
            connected = false;
            screen = Screen.MENU;
        }

        renderer.text(8, h - 16, statusText, 1, 0.4f, 0.7f, 0.4f);
    }

    // ══════════════════════════════════════════════════════════════
    //  GAME SCREEN
    // ══════════════════════════════════════════════════════════════
    private void renderGame(int w, int h) {
        // ── Keyboard → movement ──────────────────────────────────
        // WASD/arrows are screen-relative; convert to world axes for the
        // isometric projection (screen right = world (+1,-1), screen down = world (+1,+1)).
        double sx = 0, sy = 0;
        int actions = 0;
        if (renderer.keyDown(bind(Action.MOVE_RIGHT)) || renderer.keyDown(LwjglRenderer.KEY_RIGHT)) sx += 1;
        if (renderer.keyDown(bind(Action.MOVE_LEFT)) || renderer.keyDown(LwjglRenderer.KEY_LEFT)) sx -= 1;
        if (renderer.keyDown(bind(Action.MOVE_DOWN)) || renderer.keyDown(LwjglRenderer.KEY_DOWN)) sy += 1;
        if (renderer.keyDown(bind(Action.MOVE_UP)) || renderer.keyDown(LwjglRenderer.KEY_UP)) sy -= 1;
        double dx = sx + sy, dy = sy - sx;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len > 1.0) { dx /= len; dy /= len; }
        if (renderer.keyDown(bind(Action.PRIMARY))) actions |= Input.PRIMARY;
        if (renderer.keyDown(bind(Action.SECONDARY))) actions |= Input.SECONDARY;
        if (renderer.keyDown(bind(Action.INTERACT))) actions |= Input.INTERACT;
        if (renderer.keyDown(bind(Action.INVENTORY))) actions |= Input.INVENTORY;
        if (dx != 0 || dy != 0 || actions != 0) session.input(dx, dy, actions);

        // Client-only toggle: no server input is generated.
        if (renderer.keyPressed(bind(Action.CAMERA_FOLLOW))) {
            cameraFollow = !cameraFollow;
            addToast("Camera follow: " + (cameraFollow ? "ON" : "OFF"));
        }

        // ESC → back to menu
        if (renderer.keyDown(LwjglRenderer.KEY_ESCAPE)) {
            if (activeDialog != null) { activeDialog = null; }
            else { screen = Screen.MENU; session.disconnect(); connected = false; statusText = "Offline"; return; }
        }

        // ── Zoom: +/- keys ───────────────────────────────────────
        if (renderer.keyDown(LwjglRenderer.KEY_EQUAL) || renderer.keyDown(LwjglRenderer.KEY_KP_ADD))
            renderer.setZoom(renderer.zoom() * 1.02);
        if (renderer.keyDown(LwjglRenderer.KEY_MINUS) || renderer.keyDown(LwjglRenderer.KEY_KP_SUBTRACT))
            renderer.setZoom(renderer.zoom() / 1.02);

        // ── Camera ───────────────────────────────────────────────
        Snapshot snap = latestSnapshot.get();
        Snapshot.EntityState camTarget = null;
        long myId = localPlayerId.get();
        for (Snapshot.EntityState e : snap.entities()) {
            if (camTarget == null) camTarget = e;
            if (myId > 0 && e.id() == myId) { camTarget = e; break; }
        }
        if (cameraFollow && camTarget != null) renderer.setCamera(camTarget.x(), camTarget.y());
        else if (map != null && !cameraFollow) {
            // Follow disabled: keep the last camera position instead of snapping to the player.
        } else if (map != null) renderer.setCamera(map.width() / 2.0, map.height() / 2.0);

        // ── Auto-fit map into view once ──────────────────────────
        if (zoomArg == null && map != null && renderer.zoom() <= 1.0) {
            double diag = Math.max(map.width(), map.height());
            double fit = Math.min(w / (diag * 32.0 + 32), h / (diag * 16.0 + 32));
            renderer.setZoom(Math.max(0.25, Math.min(fit, 4.0)));
        }

        // ── Render ───────────────────────────────────────────────
        renderer.begin(w, h);
        renderer.applyCamera();

        // Tiles
        if (map != null && !map.layers().isEmpty()) {
            TileLayer ground = map.layers().get(0);
            for (int y = 0; y < ground.height(); y++)
                for (int x = 0; x < ground.width(); x++)
                    renderer.tile(x, y, ground.tiles()[y * ground.width() + x]);
        } else {
            for (int y = -4; y < 20; y++)
                for (int x = -4; x < 24; x++)
                    renderer.tile(x, y, (x + y) % 3);
        }

        // Entities from snapshots — sprites are addressed by name ("player", prefab, ...)
        for (Snapshot.EntityState e : snap.entities()) {
            renderer.sprite(e.x(), e.y(), e.elevation(), e.sprite(), e.scale());
        }

        // ── HUD (screen-space overlay) ───────────────────────────
        renderer.resetView();
        int hudY = 4;
        String title = "openRPGator";
        if (localPlayerId.get() > 0) title += "  #" + localPlayerId.get();
        if (connected) title += "  [connected]";
        renderer.rect(0, 0, w, 20, 0.0f, 0.0f, 0.0f, 0.75f);
        renderer.text(6, 4, title, 1, 0.85f, 0.85f, 0.9f);
        renderer.text(w - renderer.textWidth(statusText, 1) - 6, 4, statusText, 1, 0.5f, 0.8f, 0.5f);

        // ── Host-driven widget overlay (UiLayout kind "layout") ──
        renderWidgets(w, h);

        // ── Toast notifications (bottom-right, stacked) ──────────
        renderToasts(w, h);

        // ── Modal dialog overlay ─────────────────────────────────
        if (activeDialog != null) renderDialog(w, h);
    }

    // ── Host-driven widget overlay ─────────────────────────────────
    private void renderWidgets(int w, int h) {
        for (UiWidget wt : hudWidgets) {
            double px = wt.x() * w, py = wt.y() * h, pw = wt.w() * w, ph = wt.h() * h;
            switch (wt.type()) {
                case "panel" -> {
                    if (wt.bg() != null) renderer.rect(px, py, pw, ph,
                            wt.bg()[0], wt.bg()[1], wt.bg()[2], wt.bg().length > 3 ? wt.bg()[3] : 1f);
                }
                case "bar" -> {
                    renderer.rect(px - 2, py - 2, pw + 4, ph + 4, 0f, 0f, 0f, 0.55f);
                    if (wt.back() != null) renderer.rect(px, py, pw, ph,
                            wt.back()[0], wt.back()[1], wt.back()[2], 1f);
                    double frac = wt.max() > 0 ? Math.max(0, Math.min(1, wt.value() / wt.max())) : 0;
                    if (frac > 0 && wt.color() != null) renderer.rect(px, py, pw * frac, ph,
                            wt.color()[0], wt.color()[1], wt.color()[2], 1f);
                }
                case "text" -> {
                    String s = wt.ref() >= 0 && wt.ref() < hudStrings.size() ? hudStrings.get(wt.ref()) : "";
                    if (!s.isEmpty()) {
                        float[] col = wt.color() != null ? wt.color() : new float[]{1, 1, 1};
                        renderer.text(px, py, s, Math.max(1, wt.size()), col[0], col[1], col[2]);
                    }
                }
                default -> { }
            }
        }
    }

    /** Widget descriptor parsed from the host layout schema (see {@code UiLayout.layout}). */
    private record UiWidget(String type, double x, double y, double w, double h,
                            double value, double max, int ref, int size, float[] color, float[] back, float[] bg) {
        static UiWidget of(Map<String, Object> m) {
            float[] fill = col(m, "color");
            if (fill == null) fill = col(m, "fill"); // bars from the Lua layout use "fill"
            return new UiWidget(str(m, "type"), num(m, "x", 0), num(m, "y", 0), num(m, "w", 0), num(m, "h", 0),
                    num(m, "value", 0), num(m, "max", 1), (int) num(m, "ref", -1),
                    Math.max(1, (int) Math.ceil(num(m, "size", 12) / 8.0)), // 5×7 font ≈ 8px per scale
                    fill, col(m, "back"), col(m, "bg"));
        }
        private static double num(Map<String, Object> m, String k, double dflt) {
            Object v = m.get(k);
            return v instanceof Number n ? n.doubleValue() : dflt;
        }
        private static float[] col(Map<String, Object> m, String k) {
            Object v = m.get(k);
            if (!(v instanceof List)) return null;
            float[] out = new float[((List<?>) v).size()];
            int i = 0;
            for (Object o : (List<?>) v) if (o instanceof Number n) out[i++] = n.floatValue();
            if (out.length < 3) return null;
            return out.length == 3 ? new float[]{out[0], out[1], out[2], 1f} : out;
        }
        private static String str(Map<String, Object> m, String k) {
            Object v = m.get(k);
            return v instanceof String s ? s : "";
        }
    }

    // ── Toast rendering ───────────────────────────────────────────
    private void renderToasts(int w, int h) {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.timestamp > TOAST_DURATION_MS);
        int y = h - 8;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            ToastRecord t = toasts.get(i);
            float alpha = Math.min(1f, (TOAST_DURATION_MS - (now - t.timestamp)) / 1000f);
            double tw = renderer.textWidth(t.text(), 1) + 16;
            double th = 16;
            renderer.rect(w - tw - 8, y - th, tw, th, 0.1f, 0.12f, 0.18f, alpha * 0.9f);
            renderer.text(w - tw - 8 + 8, y - th + 4, t.text(), 1, 0.9f, 0.9f, 0.5f);
            y -= th + 4;
        }
    }

    // ── Dialog rendering ──────────────────────────────────────────
    private void renderDialog(int w, int h) {
        ActiveDialog d = activeDialog;
        if (d == null) return;

        double boxW = Math.max(300, Math.min(w - 80, 500));
        double lineH = 10;
        int textLines = d.text().split("\n").length;
        int choices = d.choices().size();
        double boxH = 40 + textLines * lineH + 20 + choices * 28 + 16;
        double boxX = (w - boxW) / 2.0, boxY = (h - boxH) / 2.0;

        // Dim overlay
        renderer.rect(0, 0, w, h, 0, 0, 0, 0.6f);
        // Box background
        renderer.rect(boxX, boxY, boxW, boxH, 0.08f, 0.1f, 0.14f, 0.95f);
        // Border
        renderer.rect(boxX, boxY, boxW, 2, 0.5f, 0.5f, 0.55f, 1f);
        renderer.rect(boxX, boxY + boxH - 2, boxW, 2, 0.5f, 0.5f, 0.55f, 1f);
        renderer.rect(boxX, boxY, 2, boxH, 0.5f, 0.5f, 0.55f, 1f);
        renderer.rect(boxX + boxW - 2, boxY, 2, boxH, 0.5f, 0.5f, 0.55f, 1f);

        // Text
        String[] lines = d.text().split("\n");
        double ty = boxY + 12;
        for (String line : lines) {
            renderer.text(boxX + 14, ty, line, 1, 0.9f, 0.9f, 0.95f);
            ty += lineH;
        }

        // Choice buttons
        ty += 8;
        for (int i = 0; i < choices; i++) {
            String label = d.choices().get(i);
            double btnX = boxX + 14, btnW = boxW - 28, btnH = 22, btnY = ty + i * 28;
            boolean hover = renderer.mouseX() >= btnX && renderer.mouseX() <= btnX + btnW
                    && renderer.mouseY() >= btnY && renderer.mouseY() <= btnY + btnH;
            if (hover)
                renderer.rect(btnX, btnY, btnW, btnH, 0.2f, 0.35f, 0.25f, 0.95f);
            else
                renderer.rect(btnX, btnY, btnW, btnH, 0.12f, 0.14f, 0.18f, 0.95f);
            renderer.rect(btnX, btnY, btnW, 1, 0.4f, 0.6f, 0.4f, 1f);
            renderer.rect(btnX, btnY + btnH - 1, btnW, 1, 0.4f, 0.6f, 0.4f, 1f);
            renderer.text(btnX + 10, btnY + 5, label, 1, 0.9f, 0.95f, 0.9f);
            if (hover && renderer.mouseClicked(0)) {
                session.dialogResponse(d.dialogId(), i);
                activeDialog = null;
            }
        }
    }

    // ── UI button helper ──────────────────────────────────────────
    private boolean button(double x, double y, double w, double h, String label) {
        boolean hover = renderer.mouseX() >= x && renderer.mouseX() <= x + w
                && renderer.mouseY() >= y && renderer.mouseY() <= y + h;
        if (hover)
            renderer.rect(x, y, w, h, 0.2f, 0.35f, 0.25f, 0.95f);
        else
            renderer.rect(x, y, w, h, 0.12f, 0.14f, 0.18f, 0.95f);
        renderer.rect(x, y, w, 2, 0.4f, 0.6f, 0.4f, 1f);
        renderer.rect(x, y + h - 2, w, 2, 0.4f, 0.6f, 0.4f, 1f);
        renderer.rect(x, y, 2, h, 0.4f, 0.6f, 0.4f, 1f);
        renderer.rect(x + w - 2, y, 2, h, 0.4f, 0.6f, 0.4f, 1f);
        double tw = renderer.textWidth(label, 1);
        renderer.text(x + (w - tw) / 2, y + (h - 8) / 2, label, 1, 0.9f, 0.95f, 0.9f);
        return hover && renderer.mouseClicked(0);
    }

    private void addToast(String text) {
        toasts.add(new ToastRecord(text, System.currentTimeMillis()));
    }

    // ══════════════════════════════════════════════════════════════
    //  LISTENER CALLBACKS (network thread)
    // ══════════════════════════════════════════════════════════════
    @Override public void onConnected(Welcome w) {
        localPlayerId.set(w.entityId());
        connected = true;
        statusText = "Connected #" + w.entityId();
        // Load map for tile rendering if not loaded yet
        if (map == null && mapPath != null) {
            try {
                map = RMapIO.read(mapPath);
            } catch (Exception e) { addToast("Map load error: " + e.getMessage()); }
        }
        // Load pak assets received during the handshake (sprites + tiles) into the renderer.
        // Cached paks are added by onPakCached, downloaded ones by onPakDone — both run on the
        // network thread, so read a consistent snapshot under the lock.
        List<Path> paks;
        synchronized (loadedPaks) { paks = new ArrayList<>(loadedPaks); }
        if (!paks.isEmpty()) {
            try {
                pakAssets = PakAssets.fromPaks(paks.toArray(Path[]::new));
                // The GL texture upload must run on the render thread (main loop) — never call
                // OpenGL from the network thread (no GL context here → JVM aborts). We just build
                // the atlas and flag it for the render thread to apply.
                applyAssetsPending.set(true);
                addToast("Assets: " + pakAssets.tileCount() + " tiles, "
                        + pakAssets.spriteCount() + " sprites");
            } catch (Exception e) {
                pakAssets = PakAssets.empty();
                addToast("Asset load error: " + e.getMessage());
            }
        }
        screen = Screen.GAME;
    }

    @Override public void onSnapshot(Snapshot s) { latestSnapshot.set(s); }
    @Override public void onUi(UiLayout u) {
        if (UiLayout.KIND_NOTIFY.equals(u.kind())) {
            addToast(u.bodyText());
        } else if (UiLayout.KIND_DIALOG.equals(u.kind())) {
            activeDialog = new ActiveDialog(u.dialogId(), u.bodyText(), u.choiceTexts());
        } else if (UiLayout.KIND_LAYOUT.equals(u.kind())) {
            // Host-driven widget surface — render the schema as-is, never infer game meaning.
            hudStrings = u.strings();
            hudWidgets = u.layoutWidgets().stream().map(UiWidget::of).toList();
        }
    }
    @Override public void onStatus(String s) {
        statusText = s;
        if (screen == Screen.LOADING) {
            screen = Screen.MENU;
            connected = false;
        }
    }

    @Override public void onPakStart(long totalBytes) {
        pakTotal = totalBytes;
        pakReceived = 0;
        pakDownloading = totalBytes > 0;
        if (totalBytes == 0) loadedPaks.clear();
        pakStatus = pakDownloading
                ? "Downloading assets (" + (totalBytes / 1024) + " KiB)..."
                : "Connecting, no assets to load...";
    }

    @Override public void onPakProgress(long received, long totalBytes) {
        pakReceived = received;
        pakStatus = "Downloading assets... " + (received * 100 / Math.max(1, totalBytes)) + "%";
    }

    @Override public boolean onPakCached(String name, long size) {
        Path p = cachedPakPath(name);
        if (p == null) return false;
        try {
            if (Files.exists(p) && Files.size(p) == size) {
                // Cached packs are skipped on the wire, so no onPakDone will arrive for them —
                // they must still enter the atlas for tiles/sprites to render.
                synchronized (loadedPaks) {
                    if (!loadedPaks.contains(p)) loadedPaks.add(p);
                }
                return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    @Override public void onPakDone(String name, byte[] data) {
        Path p = cachedPakPath(name);
        if (p == null) return;
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, data);
            synchronized (loadedPaks) { loadedPaks.add(p); }
            pakStatus = "Received " + name;
        } catch (IOException e) {
            addToast("Pak cache error: " + e.getMessage());
        }
    }

    /** Resolves a server-announced pak name to the local cache path; null on unsafe names. */
    private Path cachedPakPath(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains(".."))
            return null;
        return PAK_CACHE_DIR.resolve(name);
    }

}