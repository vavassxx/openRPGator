#!/usr/bin/env python3
# Apply the openRPGator 0.5.0 client/regions/docs patch.
# Run from repository root: python3 apply-0.5.0-client-regions-docs.py

from pathlib import Path

ROOT = Path.cwd()

def edit(path, replacements):
    file = ROOT / path
    if not file.is_file():
        raise SystemExit(f"Missing file: {path}")
    text = file.read_text(encoding="utf-8")
    original = text
    for old, new, label in replacements:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: expected exactly one match for {label}, found {count}; no changes written to this file")
        text = text.replace(old, new, 1)
    if text != original:
        file.write_text(text, encoding="utf-8")
        print(f"updated {path}")

edit("android-client/src/main/java/rpg/engine/android/MainActivity.java", [
(
'''    private Button connectButton;
    private volatile boolean connected;''',
'''    private Button connectButton;
    private Button localServerButton;
    private volatile boolean connected;''',
"connection button fields"
),
(
'''        addMenuCard(root, "Play", "Connect to a server or start a local world", true);
        addMenuCard(root, "Map editor", "Create and edit .rmap maps", false);''',
'''        addMenuCard(root, "Servers", "Join a server or host a local world", true);
        addMenuCard(root, "Map editor", "Create and edit .rmap maps", false);''',
"main menu Servers card"
),
(
'''    private void onMenu(String label) {
        logger.info("Main menu: " + label);
        switch (label) {
            case "Play": showGame(); break;
            case "Map editor": startActivity(new Intent(this, MapEditorActivity.class)); break;''',
'''    private void showServerMenu() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(15, 17, 22));
        root.setPadding(dp(22), dp(22), dp(22), dp(22));

        TextView title = new TextView(this);
        title.setText("Servers");
        title.setTextColor(Color.rgb(242, 244, 250));
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Connect to a saved address or start the world in data/host.");
        subtitle.setTextColor(Color.rgb(145, 153, 170));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(5), 0, dp(18));
        root.addView(subtitle);

        root.addView(sectionLabel("SERVER ADDRESS"));
        EditText host = field("Address", prefs.getString("last_host", "127.0.0.1"));
        EditText port = field("Port", prefs.getString("last_port", "27991"));
        EditText name = field("Character name", prefs.getString("last_name", "player"));
        root.addView(host, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(port, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(name, new LinearLayout.LayoutParams(-1, dp(52)));

        Button connect = actionButton("Connect", "Connect to this server");
        connect.setTextSize(14);
        root.addView(connect, new LinearLayout.LayoutParams(-1, dp(50)));
        connect.setOnClickListener(v -> {
            String h = host.getText().toString().trim();
            String p = port.getText().toString().trim();
            String n = name.getText().toString().trim();
            try {
                int portNumber = Integer.parseInt(p);
                if (h.isEmpty() || n.isEmpty() || portNumber < 1 || portNumber > 65535)
                    throw new IllegalArgumentException("Enter a host, name, and valid port (1–65535)");
                prefs.edit().putString("last_host", h).putString("last_port", p)
                    .putString("last_name", n).apply();
                showGame();
                if (connectButton != null) connectButton.performClick();
            } catch (Exception e) {
                new AlertDialog.Builder(this).setTitle("Cannot connect")
                    .setMessage(e.getMessage() == null ? "Invalid server address or port" : e.getMessage())
                    .setPositiveButton("OK", null).show();
            }
        });

        Button local = actionButton("Host local world", "Start the local server and join it");
        local.setTextSize(14);
        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(-1, dp(50));
        localLp.topMargin = dp(8);
        root.addView(local, localLp);
        local.setOnClickListener(v -> {
            String p = prefs.getString("last_port", "27991");
            String n = prefs.getString("last_name", "player");
            prefs.edit().putString("last_host", "127.0.0.1").putString("last_port", p)
                .putString("last_name", n).apply();
            showGame();
            if (localServerButton != null) localServerButton.performClick();
            if (connectButton != null) connectButton.performClick();
        });

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        Button back = actionButton("Back to menu", "Return to the main menu");
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(48)));
        back.setOnClickListener(v -> showMainMenu());
        setContentView(root);
    }

    private void onMenu(String label) {
        logger.info("Main menu: " + label);
        switch (label) {
            case "Servers": showServerMenu(); break;
            case "Map editor": startActivity(new Intent(this, MapEditorActivity.class)); break;''',
"server menu implementation"
),
(
'''        Button connect = actionButton("Connect", "Connect / disconnect");
        connectButton = connect;
        Button local = actionButton("Local server", "Start/stop local server");''',
'''        Button connect = actionButton("Connect", "Connect / disconnect");
        connectButton = connect;
        Button local = actionButton("Local server", "Start/stop local server");
        localServerButton = local;''',
"game screen button references"
),
(
'''            if (hasNetMap) {
                // Real floor streamed by the server — tiles line up exactly with snapshot entities.
                for (int y = 0; y < netH; y++) for (int x = 0; x < netW; x++) {
                    float sx = ox + (x - y) * tile * .5f, sy = oy + (x + y) * tile * .25f;
                    int id = netTiles[y * netW + x];
                    drawTile(c, sx, sy, tile, (id >= 0 && id < tileCount) ? id : -1);
                }
            }''',
'''            if (hasNetMap && netTiles != null && netW > 0 && netH > 0) {
                // Invert the isometric projection at expanded viewport corners. This bounds the
                // visible tile rectangle instead of scanning every tile on the UI thread per frame.
                float left = -tile, right = getWidth() + tile;
                float top = -tile * .5f, bottom = getHeight() + tile * .5f;
                float minWorldX = Float.POSITIVE_INFINITY, maxWorldX = Float.NEGATIVE_INFINITY;
                float minWorldY = Float.POSITIVE_INFINITY, maxWorldY = Float.NEGATIVE_INFINITY;
                float[] cornerX = { left, right, left, right };
                float[] cornerY = { top, top, bottom, bottom };
                for (int i = 0; i < 4; i++) {
                    float u = (cornerX[i] - ox) / (tile * .5f);
                    float v = (cornerY[i] - oy) / (tile * .25f);
                    float wx = (u + v) * .5f;
                    float wy = (v - u) * .5f;
                    minWorldX = Math.min(minWorldX, wx);
                    maxWorldX = Math.max(maxWorldX, wx);
                    minWorldY = Math.min(minWorldY, wy);
                    maxWorldY = Math.max(maxWorldY, wy);
                }
                int x0 = Math.max(0, (int)Math.floor(minWorldX) - 1);
                int x1 = Math.min(netW - 1, (int)Math.ceil(maxWorldX) + 1);
                int y0 = Math.max(0, (int)Math.floor(minWorldY) - 1);
                int y1 = Math.min(netH - 1, (int)Math.ceil(maxWorldY) + 1);
                int safeLength = (int)Math.min((long)netTiles.length, (long)netW * netH);
                for (int y = y0; y <= y1; y++) {
                    int row = y * netW;
                    for (int x = x0; x <= x1; x++) {
                        int index = row + x;
                        if (index < 0 || index >= safeLength) continue;
                        float sx = ox + (x - y) * tile * .5f;
                        float sy = oy + (x + y) * tile * .25f;
                        if (sx + tile * .5f < 0 || sx - tile * .5f > getWidth()
                                || sy + tile * .25f < 0 || sy - tile * .25f > getHeight()) continue;
                        int id = netTiles[index];
                        drawTile(c, sx, sy, tile, (id >= 0 && id < tileCount) ? id : -1);
                    }
                }
            }''',
"viewport tile culling"
)
])

