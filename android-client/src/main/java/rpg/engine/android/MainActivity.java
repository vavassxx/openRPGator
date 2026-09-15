package rpg.engine.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import rpg.engine.android.controls.*;
import rpg.engine.network.*;

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
    private static final int PICK_STORAGE = 9001;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        controls = ControlLayout.load(this);
        session = new ClientSession(this);
        prefs = getSharedPreferences("openrpgator.settings.v1", MODE_PRIVATE);
        appStorage = new AppStorage(this);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 19, 22));

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
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        // ── Action buttons row ──
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(8), dp(4), dp(8), dp(4));

        Button connect = actionButton("Connect", "Connect to server");
        Button local = actionButton("Local server", "Start/stop local server");
        Button editControls = actionButton("Edit controls", "Customize touch controls");
        Button menu = actionButton("Menu", "Back to main menu");

        actions.addView(connect, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(local, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(editControls, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(menu, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(60)));

        // ── Status ──
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setText("Offline");
        status.setTextSize(13);
        status.setPadding(dp(12), dp(4), dp(12), dp(4));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(36)));

        // ── Game stage ──
        FrameLayout stage = new FrameLayout(this);
        game = new GameView(this);
        stage.addView(game, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ControlOverlay(this, controls, (a, pressed) -> onAction(a, pressed));
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

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
        root.addView(editbar, new LinearLayout.LayoutParams(-1, dp(56)));

        // ── Listeners ──
        connect.setOnClickListener(v -> {
            String h = host.getText().toString().trim();
            String p = port.getText().toString().trim();
            String n = name.getText().toString().trim();
            prefs.edit().putString("last_host", h).putString("last_port", p).putString("last_name", n).apply();
            try {
                session.connect(h, Integer.parseInt(p), n);
                status.setText("Connecting to " + h + ":" + p + "...");
            } catch (Exception e) { status.setText("Invalid address or port"); }
        });
        local.setOnClickListener(v -> {
            try {
                if (!localServer.isRunning()) {
                    int p = Integer.parseInt(port.getText().toString().trim());
                    localServer.start(p);
                    host.setText("127.0.0.1");
                    status.setText("Local server on port " + p + ". Press Connect to join.");
                    local.setText("Stop server");
                } else {
                    localServer.stop();
                    local.setText("Local server");
                    status.setText("Local server stopped");
                }
            } catch (Exception e) { status.setText("Local server failed: " + e.getMessage()); }
        });
        menu.setOnClickListener(v -> {
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

        // Storage info
        TextView storage = new TextView(this);
        storage.setTextColor(Color.rgb(180, 180, 180));
        storage.setTextSize(13);
        storage.setText(
            "Data storage:\n" +
            "• Control layouts: app private storage (SharedPreferences)\n" +
            "• Maps (.rmap): user-selected location via system file picker\n" +
            "• Connection history: app private storage\n" +
            "• No data is stored on external servers"
        );
        root.addView(storage, new LinearLayout.LayoutParams(-1, -2));

        // Storage section
        root.addView(sectionLabel("Application data folder"));
        TextView storagePath = new TextView(this);
        storagePath.setTextColor(Color.rgb(180,180,180));
        storagePath.setText(appStorage.description());
        root.addView(storagePath);
        Button chooseStorage = new Button(this);
        chooseStorage.setText("Choose data folder");
        chooseStorage.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i, PICK_STORAGE); });
        root.addView(chooseStorage, new LinearLayout.LayoutParams(-1, dp(48)));

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

        Button saveDefaults = new Button(this);
        saveDefaults.setText("Save defaults");
        saveDefaults.setOnClickListener(v -> {
            prefs.edit()
                .putString("last_host", defHost.getText().toString().trim())
                .putString("last_port", defPort.getText().toString().trim())
                .apply();
            Toast.makeText(this, "Defaults saved", Toast.LENGTH_SHORT).show();
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

        setContentView(root);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_STORAGE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); } catch (Exception ignored) {}
            appStorage.setRoot(uri);
            Toast.makeText(this, "Data folder selected", Toast.LENGTH_SHORT).show();
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

    private void onAction(ControlAction a, boolean pressed) {
        if (pressed) held.add(a); else held.remove(a);
        actionBits = 0;
        if (held.contains(ControlAction.PRIMARY)) actionBits |= Input.PRIMARY;
        if (held.contains(ControlAction.SECONDARY)) actionBits |= Input.SECONDARY;
        if (held.contains(ControlAction.INTERACT)) actionBits |= Input.INTERACT;
        if (held.contains(ControlAction.INVENTORY)) actionBits |= Input.INVENTORY;
        sendMovement();
    }
    private void sendMovement() {
        double dx = (held.contains(ControlAction.MOVE_RIGHT) ? 1 : 0) - (held.contains(ControlAction.MOVE_LEFT) ? 1 : 0);
        double dy = (held.contains(ControlAction.MOVE_DOWN) ? 1 : 0) - (held.contains(ControlAction.MOVE_UP) ? 1 : 0);
        session.input(dx, dy, actionBits);
    }

    @Override public void connected(Welcome w) { runOnUiThread(() -> status.setText("Connected #" + w.entityId())); }
    @Override public void snapshot(Snapshot s) { runOnUiThread(() -> game.setSnapshot(s)); }
    @Override public void status(String s) { runOnUiThread(() -> status.setText(s)); }
    @Override protected void onDestroy() { session.disconnect(); if (localServer != null) localServer.stop(); super.onDestroy(); }

    // ── Game view ───────────────────────────────────────────────────
    final class GameView extends View {
        private Snapshot snapshot = new Snapshot(List.of());
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        GameView(Context c) { super(c); p.setTypeface(Typeface.create("sans", Typeface.NORMAL)); }
        void setSnapshot(Snapshot s) { snapshot = s; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.rgb(36, 48, 42));
            float tile = 48, ox = getWidth() / 2f, oy = getHeight() / 3f;
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(58, 78, 65));
            for (int y = -8; y < 16; y++) for (int x = -12; x < 14; x++) {
                float sx = ox + (x - y) * tile * .5f, sy = oy + (x + y) * tile * .25f;
                c.drawRect(sx, sy, sx + tile * .5f, sy + tile * .25f, p);
            }
            p.setTextSize(28); p.setColor(Color.WHITE); c.drawText("openRPGator", 20, 34, p);
            for (Snapshot.EntityState e : snapshot.entities()) {
                float sx = ox + (float)(e.x() - e.y()) * tile * .5f;
                float sy = oy + (float)(e.x() + e.y()) * tile * .25f - (float)e.elevation() * 12;
                p.setColor(Color.rgb(230, 180, 80)); c.drawCircle(sx, sy, 18, p);
                p.setColor(Color.BLACK); p.setTextSize(11); c.drawText(Long.toString(e.id()), sx - 7, sy + 4, p);
            }
            if (snapshot.entities().isEmpty()) {
                p.setColor(Color.WHITE); p.setTextSize(18);
                c.drawText("Connect to a server", 20, 70, p);
            }
        }
    }
}
