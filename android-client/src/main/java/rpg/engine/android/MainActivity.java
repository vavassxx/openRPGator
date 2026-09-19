package rpg.engine.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.io.File;
import java.nio.file.Path;
import rpg.engine.android.controls.*;
import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.map.TileLayer;
import rpg.engine.network.Input;
import rpg.engine.network.Snapshot;
import rpg.engine.network.Welcome;
import rpg.engine.network.UiLayout;

public final class MainActivity extends Activity implements ClientSession.Listener {
    private ControlLayout controls;
    private ControlOverlay overlay;
    private GameView game;
    private ClientSession session;
    private final EnumSet<ControlAction> held = EnumSet.noneOf(ControlAction.class);
    private int actionBits;
    private boolean editing;
    private TextView status;
    private LocalServerBackend localServer;
    private SharedPreferences prefs;
    private AppStorage appStorage;
    private AppLogger logger;
    private AlertDialog currentDialog;
    private static final int PICK_STORAGE = 9001;
    /** Connect/Disconnect toggle button on the game screen; label follows {@link #connected}. */
    private Button connectButton;
    private volatile boolean connected;

    /** Re-sends movement while a move button is held (≈ server tick rate / 2 at 20 Hz). */
    private static final long MOVE_INTERVAL_MS = 50;
    private final Handler gameHandler = new Handler(Looper.getMainLooper());
    private final Runnable movePump = new Runnable() {
        @Override public void run() {
            sendMovement();
            if (anyMoveHeld()) gameHandler.postDelayed(this, MOVE_INTERVAL_MS);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        controls = ControlLayout.load(this);
        prefs = getSharedPreferences("openrpgator.settings.v1", MODE_PRIVATE);
        appStorage = new AppStorage(this);
        session = new ClientSession(this, appStorage.pakCacheDir());
        logger = AppLogger.get(this);
        logger.info("Application started");
        localServer = new LocalServerBackend(s -> runOnUiThread(() -> status.setText(s)));
        showMainMenu();
    }

    // ── Immersive mode ──────────────────────────────────────────────
    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    // ── Main menu ───────────────────────────────────────────────────
    private void showMainMenu() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(18, 19, 22));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("openRPGator");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(32));
        root.addView(title);

        String[] labels = {"Play", "Map editor", "Settings", "Exit"};
        for (String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setTextSize(16);
            b.setOnClickListener(v -> onMenu(label));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
            lp.setMargins(0, dp(6), 0, dp(6));
            root.addView(b, lp);
        }
        setContentView(root);
    }

    private void onMenu(String label) {
        logger.info("Main menu: " + label);
        switch (label) {
            case "Play": showGame(); break;
            case "Map editor": startActivity(new Intent(this, MapEditorActivity.class)); break;
            case "Settings": showSettings(); break;
            case "Exit": finish(); break;
        }
    }

    // ── Game screen ─────────────────────────────────────────────────
    private void showGame() {
        applyImmersive();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(18, 19, 22));

        // ── Collapsible server panel (floating overlay; never shrinks the stage) ──
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setElevation(dp(8));
        panel.setBackgroundColor(Color.argb(235, 18, 19, 22));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(4), dp(2), dp(8), dp(2));
        Button toggle = new Button(this);
        toggle.setText("▲ Панель");
        toggle.setTextSize(11);
        toggle.setAllCaps(false);
        TextView panelTitle = new TextView(this);
        panelTitle.setText("openRPGator — server & controls");
        panelTitle.setTextColor(Color.rgb(205, 205, 210));
        panelTitle.setTextSize(12);
        headerRow.addView(toggle, new LinearLayout.LayoutParams(dp(116), dp(34)));
        headerRow.addView(panelTitle, new LinearLayout.LayoutParams(0, dp(34), 1));
        panel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        // Collapsible body (hidden by default → fullscreen game on start)
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);

        // ── Top bar: connection ──
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));

        EditText host = field("Server address", prefs.getString("last_host", "127.0.0.1"));
        EditText port = field("Port", prefs.getString("last_port", "27991"));
        EditText name = field("Name", prefs.getString("last_name", "player"));

        bar.addView(host, new LinearLayout.LayoutParams(0, dp(48), 3));
        bar.addView(port, new LinearLayout.LayoutParams(0, dp(48), 2));
        bar.addView(name, new LinearLayout.LayoutParams(0, dp(48), 2));
        body.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        // ── Local server row (auto host folder) ──
        LinearLayout localRow = new LinearLayout(this);
        localRow.setOrientation(LinearLayout.HORIZONTAL);
        localRow.setGravity(Gravity.CENTER_VERTICAL);
        localRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView hostInfo = new TextView(this);
        hostInfo.setTextColor(Color.rgb(160, 160, 160));
        hostInfo.setTextSize(11);
        String[] maps = appStorage.mapNames();
        hostInfo.setText("Host (data/host): " + (maps.length == 0 ? "no map yet" : String.join(", ", maps)));
        hostInfo.setPadding(dp(8), 0, 0, 0);
        localRow.addView(hostInfo, new LinearLayout.LayoutParams(-1, dp(48)));
        body.addView(localRow, new LinearLayout.LayoutParams(-1, dp(56)));

        // ── Action buttons row ──
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(8), dp(4), dp(8), dp(4));

        Button connect = actionButton("Connect", "Connect / disconnect");
        connectButton = connect;
        Button local = actionButton("Local server", "Start/stop local server");
        Button editControls = actionButton("Edit controls", "Customize touch controls");
        Button menu = actionButton("Menu", "Back to main menu");

        actions.addView(connect, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(local, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(editControls, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(menu, new LinearLayout.LayoutParams(0, dp(52), 1));
        body.addView(actions, new LinearLayout.LayoutParams(-1, dp(60)));

        // ── Status ──
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setText("Offline");
        status.setTextSize(13);
        status.setPadding(dp(12), dp(4), dp(12), dp(4));
        body.addView(status, new LinearLayout.LayoutParams(-1, dp(36)));

        // ── Game stage ──
        FrameLayout stage = new FrameLayout(this);
        game = new GameView(this);
        loadHostMap();
        stage.addView(game, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ControlOverlay(this, controls, (a, pressed) -> onAction(a, pressed));
        overlay.setZoomListener(f -> game.setZoom(f));
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        // ── Controls edit bar (hidden by default) ──
        LinearLayout editbar = new LinearLayout(this);
        editbar.setOrientation(LinearLayout.HORIZONTAL);
        editbar.setGravity(Gravity.CENTER);
        editbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        editbar.setVisibility(View.GONE);

        Button done = actionButton("Done", "Finish editing");
        Button save = actionButton("Save", "Save layout");
        Button action = actionButton("Action", "Change button action");
        Button type = actionButton("Type", "Toggle button/joystick");
        Button smaller = actionButton("−", "Smaller");
        Button larger = actionButton("+", "Larger");
        Button add = actionButton("Add", "Add button");
        Button remove = actionButton("Delete", "Remove selected");

        editbar.addView(done, new LinearLayout.LayoutParams(0, dp(48), 1));
        editbar.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1));
        editbar.addView(action, new LinearLayout.LayoutParams(0, dp(48), 1));
        editbar.addView(type, new LinearLayout.LayoutParams(0, dp(48), 1));
        editbar.addView(smaller, new LinearLayout.LayoutParams(0, dp(48), 1));
        editbar.addView(larger, new LinearLayout.LayoutParams(0, dp(48), 1));
        editbar.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
        editbar.addView(remove, new LinearLayout.LayoutParams(0, dp(48), 1));
        body.addView(editbar, new LinearLayout.LayoutParams(-1, dp(56)));

        // ── Listeners ──
        connect.setOnClickListener(v -> {
            if (connected) {
                session.disconnect();
                gameHandler.removeCallbacks(movePump);
                connected = false;
                updateConnectUi();
                status.setText("Disconnected");
                return;
            }
            String h = host.getText().toString().trim();
            String p = port.getText().toString().trim();
            String n = name.getText().toString().trim();
            prefs.edit().putString("last_host", h).putString("last_port", p).putString("last_name", n).apply();
            try {
                session.connect(h, Integer.parseInt(p), n);
                status.setText("Connecting to " + h + ":" + p + "...");
            } catch (Exception e) { logger.error("Connection failed", e); status.setText("Invalid address or port"); }
        });
        local.setOnClickListener(v -> {
            try {
                if (!localServer.isRunning()) {
                    int p = Integer.parseInt(port.getText().toString().trim());
                    int tickRate = prefs.getInt("server_tick_rate", 20);
                    localServer.setTickRate(tickRate);
                    localServer.start(p, appStorage.dataDir().toPath());
                    host.setText("127.0.0.1");
                    status.setText("Local server on port " + p + " @ " + tickRate + " Hz (data/host). Press Connect to join.");
                    local.setText("Stop server");
                } else {
                    localServer.stop();
                    local.setText("Local server");
                    status.setText("Local server stopped");
                }
            } catch (Exception e) { status.setText("Local server failed: " + e.getMessage()); }
        });
        menu.setOnClickListener(v -> {
            connected = false;
            session.disconnect();
            if (localServer.isRunning()) localServer.stop();
            showMainMenu();
        });
        editControls.setOnClickListener(v -> {
            editing = true;
            overlay.setEditMode(true);
            editbar.setVisibility(View.VISIBLE);
            editControls.setVisibility(View.GONE);
            status.setText("Edit mode: tap to select, drag to move");
        });
        done.setOnClickListener(v -> {
            editing = false;
            overlay.setEditMode(false);
            editbar.setVisibility(View.GONE);
            editControls.setVisibility(View.VISIBLE);
            status.setText("Game mode");
        });
        save.setOnClickListener(v -> { controls.save(this); status.setText("Controls saved"); });
        action.setOnClickListener(v -> { if (overlay.selected() != null) { controls.cycleAction(overlay.selected()); overlay.invalidate(); status.setText("Action: " + overlay.selected().action.title); } });
        type.setOnClickListener(v -> { overlay.cycleType(); if (overlay.selected() != null) status.setText("Type: " + overlay.selected().type.name()); });
        smaller.setOnClickListener(v -> { if (overlay.selected() != null) { overlay.selected().size = Math.max(.05f, overlay.selected().size - .02f); overlay.invalidate(); } });
        larger.setOnClickListener(v -> { if (overlay.selected() != null) { overlay.selected().size = Math.min(.35f, overlay.selected().size + .02f); overlay.invalidate(); } });
        add.setOnClickListener(v -> {
            ControlBinding b = controls.addCustom(ControlAction.PRIMARY, ControlType.BUTTON, .5f, .5f, .12f, "A");
            overlay.select(b);
            status.setText("New button: drag to position");
        });
        remove.setOnClickListener(v -> { if (overlay.selected() != null) { controls.remove(overlay.selected()); overlay.invalidate(); status.setText("Button removed"); } });

        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        toggle.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "▼ Панель" : "▲ Панель");
            status.setText(show ? "Server panel open" : "Server panel collapsed — game screen is full");
        });

        setContentView(root);
    }

    private Button actionButton(String text, String description) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setOnLongClickListener(v -> {
            Toast.makeText(this, description, Toast.LENGTH_SHORT).show();
            return true;
        });
        return b;
    }

    private void shareLog() {
        try {
            File logFile = logger.privateLogFile();
            if (!logFile.isFile()) {
                logger.info("Log export requested before private log file existed");
            }
            if (!logFile.isFile()) {
                Toast.makeText(this, "Log file is not available yet", Toast.LENGTH_LONG).show();
                return;
            }

            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                logFile
            );

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Send openRPGator log"));
            logger.info("Log export requested: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            logger.error("Could not share private log", e);
            Toast.makeText(this, "Could not open Android share dialog: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── Settings screen ─────────────────────────────────────────────
    private void showSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 19, 22));
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        Button mainMenu = new Button(this);
        mainMenu.setText("Main menu");
        mainMenu.setOnClickListener(v -> showMainMenu());
        root.addView(mainMenu, new LinearLayout.LayoutParams(-1, dp(48)));

        Button shareLog = new Button(this);
        shareLog.setText("Share log");
        shareLog.setOnClickListener(v -> shareLog());
        root.addView(shareLog, new LinearLayout.LayoutParams(-1, dp(48)));

        // Storage info
        TextView storage = new TextView(this);
        storage.setTextColor(Color.rgb(180, 180, 180));
        storage.setTextSize(13);
        storage.setText(
            "App folder (data):\n" +
            appStorage.description() + "\n\n" +
            "• Maps / logs are kept inside the app folder\n" +
            "• Settings: Android app preferences\n" +
            "• The app folder is browsable from outside via the file picker (sidebar \u201copenRPGator\u201d)"
        );
        root.addView(storage, new LinearLayout.LayoutParams(-1, -2));

        // Storage section
        root.addView(sectionLabel("Storage"));
        TextView storagePath = new TextView(this);
        storagePath.setTextColor(Color.rgb(180,180,180));
        storagePath.setText(appStorage.description());
        root.addView(storagePath);

        Button openStorage = new Button(this);
        openStorage.setText("Open openRPGator storage (outside)");
        openStorage.setOnClickListener(v -> openStoragePicker(true));
        root.addView(openStorage, new LinearLayout.LayoutParams(-1, dp(48)));

        // Control layout section
        TextView controlsLabel = sectionLabel("Control layout");
        root.addView(controlsLabel);
        Button resetControls = new Button(this);
        resetControls.setText("Reset to defaults");
        resetControls.setOnClickListener(v -> {
            controls.reset();
            controls.save(this);
            Toast.makeText(this, "Controls reset", Toast.LENGTH_SHORT).show();
        });
        root.addView(resetControls, new LinearLayout.LayoutParams(-1, dp(48)));

        Button editControls = new Button(this);
        editControls.setText("Edit controls (opens game screen)");
        editControls.setOnClickListener(v -> { showGame(); });
        root.addView(editControls, new LinearLayout.LayoutParams(-1, dp(48)));

        // Connection defaults
        root.addView(sectionLabel("Default connection"));
        EditText defHost = new EditText(this);
        defHost.setHint("Default server address");
        defHost.setText(prefs.getString("last_host", "127.0.0.1"));
        defHost.setTextColor(Color.WHITE);
        root.addView(defHost, new LinearLayout.LayoutParams(-1, dp(48)));

        EditText defPort = new EditText(this);
        defPort.setHint("Default port");
        defPort.setText(prefs.getString("last_port", "27991"));
        defPort.setTextColor(Color.WHITE);
        root.addView(defPort, new LinearLayout.LayoutParams(-1, dp(48)));

        EditText defTick = new EditText(this);
        defTick.setHint("Local server tick rate (Hz), default 20");
        defTick.setText(String.valueOf(prefs.getInt("server_tick_rate", 20)));
        defTick.setTextColor(Color.WHITE);
        defTick.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(defTick, new LinearLayout.LayoutParams(-1, dp(48)));

        Button saveDefaults = new Button(this);
        saveDefaults.setText("Save defaults");
        saveDefaults.setOnClickListener(v -> {
            int tick = 20;
            try {
                tick = Integer.parseInt(defTick.getText().toString().trim());
                if (tick < 1 || tick > 240) tick = 20;
            } catch (NumberFormatException ignored) {}
            prefs.edit()
                .putString("last_host", defHost.getText().toString().trim())
                .putString("last_port", defPort.getText().toString().trim())
                .putInt("server_tick_rate", tick)
                .apply();
            Toast.makeText(this, "Defaults saved (tick " + tick + " Hz)", Toast.LENGTH_SHORT).show();
        });
        root.addView(saveDefaults, new LinearLayout.LayoutParams(-1, dp(48)));

        // About
        root.addView(sectionLabel("About"));
        TextView about = new TextView(this);
        about.setTextColor(Color.rgb(140, 140, 140));
        about.setTextSize(12);
        about.setText("openRPGator 0.3.2.3\nGPL-3.0\nServer-authoritative 2.5D RPG engine");
        root.addView(about);

        // Back button at bottom
        Button back = new Button(this);
        back.setText("Back to menu");
        back.setOnClickListener(v -> showMainMenu());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, dp(52));
        backLp.setMargins(0, dp(24), 0, 0);
        root.addView(back, backLp);

        Button exit = new Button(this);
        exit.setText("Exit application");
        exit.setOnClickListener(v -> {
            session.disconnect();
            if (localServer != null && localServer.isRunning()) localServer.stop();
            finishAffinity();
        });
        root.addView(exit, new LinearLayout.LayoutParams(-1, dp(52)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void openStoragePicker(boolean preferAppRoot) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (preferAppRoot && Build.VERSION.SDK_INT >= 26) {
            try {
                Uri rootUri = appStorage.providerTreeUri();
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri);
            } catch (Exception e) {
                logger.error("Could not build app storage URI", e);
            }
        }
        try {
            startActivityForResult(i, PICK_STORAGE);
        } catch (Exception e) {
            logger.error("Could not open Android storage picker", e);
            Toast.makeText(this, "Android file picker is unavailable", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_STORAGE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            logger.info("Application storage folder opened: " + uri);
            Toast.makeText(this, "Opened " + uri, Toast.LENGTH_SHORT).show();
            showSettings();
        }
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setPadding(0, dp(20), 0, dp(8));
        return t;
    }

    // ── Helpers ─────────────────────────────────────────────────────
    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value);
        e.setTextColor(Color.WHITE); e.setSingleLine();
        return e;
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    /** Loads the first *.rmap from the host folder so the floor matches the served map. */
    private void loadHostMap() {
        try {
            java.io.File[] maps = appStorage.hostDir().listFiles((d, n) -> n.endsWith(".rmap"));
            if (maps != null && maps.length > 0) {
                java.util.Arrays.sort(maps, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                try (java.io.InputStream in = new java.io.FileInputStream(maps[0])) {
                    game.setMap(RMapIO.read(in));
                }
            }
        } catch (Exception e) {
            logger.error("Host map load failed", e);
        }
    }

    /** The connection button doubles as disconnect while connected. */
    private void updateConnectUi() {
        if (connectButton != null) connectButton.setText(connected ? "Disconnect" : "Connect");
    }

    private void onAction(ControlAction a, boolean pressed) {
        if (pressed) held.add(a); else held.remove(a);
        if (a == ControlAction.INTERACT) {
            // Edge-triggered: one interaction per press — never repeated by the hold pump.
            if (pressed) session.input(0, 0, Input.INTERACT);
            return;
        }
        actionBits = 0;
        if (held.contains(ControlAction.PRIMARY)) actionBits |= Input.PRIMARY;
        if (held.contains(ControlAction.SECONDARY)) actionBits |= Input.SECONDARY;
        if (held.contains(ControlAction.INVENTORY)) actionBits |= Input.INVENTORY;
        sendMovement();
        gameHandler.removeCallbacks(movePump);
        if (anyMoveHeld()) gameHandler.postDelayed(movePump, MOVE_INTERVAL_MS);
    }
    private boolean anyMoveHeld() {
        return held.contains(ControlAction.MOVE_UP) || held.contains(ControlAction.MOVE_DOWN)
                || held.contains(ControlAction.MOVE_LEFT) || held.contains(ControlAction.MOVE_RIGHT);
    }
    /**
     * Screen-relative → world axes, same isometric conversion as the desktop client
     * (screen right = world (+1,-1), screen down = world (+1,+1)). Holding a button keeps
     * re-sending via {@link #movePump} until the last move button is released (a zero vector
     * is then sent to stop).
     */
    private void sendMovement() {
        double sx = (held.contains(ControlAction.MOVE_RIGHT) ? 1 : 0) - (held.contains(ControlAction.MOVE_LEFT) ? 1 : 0);
        double sy = (held.contains(ControlAction.MOVE_DOWN) ? 1 : 0) - (held.contains(ControlAction.MOVE_UP) ? 1 : 0);
        double dx = sx + sy, dy = sy - sx;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len > 1.0) { dx /= len; dy /= len; }
        session.input(dx, dy, actionBits);
    }

    @Override public void connected(Welcome w) {
        runOnUiThread(() -> { connected = true; updateConnectUi(); status.setText("Connected #" + w.entityId()); });
    }
    @Override public void snapshot(Snapshot s) { runOnUiThread(() -> game.setSnapshot(s)); }
    @Override public void status(String s) {
        runOnUiThread(() -> {
            if (s.startsWith("Disconnected")) { connected = false; updateConnectUi(); }
            status.setText(s);
        });
    }
    @Override public void pakLoaded(String name) {
        logAsset(name);
        runOnUiThread(() -> { if (game != null) game.reloadAssets(); });
    }
    private void logAsset(String name) { logger.info("Assets: " + name + " received, atlas reloaded"); }
    @Override public void ui(UiLayout u) {
        runOnUiThread(() -> {
            if (UiLayout.KIND_NOTIFY.equals(u.kind())) {
                // Rendered in the in-game toast area (like the desktop overlay), not a system toast.
                game.addToast(u.bodyText());
            } else if (UiLayout.KIND_DIALOG.equals(u.kind())) {
                logger.info("Dialog id=" + u.dialogId() + " choices=" + u.choiceTexts());
                showDialog(u);
            } else if (UiLayout.KIND_LAYOUT.equals(u.kind())) {
                // Host-driven widget surface — forwarded to the blind renderer in GameView.
                game.setLayout(u);
            }
        });
    }

    /**
     * Server dialog rendered with an explicit content view (message + one button per choice).
     * The plain {@code AlertDialog.Builder.setItems} path produced an empty dialog on device
     * (title+message but no list, no buttons), so choices are built as real buttons.
     */
    private void showDialog(UiLayout u) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Dialog");
        b.setCancelable(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView body = new TextView(this);
        body.setText(u.bodyText());
        body.setTextColor(Color.WHITE);
        body.setTextSize(15);
        content.addView(body, new LinearLayout.LayoutParams(-1, -2));

        List<String> choices = u.choiceTexts();
        if (choices.isEmpty()) {
            Button ok = dialogChoiceButton("OK");
            ok.setOnClickListener(v -> {
                session.dialogResponse(u.dialogId(), -1);
                dismissDialog();
            });
            content.addView(ok, new LinearLayout.LayoutParams(-1, dp(42)));
        } else {
            for (int i = 0; i < choices.size(); i++) {
                Button btn = dialogChoiceButton(choices.get(i));
                int which = i;
                btn.setOnClickListener(v -> {
                    session.dialogResponse(u.dialogId(), which);
                    dismissDialog();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
                lp.topMargin = dp(6);
                content.addView(btn, lp);
            }
        }
        b.setView(content);
        currentDialog = b.show();
    }

    private Button dialogChoiceButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        return btn;
    }

    private void dismissDialog() {
        if (currentDialog != null) {
            currentDialog.dismiss();
            currentDialog = null;
        }
    }

    @Override protected void onDestroy() { logger.info("Application stopping"); session.disconnect(); if (localServer != null) localServer.stop(); super.onDestroy(); }

    // ── Game view ───────────────────────────────────────────────────
    final class GameView extends View {
        private Snapshot snapshot = new Snapshot(List.of());
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path diamond = new android.graphics.Path();
        private final PakAtlas atlas = new PakAtlas();
        /** Host-driven widget overlay (UiLayout kind "layout"); rendered blindly. */
        private volatile List<Map<String, Object>> layoutWidgets = List.of();
        private volatile List<String> layoutStrings = List.of();
        /** Camera zoom (pinch gesture); 1 = map fits, <1 more landscape, >1 closer. */
        private float zoom = 1f;
        private static final float MIN_ZOOM = 0.5f, MAX_ZOOM = 4f;
        /** Notification list rendered as an in-game toast area (like the desktop overlay). */
        private static final long TOAST_DURATION_MS = 4000;
        private final List<ToastRecord> toasts = new ArrayList<>();
        private record ToastRecord(String text, long at) {}
        /** Real map ground layer (from the host folder's *.rmap); null → procedural floor. */
        private volatile int[] mapTiles;
        private volatile int mapW, mapH;
        private volatile boolean hasMap;
        GameView(Context c) {
            super(c);
            p.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            reloadAssets();
        }
        void setMap(RMap map) {
            if (map == null || map.layers().isEmpty()) {
                hasMap = false;
                invalidate();
                return;
            }
            TileLayer ground = map.layers().get(0);
            mapW = ground.width();
            mapH = ground.height();
            mapTiles = ground.tiles();
            hasMap = true;
            invalidate();
        }
        void setZoom(float factor) {
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
            invalidate();
        }
        void addToast(String text) {
            toasts.add(new ToastRecord(text, System.currentTimeMillis()));
            if (toasts.size() > 5) toasts.remove(0);
            invalidate();
        }
        void setLayout(UiLayout u) {
            layoutWidgets = u.layoutWidgets();
            layoutStrings = u.strings();
            invalidate();
        }
        void reloadAssets() {
            try {
                atlas.loadDir(appStorage.pakFiles());
            } catch (Exception e) {
                logger.error("Atlas reload failed", e);
            }
            invalidate();
        }
        void setSnapshot(Snapshot s) { snapshot = s; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.rgb(36, 48, 42));
            float tile = 48 * zoom, ox = getWidth() / 2f, oy = getHeight() / 3f;
            p.setStyle(Paint.Style.FILL);
            int tileCount = atlas.tileCount();
            if (hasMap) {
                // Real floor from the host map — tiles line up exactly with snapshot entities.
                for (int y = 0; y < mapH; y++) for (int x = 0; x < mapW; x++) {
                    float sx = ox + (x - y) * tile * .5f, sy = oy + (x + y) * tile * .25f;
                    int id = mapTiles[y * mapW + x];
                    drawTile(c, sx, sy, tile, (id >= 0 && id < tileCount) ? id : -1);
                }
            } else {
                for (int y = -8; y < 16; y++) for (int x = -12; x < 14; x++) {
                    float sx = ox + (x - y) * tile * .5f, sy = oy + (x + y) * tile * .25f;
                    drawTile(c, sx, sy, tile, floorTileId(x, y, tileCount));
                }
            }
            for (Snapshot.EntityState e : snapshot.entities()) {
                float sx = ox + (float)(e.x() - e.y()) * tile * .5f;
                float sy = oy + (float)(e.x() + e.y()) * tile * .25f - (float)e.elevation() * 12 * zoom;
                Bitmap bmp = atlas.spriteImage(e.sprite());
                if (bmp != null) {
                    float s = 40 * zoom * (float) e.scale();
                    float sc = Math.min(s / bmp.getWidth(), s / bmp.getHeight());
                    int w = (int) (bmp.getWidth() * sc), h = (int) (bmp.getHeight() * sc);
                    Rect dst = new Rect((int) (sx - w / 2f), (int) (sy - h / 2f), (int) (sx + w / 2f), (int) (sy + h / 2f));
                    c.drawBitmap(bmp, null, dst, p);
                } else {
                    p.setColor(Color.rgb(230, 180, 80)); c.drawCircle(sx, sy, 18 * zoom, p);
                }
                p.setColor(Color.BLACK); p.setTextSize(11); c.drawText(Long.toString(e.id()), sx - 7, sy + 4, p);
            }
            drawLayout(c);
            drawToasts(c);
        }
        /** Draws one isometric tile from the atlas texture, falling back to a flat color fill. */
        private void drawTile(Canvas c, float sx, float sy, float tile, int id) {
            float hw = tile * .5f, hh = tile * .25f;
            diamond.reset();
            diamond.moveTo(sx, sy);
            diamond.lineTo(sx + hw, sy + hh);
            diamond.lineTo(sx, sy + tile * .5f);
            diamond.lineTo(sx - hw, sy + hh);
            diamond.close();
            Bitmap bmp = id >= 0 ? atlas.tile(id) : null;
            if (bmp != null) {
                // Pak tiles are already diamond-shaped (transparent corners) — draw the bounding
                // box directly. clipPath() with a non-rect path is unsupported on the hardware
                // canvas and only produced garbage.
                Rect dst = new Rect((int) (sx - hw), (int) (sy - hh), (int) (sx + hw), (int) (sy + hh));
                c.drawBitmap(bmp, null, dst, p);
            } else {
                p.setColor(Color.rgb(58, 78, 65));
                c.drawPath(diamond, p);
            }
        }
        /** Coherent procedural floor: mostly grass with sparse dirt/light patches (stable per cell). */
        private int floorTileId(int x, int y, int tileCount) {
            if (tileCount <= 0) return -1;
            int h = Math.abs(x * 31 + y * 17) % 103;
            if (h == 0 && tileCount > 1) return 1;
            if (h == 7 && tileCount > 7) return 7;
            if (h == 19 && tileCount > 8) return 8;
            return 0;
        }

        /** Blind renderer for the host-driven widget schema (UiLayout kind "layout"). */
        private void drawLayout(Canvas c) {
            if (layoutWidgets.isEmpty()) return;
            float W = getWidth(), H = getHeight();
            float density = getResources().getDisplayMetrics().density;
            for (Map<String, Object> w : layoutWidgets) {
                String type = str(w, "type", "");
                float x = num(w, "x", 0) * W, y = num(w, "y", 0) * H;
                switch (type) {
                    case "panel" -> {
                        float[] bg = col(w, "bg");
                        if (bg != null) {
                            p.setStyle(Paint.Style.FILL);
                            p.setColor(argb(bg, bg.length > 3 ? bg[3] : 1f));
                            c.drawRoundRect(x, y, x + num(w, "w", 0) * W, y + num(w, "h", 0) * H,
                                    dp(4), dp(4), p);
                        }
                    }
                    case "bar" -> {
                        float ww = num(w, "w", 0.2f) * W, hh = num(w, "h", 0.04f) * H;
                        float frac = num(w, "max", 1) > 0
                                ? Math.max(0, Math.min(1, num(w, "value", 0) / num(w, "max", 1))) : 0;
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0xCC101318);
                        c.drawRoundRect(x - 2, y - 2, x + ww + 2, y + hh + 2, dp(3), dp(3), p);
                        float[] back = col(w, "back");
                        if (back != null) { p.setColor(argb(back, 1f)); c.drawRoundRect(x, y, x + ww, y + hh, dp(3), dp(3), p); }
                        float[] fill = col(w, "fill");
                        if (fill != null) {
                            if (fill.length > 3 && fill[3] < 1) p.setColor(argb(fill, fill[3]));
                            else p.setColor(argb(fill, 1f));
                            if (frac > 0) c.drawRoundRect(x, y, x + ww * frac, y + hh, dp(3), dp(3), p);
                        }
                    }
                    case "text" -> {
                        int ref = (int) num(w, "ref", -1);
                        String s = ref >= 0 && ref < layoutStrings.size() ? layoutStrings.get(ref) : "";
                        if (s.isEmpty()) break;
                        float[] color = col(w, "color");
                        p.setColor(color != null ? argb(color, 1f) : Color.WHITE);
                        p.setTextSize(num(w, "size", 12) * density);
                        c.drawText(s, x, y + p.getTextSize(), p);
                    }
                    default -> { }
                }
            }
        }

        /** Notification area, bottom-right, like the desktop toast overlay: stack, fade after 4 s. */
        private void drawToasts(Canvas c) {
            if (toasts.isEmpty()) return;
            long now = System.currentTimeMillis();
            toasts.removeIf(t -> now - t.at > TOAST_DURATION_MS);
            if (toasts.isEmpty()) return;
            float density = getResources().getDisplayMetrics().density;
            float y = getHeight() - dp(8);
            for (int i = toasts.size() - 1; i >= 0; i--) {
                ToastRecord t = toasts.get(i);
                long age = now - t.at;
                float alpha = Math.min(1f, (TOAST_DURATION_MS - age) / 1000f);
                p.setTextSize(13 * density);
                float tw = p.measureText(t.text);
                float th = p.descent() - p.ascent();
                float w = tw + dp(16);
                float left = getWidth() - w - dp(8), top = y - th - dp(4) + p.ascent();
                p.setStyle(Paint.Style.FILL);
                p.setColor(argb(0.10f, 0.12f, 0.18f, alpha * 0.92f));
                c.drawRoundRect(left, top, left + w, y, dp(4), dp(4), p);
                p.setColor(argb(0.90f, 0.90f, 0.55f, alpha));
                c.drawText(t.text, left + dp(8), y - dp(4), p);
                y -= th - p.ascent() + dp(6);
            }
            postInvalidateDelayed(150);
        }

        private static String str(Map<String, Object> m, String key, String dflt) {
            Object v = m.get(key);
            return v instanceof String s ? s : dflt;
        }
        private static float num(Map<String, Object> m, String key, float dflt) {
            Object v = m.get(key);
            return v instanceof Number n ? n.floatValue() : dflt;
        }
        /** Floats 0..1 (r,g,b[,a]) → ARGB int. */
        private static float[] col(Map<String, Object> m, String key) {
            Object v = m.get(key);
            if (!(v instanceof List)) return null;
            float[] out = new float[((List<?>) v).size()];
            int i = 0;
            for (Object o : (List<?>) v) if (o instanceof Number n) out[i++] = n.floatValue();
            return out;
        }
        private static int argb(float[] c, float alpha) {
            return Color.argb(Math.round(Math.max(0, Math.min(1, alpha)) * 255),
                    Math.round(Math.max(0, Math.min(1, c[0])) * 255),
                    Math.round(Math.max(0, Math.min(1, c[1])) * 255),
                    Math.round(Math.max(0, Math.min(1, c[2])) * 255));
        }
        private static int argb(float r, float g, float b, float a) {
            return Color.argb(Math.round(Math.max(0, Math.min(1, a)) * 255),
                    Math.round(Math.max(0, Math.min(1, r)) * 255),
                    Math.round(Math.max(0, Math.min(1, g)) * 255),
                    Math.round(Math.max(0, Math.min(1, b)) * 255));
        }
    }
}