edit("engine-map/src/main/java/rpg/engine/map/RegionCatalog.java", [
(
'''    private final Map<String, Entry> regions;
    private RegionCatalog(Map<String, Entry> regions) { this.regions = Map.copyOf(regions); }''',
'''    private final Map<String, Entry> regions;
    private RegionCatalog(Map<String, Entry> regions) {
        // Preserve filename-sorted load order; Map.copyOf does not promise iteration order.
        this.regions = Collections.unmodifiableMap(new LinkedHashMap<>(regions));
    }''',
"preserve catalog order"
)
])

edit("dedicated-server/src/main/java/rpg/engine/server/ServerHost.java", [
(
'''    private RegionRuntime initialRegion() {
        return regions.find("default").orElseGet(() -> regions.all().stream().findFirst().orElse(null));
    }''',
'''    private RegionRuntime initialRegion() {
        Optional<RegionRuntime> namedDefault = regions.find("default");
        if (namedDefault.isPresent()) return namedDefault.get();
        // Explicit fallback policy: lexicographically first region id.
        return regions.all().stream().min(Comparator.comparing(RegionRuntime::id)).orElse(null);
    }''',
"deterministic initial region"
)
])

edit("docs/architecture.md", [
(
'''- Android: `GameView` draws snapshots + the `UiLayout` schema; the floor is streamed by the server
  as `MapPacket` — **without a connection the game screen is empty** (no local `.rmap` is read, no
  procedural fallback). Plain taps that miss the touch controls are forwarded to the HUD widget
  layer, so scripted buttons work on screen. `ControlOverlay` handles touch: hold-to-move, pinch
  zoom, editable control widgets (semantic actions, persisted).''',
'''- Android: `GameView` draws snapshots + the `UiLayout` schema; the floor is streamed by the server
  as `MapPacket` — **without a connection the game screen is empty** (no local `.rmap` is read, no
  procedural fallback). Tile rendering bounds iteration to the visible isometric viewport rather
  than scanning the entire map every frame. The main menu's Servers screen exposes the last-used
  address and a local-host-and-join flow. Plain taps that miss the touch controls are forwarded to
  the HUD widget layer, so scripted buttons work on screen. `ControlOverlay` handles touch:
  hold-to-move, pinch zoom, editable control widgets (semantic actions, persisted).''',
"document Android renderer and server menu"
)
])

