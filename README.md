# openRPGator

Java 21, server-authoritative, 2.5D/isometric RPG engine targeting Linux/Windows/macOS and Android/Termux.

## Current vertical slice

- ECS-like world model with typed components
- 2.5D coordinates and deterministic tick loop
- lightweight collision primitives
- binary `.rmap` map format
- LuaJ scripting runtime and engine API
- Lua-driven entity and world events
- TCP server with length-framed JSON-free binary protocol
- headless server runnable as a plain JAR on JVM
- desktop map editor (grid canvas, tile/collision painting, entity placement with sprite previews,
  Lua script editor tab, undo/redo) and standalone script editor
- Swing admin console for the dedicated server (`server-admin`)
- desktop software renderer/client with no native dependency in the core
- desktop client settings screen (player name only — the built-in local server runs from the shared
  `data/host` folder automatically)
- `.pak` asset packs: PNG rasters packed into a binary container, streamed to clients during the
  connection handshake, cached locally (`~/.openrpgator/data/pakcache`) with a download progress
  screen, and rendered as entity sprites / tile textures by the desktop and Android clients

## Build

GitHub Actions builds the Linux x86_64/arm64 distributions and the universal Android APK. Locally, any Gradle 8.7.x installation can be used:

    gradle build

The server can be run with:

    gradle :dedicated-server:installDist
    ./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
        --data-dir examples --port 27800

Or from the Swing admin console (data dir + port persisted to `~/.openrpgator/server.properties`):

    gradle :server-admin:installDist
    ./server-admin/build/install/server-admin/bin/server-admin

Server content lives in the `host` sub-folder of the data directory: the single `*.rmap` there is
auto-selected, every `*.pak` is streamed to clients during the handshake and Lua scripts next to the
map are loaded automatically. There is no per-map/per-pack selection anymore.

To rebuild the visual assets from the PNG sources:

    gradle :pak:installDist
    java -cp "pak/build/install/pak/lib/*" rpg.engine.pak.PakTool pack \
        --root assets-src --out assets/basic.pak \
        --resize sprite=32x64 --resize tile=32x16

### Shared data directory

All desktop tooling and the clients share `~/.openrpgator/data` (Android uses the same layout
`<app-files>/data`):

- `data/host` — server content folder. Drop the map (`*.rmap`), Lua scripts (`.lua` next to the
  map) and packs (`*.pak`) here; servers auto-select the single map and stream every pack.
- `data/pakcache` — client-side cache of packs downloaded from remote servers (re-skipped by name
  on later connects).
- Legacy `data/maps` / `data/paks` folders are migrated into `data/host` on first run.

The server accepts `--data-dir <dir>` to point at a different root (it must contain a `host`
sub-folder). The map/script editors save into `data/host` as well.

Sprite indices refer to `sprite/<n>` entries: the client builds its sprite array from the sorted
union of `sprite/*` keys across all packs (`sprite/player` is used for entities the engine marks
with resource `-1`), and the servers derive the same indices from the host packs. When no packs are
present the engine falls back to prefab-sorted indices and flat color fills. Assets in `assets/` are
CC0-licensed (Kenney — see `assets-src/KENNEY_CC0_LICENSE.txt`). Demo tiles `tile/0..11` are packed
into `basic.pak` so clients render textured iso tiles with a color fill as a fallback.

A fully scripted test host (NPCs, dialogues, triggers, teleports, patrols) lives in `examples/host/`
— see [examples/host/README.md](examples/host/README.md) for the runbook and the open work items.

The core/server modules do not depend on LWJGL, AWT, Android or native libraries.

## Automated builds

Every push runs `.github/workflows/build.yml`. It produces:

- `openRPGator-linux-x86_64.tar.gz` — server, server-admin, Swing map editor and desktop client with x86_64 LWJGL natives.
- `openRPGator-linux-arm64.tar.gz` — server, server-admin, Swing map editor and desktop client with ARM64 LWJGL natives.
- `openRPGator-map-editor-universal.apk` — the Android app (map editor, game client view and embedded
  local server). The Android client connects over TCP like the desktop client and can host a local
  server from `data/host`; a separate foreground-service wrapper is still pending. The JVM server
  itself is headless and can run directly under Termux on ARM64.

## License

GPL-3.0. See [LICENSE](LICENSE).
