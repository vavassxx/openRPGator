# openRPGator

Java 21, server-authoritative, 2.5D/isometric RPG engine targeting Linux/Windows/macOS and
Android/Termux.

The core idea: **host-driven everything.** Scripts running on the server own the mechanics — even
the HUD is a widget schema pushed from Lua (`engine.layout`), and clients are dumb renderers of
snapshots and UI layouts.

## Highlights

- ECS-like world with typed components, deterministic 2.5D tick loop, collision + triggers.
- Binary `.rmap` maps with Lua scripts attached to entities (LuaJ, full reference in
  [docs/scripting.md](docs/scripting.md)).
- Headless authoritative server — the *same* `ServerHost` code runs as the dedicated CLI, inside
  the Swing admin console, inside the desktop client, and inside the Android app.
- TCP length-framed binary protocol: snapshots, semantic input bitmask, `.pak` streaming
  handshake, and `UiLayout` push UI (toasts, dialogs, host-driven HUD).
- `.pak` asset containers: PNG sources packed once, streamed to clients, cached locally, rendered
  as tile/sprites on desktop and Android.
- Desktop client (LWJGL) with local server, settings, rebindable controls and camera-follow;
  Android client with the game guest view, touch controls (editable/persisted), pinch zoom and a
  native map editor.
- Full map editor (tile/collision painting, entity placement with sprite previews, embedded Lua
  editor, undo/redo) and a standalone script editor.

## Quick start

Requirements: JDK 21 + Gradle 8.10+ (Android SDK for the APK). See
[docs/getting-started.md](docs/getting-started.md) for details, Termux, editors and tick-rate
configuration.

    gradle build                    # full build (desktop/server + Android APK)

Run the server with the scripted demo host:

    gradle :dedicated-server:installDist
    ./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
        --data-dir examples --port 27800

Run the desktop client:

    gradle :desktop-client:installDist
    ./desktop-client/build/install/desktop-client/bin/desktop-client

Android: install `android-client/build/outputs/apk/debug/android-client-debug.apk`, open the game
screen, press **Local server**, then **Connect**.

### Shared data directory

All tooling and clients share `data/host` — the single `*.rmap` there is auto-selected, every
`*.pak` is streamed to clients during the handshake and Lua scripts next to the map are loaded
automatically. No per-map/per-pack selection anywhere. (Desktop root `~/.openrpgator/data`,
Android `<app-files>/data`; remote packs cache into `data/pakcache`.)

## Documentation

| Doc | Covers |
|-----|--------|
| [docs/getting-started.md](docs/getting-started.md) | Requirements, build, run server/clients/editors, data directories, tick rate |
| [docs/architecture.md](docs/architecture.md) | Module map, protocol, `.pak` pipeline, host-driven UI, rendering |
| [docs/scripting.md](docs/scripting.md) | Lua scripting API reference |
| [docs/assets.md](docs/assets.md) | Packing assets into `.pak` containers |
| [docs/status.md](docs/status.md) | Implemented vs. roadmap / known gaps |
| [examples/host/README.md](examples/host/README.md) | The scripted demo host: runbook + manual test checklist |

## License

GPL-3.0. See [LICENSE](LICENSE). Asset sources in `assets-src/` are CC0 (Kenney); the original
0x72 DungeonTileset sheets are vendored under `vendor/` with their own CC0 notice.