#!/usr/bin/env python3
"""Generate, validate, and apply the openRPGator 0.5.0 server-hardening patch.

Run from the repository root on branch asteria-regions-mainline:
    python3 /path/to/apply-0.5.0-server-hardening.py

The script edits in memory first, writes a unified patch, runs `git apply --check`,
and only then applies it. It refuses unexpected source contexts or an existing patch file.
"""
from __future__ import annotations

import difflib
import subprocess
import sys
from pathlib import Path

ROOT = Path.cwd()
EXPECTED_BRANCH = "asteria-regions-mainline"
EXPECTED_HEAD_PREFIX = "dbb0e7706df9374036fd99fe8f3d59d07ad94f7a"
PATCH_PATH = ROOT / "openRPGator-0.5.0-server-hardening.patch"


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode != 0:
        raise SystemExit(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def replace_once(path: str, text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match for {label}, found {count}; nothing applied")
    return text.replace(old, new, 1)


def transform(path: str, replacements: list[tuple[str, str, str]]) -> tuple[str, str]:
    file = ROOT / path
    if not file.is_file():
        raise SystemExit(f"Missing required file: {path}")
    before = file.read_text(encoding="utf-8")
    after = before
    for old, new, label in replacements:
        after = replace_once(path, after, old, new, label)
    return before, after


if not (ROOT / ".git").exists():
    raise SystemExit("Run this script from the repository root (no .git directory found).")
branch = git("branch", "--show-current")
head = git("rev-parse", "HEAD")
if branch != EXPECTED_BRANCH:
    raise SystemExit(f"Expected branch {EXPECTED_BRANCH}, found {branch!r}; nothing applied")
if head != EXPECTED_HEAD_PREFIX:
    raise SystemExit(f"Expected HEAD {EXPECTED_HEAD_PREFIX}, found {head}; nothing applied")
if PATCH_PATH.exists():
    raise SystemExit(f"{PATCH_PATH.name} already exists; move it aside before rerunning.")

server_path = "dedicated-server/src/main/java/rpg/engine/server/ServerHost.java"
server_replacements = [
    (
'''    static final int MAX_INPUT_ACTIONS_PER_SECOND = 24;
    static final int MAX_NAME_LENGTH = 24;''',
'''    static final int MAX_INPUT_ACTIONS_PER_SECOND = 24;
    /** Incoming packets accepted per second per client, with a token-bucket burst allowance. */
    static final int MAX_INBOUND_PACKETS_PER_SECOND = 60;
    /** Bound concurrent socket readers; excess connections are rejected rather than spawning threads forever. */
    static final int MAX_CLIENT_CONNECTIONS = 64;
    /** Global bound on pending client packets. */
    static final int INBOUND_QUEUE_CAPACITY = 4096;
    /** Maximum number of queued packets processed during one world tick. */
    static final int MAX_INBOUND_PACKETS_PER_TICK = 512;
    static final int MAX_CONSECUTIVE_INBOUND_DROPS = 64;
    static final int MAX_CONSECUTIVE_QUEUE_OVERFLOWS = 8;
    static final int MAX_NAME_LENGTH = 24;''',
        "server ingress constants"),
    (
'''    private final RegionManager regions = new RegionManager();
    private volatile double playerSpeed = DEFAULT_PLAYER_SPEED;''',
'''    private final RegionManager regions = new RegionManager();
    private final BlockingQueue<InboundPacket> inboundPackets = new ArrayBlockingQueue<>(INBOUND_QUEUE_CAPACITY);
    private volatile int inboundPacketBudget = 240;
    private volatile double playerSpeed = DEFAULT_PLAYER_SPEED;''',
        "bounded inbound queue fields"),
    (
'''        playerSpeed = cfg.playerSpeed();
        viewRadius = cfg.viewRadius();
        regions.clear();''',
'''        playerSpeed = cfg.playerSpeed();
        viewRadius = cfg.viewRadius();
        inboundPackets.clear();
        inboundPacketBudget = Math.max(64, Math.min(MAX_INBOUND_PACKETS_PER_TICK, cfg.tickHz() * 12));
        regions.clear();''',
        "queue initialization"),
    (
'''                synchronized (worldLock) {
                    for (RegionRuntime region : regions.all())
                        region.runtime().setPlayers(playersIn(region.id()));
                    regions.tick(null);
                    broadcastSnapshots();
                }''',
'''                synchronized (worldLock) {
                    drainInboundPackets(inboundPacketBudget);
                    for (RegionRuntime region : regions.all())
                        region.runtime().setPlayers(playersIn(region.id()));
                    regions.tick(null);
                    broadcastSnapshots();
                }''',
        "tick-bounded packet processing"),
    (
'''        exec = Executors.newCachedThreadPool();
        server = new ServerSocket(cfg.port());''',
'''        exec = new ThreadPoolExecutor(0, MAX_CLIENT_CONNECTIONS, 30L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "openrpg-client");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        server = new ServerSocket(cfg.port());''',
        "bounded socket-reader pool"),
    (
'''        if (exec != null) exec.shutdownNow();
        if (tick != null) tick.shutdown();''',
'''        if (exec != null) exec.shutdownNow();
        if (tick != null) tick.shutdown();
        inboundPackets.clear();''',
        "clear queued packets on stop"),
    (
'''                Socket s = server.accept();
                exec.submit(() -> client(s));
            } catch (IOException e) {
                if (running) listener.error("accept error: " + e.getMessage());''',
'''                Socket s = server.accept();
                try {
                    exec.execute(() -> client(s));
                } catch (RejectedExecutionException full) {
                    try { s.close(); } catch (IOException ignored) { }
                    listener.error("Connection rejected: server is at client capacity");
                }
            } catch (IOException e) {
                if (running) listener.error("accept error: " + e.getMessage());''',
        "reject excess connections safely"),
    (
'''        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Packet hello = Protocol.read(in);
            if (!(hello instanceof Hello h)) return;
            synchronized (worldLock) { client = spawnPlayer(h.name(), out); }''',
'''        try (s) {
            // Do not let silent sockets occupy all bounded client-reader threads forever.
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Packet hello = Protocol.read(in);
            if (!(hello instanceof Hello h)) return;
            s.setSoTimeout(0);
            synchronized (worldLock) { client = spawnPlayer(h.name(), out); }''',
        "handshake timeout"),
    (
'''            clients.put(client.entityId, client);
            sendMap(client);
            while (running && !s.isClosed()) {
                Packet q = Protocol.read(in);
                if (q instanceof Input x) applyInput(client.entityId, x);
                else if (q instanceof DialogResponse r) {
                    synchronized (worldLock) {
                        currentRuntime(client.entityId).respondDialog(r.dialogId(), r.choice());
                    }
                } else if (q instanceof Cmd c) {
                    synchronized (worldLock) {
                        currentRuntime(client.entityId).dispatchCommand(client.entityId, c.code(), c.arg());
                    }
                }
            }''',
'''            clients.put(client.entityId, client);
            sendMap(client);
            int rateLimitDrops = 0;
            int queueOverflowDrops = 0;
            while (running && !s.isClosed()) {
                Packet q = Protocol.read(in);
                if (!client.inboundPackets.tryAcquire()) {
                    if (++rateLimitDrops >= MAX_CONSECUTIVE_INBOUND_DROPS) {
                        listener.error("Disconnecting " + client.name + ": inbound packet rate exceeded");
                        break;
                    }
                    continue;
                }
                rateLimitDrops = 0;
                if (!inboundPackets.offer(new InboundPacket(client, q))) {
                    if (++queueOverflowDrops >= MAX_CONSECUTIVE_QUEUE_OVERFLOWS) {
                        listener.error("Disconnecting " + client.name + ": inbound packet queue is full");
                        break;
                    }
                    continue;
                }
                queueOverflowDrops = 0;
            }''',
        "enqueue and rate-limit incoming packets"),
    (
'''    private void spawnPlayer(String rawName, OutputStream out) {''',
'''    private void spawnPlayer(String rawName, OutputStream out) {''',
        "unused compatibility guard"),
    (
'''            Optional<MapPortal> portal = regions.portalAt(region.id(), pos);
            portal.ifPresent(p -> transitionPlayer(entityId, region.id(), p.targetRegion(), p.targetPosition()));
            broadcastSnapshots();''',
'''            Optional<MapPortal> portal = regions.portalAt(region.id(), pos);
            if (c.portalGuard.shouldActivatePortal(portal.isPresent(), System.nanoTime())) {
                MapPortal p = portal.orElseThrow();
                transitionPlayer(entityId, region.id(), p.targetRegion(), p.targetPosition());
            }''',
        "portal re-entry guard and remove per-packet snapshots"),
    (
'''        String from = c.regionId;
        String expected = expectedSourceRegion == null ? null : expectedSourceRegion.trim().toLowerCase(Locale.ROOT);''',
'''        long transitionNow = System.nanoTime();
        if (!c.portalGuard.canTransition(transitionNow)) return false;
        String from = c.regionId;
        String expected = expectedSourceRegion == null ? null : expectedSourceRegion.trim().toLowerCase(Locale.ROOT);''',
        "transition cooldown check"),
    (
'''        if (!moved) return false;
        c.regionId = to;

        // Reconstruct only the host-owned session representation needed by the destination world.''',
'''        if (!moved) return false;
        c.regionId = to;
        c.portalGuard.onTransition(transitionNow);

        // Reconstruct only the host-owned session representation needed by the destination world.''',
        "arm portal exit guard after transition"),
    (
'''    private void broadcastSnapshots() {''',
'''    private void drainInboundPackets(int budget) {
        for (int i = 0; i < budget; i++) {
            InboundPacket inbound = inboundPackets.poll();
            if (inbound == null) return;
            Client client = inbound.client();
            // Ignore stale queued packets from a client that has already disconnected.
            if (client.dismissed || clients.get(client.entityId) != client) continue;
            try {
                Packet packet = inbound.packet();
                if (packet instanceof Input input) {
                    applyInput(client.entityId, input);
                } else if (packet instanceof DialogResponse response) {
                    currentRuntime(client.entityId).respondDialog(response.dialogId(), response.choice());
                } else if (packet instanceof Cmd command) {
                    currentRuntime(client.entityId).dispatchCommand(client.entityId, command.code(), command.arg());
                }
            } catch (RuntimeException rejected) {
                listener.error("Rejected packet from " + client.name + ": " +
                        (rejected.getMessage() == null ? rejected.getClass().getSimpleName() : rejected.getMessage()));
            }
        }
    }

    private record InboundPacket(Client client, Packet packet) {}

    private void broadcastSnapshots() {''',
        "bounded packet queue drain"),
    (
'''    private static final class Client {
        final long entityId;
        final String name;
        volatile String regionId;
        final OutputStream out;
        final ActionLimiter actions = new ActionLimiter(MAX_INPUT_ACTIONS_PER_SECOND);
        volatile long lastInputNanos;''',
'''    static final class PortalGuard {
        static final long COOLDOWN_NANOS = 500_000_000L;
        private boolean mustExitPortal;
        private long cooldownUntilNanos;

        boolean canTransition(long nowNanos) { return nowNanos >= cooldownUntilNanos; }

        boolean shouldActivatePortal(boolean insidePortal, long nowNanos) {
            if (mustExitPortal) {
                if (!insidePortal) mustExitPortal = false;
                return false;
            }
            return insidePortal && canTransition(nowNanos);
        }

        void onTransition(long nowNanos) {
            mustExitPortal = true;
            cooldownUntilNanos = nowNanos + COOLDOWN_NANOS;
        }
    }

    private static final class Client {
        final long entityId;
        final String name;
        volatile String regionId;
        final OutputStream out;
        final ActionLimiter actions = new ActionLimiter(MAX_INPUT_ACTIONS_PER_SECOND);
        final ActionLimiter inboundPackets = new ActionLimiter(MAX_INBOUND_PACKETS_PER_SECOND);
        final PortalGuard portalGuard = new PortalGuard();
        volatile long lastInputNanos;''',
        "client packet limiter and portal guard"),
]

# Drop the deliberate no-op marker before applying replacements; it exists only to keep this
# script's replacement table easy to review if the spawnPlayer signature ever changes.
server_replacements = [r for r in server_replacements if r[2] != "unused compatibility guard"]

android_path = "android-client/src/main/java/rpg/engine/android/MainActivity.java"
android_replacements = [
    (
'''        root.addView(subtitle);

        root.addView(sectionLabel("SERVER ADDRESS"));''',
'''        root.addView(subtitle);

        root.addView(sectionLabel("SAVED SERVERS"));
        LinearLayout savedList = new LinearLayout(this);
        savedList.setOrientation(LinearLayout.VERTICAL);
        List<String[]> savedServers = loadSavedServers();
        if (savedServers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No saved servers yet. Connect once to add an address.");
            empty.setTextColor(Color.rgb(120, 129, 146));
            empty.setTextSize(12);
            empty.setPadding(0, dp(4), 0, dp(10));
            savedList.addView(empty);
        } else {
            for (String[] server : savedServers) {
                String savedHost = server[0];
                String savedPort = server[1];
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(2), dp(4), dp(2));
                row.setBackgroundColor(Color.rgb(27, 30, 38));
                TextView address = new TextView(this);
                address.setText(savedHost + ":" + savedPort);
                address.setTextColor(Color.rgb(225, 230, 240));
                address.setTextSize(13);
                row.addView(address, new LinearLayout.LayoutParams(0, dp(44), 1));
                Button use = actionButton("Connect", "Connect to " + savedHost + ":" + savedPort);
                use.setOnClickListener(v -> connectToServer(savedHost, savedPort,
                        prefs.getString("last_name", "player")));
                row.addView(use, new LinearLayout.LayoutParams(-2, dp(44)));
                Button remove = actionButton("×", "Remove saved server");
                remove.setOnClickListener(v -> {
                    List<String[]> current = loadSavedServers();
                    current.removeIf(entry -> entry[0].equals(savedHost) && entry[1].equals(savedPort));
                    saveSavedServers(current);
                    showServerMenu();
                });
                row.addView(remove, new LinearLayout.LayoutParams(-2, dp(44)));
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.bottomMargin = dp(5);
                savedList.addView(row, rowLp);
            }
        }
        root.addView(savedList, new LinearLayout.LayoutParams(-1, -2));

        root.addView(sectionLabel("SERVER ADDRESS"));''',
        "saved server list UI"),
    (
'''        connect.setOnClickListener(v -> {
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
        });''',
'''        connect.setOnClickListener(v -> connectToServer(
                host.getText().toString().trim(), port.getText().toString().trim(),
                name.getText().toString().trim()));''',
        "reuse connection validation for saved servers"),
    (
'''            prefs.edit().putString("last_host", "127.0.0.1").putString("last_port", p)
                .putString("last_name", n).apply();
            showGame();''',
'''            prefs.edit().putString("last_host", "127.0.0.1").putString("last_port", p)
                .putString("last_name", n).apply();
            rememberSavedServer("127.0.0.1", p);
            showGame();''',
        "remember local server address"),
    (
'''        back.setOnClickListener(v -> showMainMenu());
        setContentView(root);
    }

    private void onMenu(String label) {''',
'''        back.setOnClickListener(v -> showMainMenu());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void connectToServer(String host, String port, String name) {
        try {
            int portNumber = Integer.parseInt(port);
            if (host.isEmpty() || name.isEmpty() || portNumber < 1 || portNumber > 65535)
                throw new IllegalArgumentException("Enter a host, name, and valid port (1–65535)");
            prefs.edit().putString("last_host", host).putString("last_port", port)
                    .putString("last_name", name).apply();
            rememberSavedServer(host, port);
            showGame();
            if (connectButton != null) connectButton.performClick();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Cannot connect")
                    .setMessage(e.getMessage() == null ? "Invalid server address or port" : e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private List<String[]> loadSavedServers() {
        List<String[]> result = new ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(prefs.getString("saved_servers", "[]"));
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String host = item.optString("host", "").trim();
                String port = item.optString("port", "").trim();
                if (!host.isEmpty() && !port.isEmpty()) result.add(new String[]{host, port});
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void rememberSavedServer(String host, String port) {
        List<String[]> next = new ArrayList<>();
        next.add(new String[]{host, port});
        for (String[] entry : loadSavedServers()) {
            if (entry[0].equalsIgnoreCase(host) && entry[1].equals(port)) continue;
            if (next.size() >= 8) break;
            next.add(entry);
        }
        saveSavedServers(next);
    }

    private void saveSavedServers(List<String[]> servers) {
        org.json.JSONArray array = new org.json.JSONArray();
        try {
            for (String[] server : servers) {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("host", server[0]);
                item.put("port", server[1]);
                array.put(item);
            }
            prefs.edit().putString("saved_servers", array.toString()).apply();
        } catch (org.json.JSONException e) {
            logger.error("Could not save server list", e);
        }
    }

    private void onMenu(String label) {''',
        "saved-server persistence helpers and scrollable menu"),
]

policy_path = "dedicated-server/src/test/java/rpg/engine/server/ServerHostPolicyTest.java"
policy_replacements = [
    (
'''    @Test
    void actionLimiterRejectsInvalidCapacity() {''',
'''    @Test
    void portalGuardRequiresLeavingDestinationPortalBeforeReactivation() {
        ServerHost.PortalGuard guard = new ServerHost.PortalGuard();
        long now = 1_000_000_000L;
        assertTrue(guard.shouldActivatePortal(true, now));
        guard.onTransition(now);
        assertFalse(guard.canTransition(now + 100_000_000L));
        assertFalse(guard.shouldActivatePortal(true, now + 1_000_000_000L),
                "standing inside a destination portal must not immediately retrigger it");
        assertFalse(guard.shouldActivatePortal(false, now + 1_100_000_000L),
                "the first frame outside a portal only rearms the guard");
        assertTrue(guard.shouldActivatePortal(true, now + 1_200_000_000L),
                "a portal can activate again after leaving and re-entering");
    }

    @Test
    void portalGuardAllowsTransitionsAgainAfterCooldown() {
        ServerHost.PortalGuard guard = new ServerHost.PortalGuard();
        long now = 2_000_000_000L;
        guard.onTransition(now);
        assertFalse(guard.canTransition(now + ServerHost.PortalGuard.COOLDOWN_NANOS - 1));
        assertTrue(guard.canTransition(now + ServerHost.PortalGuard.COOLDOWN_NANOS));
    }

    @Test
    void actionLimiterRejectsInvalidCapacity() {''',
        "portal guard tests"),
]

docs_path = "docs/status.md"
docs_replacements = [
    (
'''- `.pak` asset pipeline end-to-end (pack → stream → cache → render).''',
'''- Dedicated server ingress protection: bounded concurrent socket readers, a bounded inbound packet
  queue drained with a per-tick budget, per-client packet rate limits, and rejection of sustained
  floods or queue overload. Portal arrival requires leaving the destination portal zone before another
  portal transition can trigger.
- `.pak` asset pipeline end-to-end (pack → stream → cache → render).''',
        "document bounded packet ingress"),
    (
'''- Android client: `GameView` guest renderer (floor streamed as `MapPacket`, snapshot sprites,
  `UiLayout` renderer, scriptable HUD screens), viewport-culling for streamed map tiles, touch
  controls with editable/persisted layouts, pinch zoom, tap-to-widget forwarding, a Servers screen
  with last-used connection details and local-host-and-join flow, local server, and map editor.''',
'''- Android client: `GameView` guest renderer (floor streamed as `MapPacket`, snapshot sprites,
  `UiLayout` renderer, scriptable HUD screens), viewport-culling for streamed map tiles, touch
  controls with editable/persisted layouts, pinch zoom, tap-to-widget forwarding, a scrollable Servers
  screen with a persisted list of up to eight recent addresses, one-tap connection, local-host-and-join
  flow, local server, and map editor.''',
        "document persisted Android server list"),
    (
'''- **Region integration verification.** Region catalog loading, deterministic initial-region selection,
  portal transitions, destination map delivery, and state migration need end-to-end host testing;''',
'''- **Region integration verification.** Region catalog loading, deterministic initial-region selection,
  portal re-entry protection, destination map delivery, and state migration need end-to-end host testing;''',
        "document portal re-entry protection"),
]

checklist_path = "docs/0.5.0-release-checklist.md"
checklist_replacements = [
    (
'''- [ ] The Android Servers screen appears from the main menu.
- [ ] Connect to a server using the last-used address.''',
'''- [ ] The Android Servers screen appears from the main menu.
- [ ] Saved server addresses persist across app restarts; connect and remove entries from the list.
- [ ] Connect to a server using the last-used address.''',
        "add saved server list release check"),
    (
'''- [ ] Lua-driven transitions identify the actual source region and stale cross-region requests are rejected.
- [ ] Check collision behavior around region boundaries and portals.''',
'''- [ ] Lua-driven transitions identify the actual source region and stale cross-region requests are rejected.
- [ ] Arriving inside a destination portal does not immediately trigger another transition; the player must leave its zone first.
- [ ] Verify inbound packets are rate-limited, queued within a fixed bound, and drained within a per-tick budget.
- [ ] Verify excess simultaneous connections and sustained packet floods are rejected without crashing the host.
- [ ] Check collision behavior around region boundaries and portals.''',
        "add server-hardening release checks"),
]

changes: dict[str, tuple[str, str]] = {}
for path, replacements in [
    (server_path, server_replacements),
    (android_path, android_replacements),
    (policy_path, policy_replacements),
    (docs_path, docs_replacements),
    (checklist_path, checklist_replacements),
]:
    changes[path] = transform(path, replacements)

patch_parts: list[str] = []
for path, (before, after) in changes.items():
    if before == after:
        continue
    diff = difflib.unified_diff(
        before.splitlines(keepends=True), after.splitlines(keepends=True),
        fromfile=f"a/{path}", tofile=f"b/{path}", n=3,
    )
    patch_parts.extend(diff)
if not patch_parts:
    raise SystemExit("No changes produced; nothing applied.")
patch_text = "".join(patch_parts)
PATCH_PATH.write_text(patch_text, encoding="utf-8")

check = subprocess.run(["git", "apply", "--check", str(PATCH_PATH)], cwd=ROOT,
                       text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
if check.returncode != 0:
    PATCH_PATH.unlink(missing_ok=True)
    raise SystemExit("git apply --check failed; no files changed:\n" + (check.stderr or check.stdout))

apply = subprocess.run(["git", "apply", str(PATCH_PATH)], cwd=ROOT,
                       text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
if apply.returncode != 0:
    raise SystemExit("git apply failed after a successful check:\n" + (apply.stderr or apply.stdout))
print("Patch validated with git apply --check and applied successfully.")
print("Changed files:")
for path, (before, after) in changes.items():
    if before != after:
        print(f"  {path}")
print(f"Patch saved as {PATCH_PATH.name}; review with: git diff --check && git diff")
print("Run the dedicated-server tests and normal CI builds before committing.")