edit("docs/status.md", [
(
'''- Android client: `GameView` guest renderer (floor streamed as `MapPacket`, snapshot sprites,
  `UiLayout` renderer, scriptable HUD screens), touch controls with editable/persisted layouts,
  pinch zoom, tap-to-widget forwarding, local server, and a map-editor activity.''',
'''- Android client: `GameView` guest renderer (floor streamed as `MapPacket`, snapshot sprites,
  `UiLayout` renderer, scriptable HUD screens), viewport-culling for streamed map tiles, touch
  controls with editable/persisted layouts, pinch zoom, tap-to-widget forwarding, a Servers screen
  with last-used connection details and local-host-and-join flow, local server, and map editor.''',
"document Android changes"
),
(
'''- **Android device verification.** The game screen works end-to-end in the guest view (map floor,
  HUD, toasts, dialogs, controls) but still wants interactive testing on a real device (CI is
  SDK-only).''',
'''- **Android device verification.** The game screen, viewport culling, server menu, local-host-and-join
  flow, HUD, toasts, dialogs, and controls still need interactive verification on a real device
  (CI is SDK-only).''',
"document device verification"
),
(
'''- **Region integration verification.** Region catalog loading, portal transitions and state
  migration need end-to-end host testing; migration preserves entity identity but game-specific
  inventory/quest/progression persistence remains the host's responsibility.''',
'''- **Region integration verification.** Region catalog loading, deterministic initial-region selection,
  portal transitions, destination map delivery, and state migration need end-to-end host testing;
  migration preserves entity identity but game-specific inventory/quest/progression persistence
  remains the host's responsibility.''',
"document region verification"
)
])

checklist = ROOT / "docs/0.5.0-release-checklist.md"
if checklist.exists():
    raise SystemExit("docs/0.5.0-release-checklist.md already exists; refusing to overwrite it")
checklist.write_text('''# 0.5.0 release checklist

This is a practical release gate, not a request to polish every subsystem.

## Client
- [ ] Android tile viewport culling is present and the app no longer freezes after connecting.
- [ ] The Android Servers screen appears from the main menu.
- [ ] Connect to a server using the last-used address.
- [ ] Start a local host and join it from the same device.
- [ ] Verify snapshots, movement, camera follow, and streamed map rendering.
- [ ] Verify desktop client still connects and renders the streamed map.
- [ ] Verify smoothing snaps rather than interpolates across a region teleport.

## Regions and server
- [ ] `data/host/regions/*.rmap` loads as a catalog.
- [ ] `default.rmap`, when present, is always the initial spawn region.
- [ ] Without `default.rmap`, the lexicographically first region is selected.
- [ ] Walk through a portal A → B and confirm the entity ID remains stable.
- [ ] Destination map is sent after transition; view and movement remain correct.
- [ ] Return B → A and confirm entities from different regions are not mixed.
- [ ] Invalid portal targets fail catalog loading clearly.
- [ ] Lua-driven transitions identify the actual source region and stale cross-region requests are rejected.
- [ ] Check collision behavior around region boundaries and portals.

## CI and release
- [ ] Run the repository Gradle test/build workflows in CI.
- [ ] Build the Android APK and desktop client.
- [ ] Review CI artifacts and logs; successful compilation alone is not runtime verification.
- [ ] Update release notes and `docs/status.md` to match the tested state.
- [ ] Tag and publish 0.5.0 only after critical checks pass.

## Out of scope for the release gate
- Full animation system, pathfinding, inventory/combat framework, robust reconnect/auth, and account/database persistence.
- Further visual polish that does not block the documented 0.5.0 feature set.
''', encoding="utf-8")
print("created docs/0.5.0-release-checklist.md")
print("Patch applied. Review the diff before committing.")
