package rpg.engine.client;

import rpg.engine.render.desktop.LwjglRenderer;
import rpg.engine.runtime.GameRuntime;
import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.map.TileLayer;
import rpg.engine.network.*;
import rpg.engine.core.component.Transform;

import java.io.*;
import java.nio.file.*;
import java.util.*;
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
    private static final long TOAST_DURATION_MS = 4000;

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".openrpgator");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("client.properties");

    enum Screen { MENU, SETTINGS, GAME }
    private volatile Screen screen = Screen.MENU;

    private static final int FIELD_CAPACITY = 512;

    private final LwjglRenderer renderer;
    private final DesktopClientSession session;

    private GameRuntime runtime;
    private RMap map;
    private Path mapPath;
    private Path resourceDir;
    private DesktopLocalServer localServer;
    private int localPort = DEFAULT_PORT;
    private String connectHost = DEFAULT_HOST;
    private int connectPort = DEFAULT_PORT;
    private String playerName = DEFAULT_NAME;
    private Double zoomArg = null;

    private SettingsField mapField, resourceField, nameField;
    private int focusedField; // index into settingsFields when on SETTINGS

    private final AtomicReference<Snapshot> latestSnapshot = new AtomicReference<>(new Snapshot(List.of()));
    private final AtomicLong localPlayerId = new AtomicLong(-1);
    private volatile String statusText = "Offline";
    private volatile boolean connected;

    private final List<ToastRecord> toasts = new ArrayList<>();
    private ActiveDialog activeDialog;

    private record ToastRecord(String text, long timestamp) {}
    private record ActiveDialog(long dialogId, String text, List<String> choices) {}

    public static void main(String[] args) throws Exception {
        new DesktopClientMain(args);
    }

    private DesktopClientMain(String[] args) throws Exception {
        // ── Parse arguments ──────────────────────────────────────
        parseArgs(args);
        loadConfig();

        // ── Renderer ─────────────────────────────────────────────
        renderer = new LwjglRenderer(1280, 720, "openRPGator");
        session = new DesktopClientSession(this);

        if (zoomArg != null) renderer.setZoom(zoomArg);

        // ── Main loop ────────────────────────────────────────────
        while (!renderer.shouldClose()) {
            renderer.poll();
            int w = renderer.framebufferWidth(), h = renderer.framebufferHeight();

            switch (screen) {
                case MENU -> renderMenu(w, h);
                case SETTINGS -> renderSettings(w, h);
                case GAME -> renderGame(w, h);
            }

            renderer.end();
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
                default -> {
                    if (args[i].endsWith(".rmap") && Files.exists(Path.of(args[i])))
                        mapPath = Path.of(args[i]);
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
        String savedMap = cfg.getProperty("map");
        if (savedMap != null && !savedMap.isBlank() && mapPath == null && Files.exists(Path.of(savedMap)))
            mapPath = Path.of(savedMap);
        String savedRes = cfg.getProperty("resources");
        if (savedRes != null && !savedRes.isBlank()) resourceDir = Path.of(savedRes);
        String savedConnect = cfg.getProperty("connect");
        if (savedConnect != null && connectHost.equals(DEFAULT_HOST)) connectTo(savedConnect);
    }

    private void saveConfig() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Properties cfg = new Properties();
            cfg.setProperty("name", playerName);
            cfg.setProperty("connect", connectHost + ":" + connectPort);
            if (mapPath != null) cfg.setProperty("map", mapPath.toString());
            if (resourceDir != null) cfg.setProperty("resources", resourceDir.toString());
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
        if (button(cx - bw/2, startY + gap * 3, bw, bh, "Quit"))
            renderer.close();

        renderer.text(8, h - 16, statusText, 1, 0.4f, 0.7f, 0.4f);
    }

    private void openSettings() {
        if (mapField == null) {
            mapField = new SettingsField("Map file (.rmap)", mapPath == null ? "" : mapPath.toString());
            resourceField = new SettingsField("Resources directory", resourceDir == null ? "" : resourceDir.toString());
            nameField = new SettingsField("Player name", playerName);
        }
        focusedField = 0;
        screen = Screen.SETTINGS;
    }

    private void startLocalServer() {
        try {
            if (localServer == null) localServer = new DesktopLocalServer();
            localServer.setResourceDir(resourceDir);
            localServer.start(localPort);
            if (mapPath != null && localServer.runtime() != null) {
                try { localServer.runtime().loadMap(mapPath); } catch (Exception e) { addToast("Map load error: " + e.getMessage()); }
            }
            statusText = "Local server on " + localPort + " — connecting...";
            session.connect("127.0.0.1", localPort, playerName);
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
        addToast("Local server stopped");
    }

    private void connectRemote() {
        statusText = "Connecting to " + connectHost + ":" + connectPort + "...";
        session.connect(connectHost, connectPort, playerName);
    }

    // ══════════════════════════════════════════════════════════════
    //  SETTINGS SCREEN
    // ══════════════════════════════════════════════════════════════
    private void renderSettings(int w, int h) {
        renderer.begin(w, h);

        double cx = w / 2.0;
        double top = 80;
        renderer.text(cx - renderer.textWidth("Settings", 3) / 2, top, "Settings", 3, 0.9f, 0.8f, 0.3f);
        renderer.text(cx - renderer.textWidth("Local server: map file and resources", 1) / 2,
                top + 40, "Local server: map file and resources", 1, 0.6f, 0.6f, 0.65f);

        SettingsField[] fields = { nameField, mapField, resourceField };
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
            if (focused) display += "\u2588"; // block cursor
            renderer.text(fX + 10, y + (fh - 8) / 2, display, 1, 0.92f, 0.92f, 0.95f);
            if (renderer.mouseClicked(0)
                    && renderer.mouseX() >= fX && renderer.mouseX() <= fX + fw
                    && renderer.mouseY() >= y && renderer.mouseY() <= y + fh) {
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
        SettingsField[] fields = { nameField, mapField, resourceField };
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
        String mapStr = mapField.value().trim();
        if (!mapStr.isEmpty()) {
            mapPath = Path.of(mapStr);
            map = null; // force reload from new path
        }
        String resStr = resourceField.value().trim();
        resourceDir = resStr.isEmpty() ? null : Path.of(resStr);
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
    //  GAME SCREEN
    // ══════════════════════════════════════════════════════════════
    private void renderGame(int w, int h) {
        // ── Keyboard → movement ──────────────────────────────────
        // WASD/arrows are screen-relative; convert to world axes for the
        // isometric projection (screen right = world (+1,-1), screen down = world (+1,+1)).
        double sx = 0, sy = 0;
        int actions = 0;
        if (renderer.keyDown(LwjglRenderer.KEY_D) || renderer.keyDown(LwjglRenderer.KEY_RIGHT)) sx += 1;
        if (renderer.keyDown(LwjglRenderer.KEY_A) || renderer.keyDown(LwjglRenderer.KEY_LEFT)) sx -= 1;
        if (renderer.keyDown(LwjglRenderer.KEY_S) || renderer.keyDown(LwjglRenderer.KEY_DOWN)) sy += 1;
        if (renderer.keyDown(LwjglRenderer.KEY_W) || renderer.keyDown(LwjglRenderer.KEY_UP)) sy -= 1;
        double dx = sx + sy, dy = sy - sx;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len > 1.0) { dx /= len; dy /= len; }
        if (renderer.keyDown(LwjglRenderer.KEY_J)) actions |= Input.PRIMARY;
        if (renderer.keyDown(LwjglRenderer.KEY_K)) actions |= Input.SECONDARY;
        if (renderer.keyDown(LwjglRenderer.KEY_L)) actions |= Input.INTERACT;
        if (renderer.keyDown(LwjglRenderer.KEY_I)) actions |= Input.INVENTORY;
        if (dx != 0 || dy != 0 || actions != 0) session.input(dx, dy, actions);

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
        if (camTarget != null) renderer.setCamera(camTarget.x(), camTarget.y());
        else if (map != null) renderer.setCamera(map.width() / 2.0, map.height() / 2.0);

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

        // Entities from snapshots
        for (Snapshot.EntityState e : snap.entities()) {
            renderer.sprite(e.x(), e.y(), e.elevation(), 0);
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

        // ── Toast notifications (bottom-right, stacked) ──────────
        renderToasts(w, h);

        // ── Modal dialog overlay ─────────────────────────────────
        if (activeDialog != null) renderDialog(w, h);
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
        screen = Screen.GAME;
        // Load map for tile rendering if not loaded yet
        if (map == null && mapPath != null) {
            try {
                map = RMapIO.read(mapPath);
            } catch (Exception e) { addToast("Map load error: " + e.getMessage()); }
        }
    }

    @Override public void onSnapshot(Snapshot s) { latestSnapshot.set(s); }
    @Override public void onNotify(Notify n) { addToast(n.text()); }
    @Override public void onDialog(Dialog d) { activeDialog = new ActiveDialog(d.dialogId(), d.text(), d.choices()); }
    @Override public void onStatus(String s) { statusText = s; }

}