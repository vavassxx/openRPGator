# Getting started

## Requirements

- **JDK 21** — the desktop/server build. JVM toolchains are auto-resolved through the foojay
  resolver registered in `settings.gradle`, which also detects SDKMAN-installed JDKs
  (`~/.sdkman/candidates/java`); `sdk install java 21.x-ms` is enough, no manual `JAVA_HOME` setup.
- **Gradle 8.10+** (any 8.x works; the Android build needs an Android SDK + Android Gradle Plugin
  prerequisites).
- The Android APK additionally requires `ANDROID_HOME` pointing at an Android SDK.

## Build and test

    gradle build            # full build: desktop/server modules + Android APK + lint
    gradle test             # JVM module unit tests (engine-map/network/runtime/script/world, pak)

The Android debug APK lands at `android-client/build/outputs/apk/debug/android-client-debug.apk`.

The core/server modules do not depend on LWJGL, AWT, Android or native libraries.

## Run the dedicated server

    gradle :dedicated-server:installDist
    ./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
        --data-dir examples --port 27800

Flags: `--data-dir <dir>` (must contain a `host` sub-folder), `--port <n>`, `--tick-rate <hz>`
(world tick rate, default 20, clamped 1..240), `--help`. The server is headless — no root, systemd,
Docker or GPU required; the same JAR runs under Termux on Android ARM64:

    pkg install openjdk-21
    java -jar dedicated-server/build/libs/dedicated-server-0.4.0.jar --data-dir <dir> --port 27800

The Swing admin console runs the same server in-process (data dir + port persisted to
`~/.openrpgator/server.properties`):

    gradle :server-admin:installDist
    ./server-admin/build/install/server-admin/bin/server-admin

## Run the desktop client / editors

    gradle :desktop-client:installDist
    ./desktop-client/build/install/desktop-client/bin/desktop-client

    gradle :map-editor:installDist
    ./map-editor/build/install/map-editor/bin/map-editor

    gradle :script-editor:installDist
    ./script-editor/build/install/script-editor/bin/script-editor

The desktop client menu starts a **built-in local server** from the shared `data/host` folder
automatically (no map/resource pickers), or connects to a remote address configured in
`Menu → Settings`.

## Run on Android

Build the APK, install and launch. The app opens into the map editor; the **game** screen (icon)
is the guest view. **Local server** starts the same `ServerHost` code as the PC build inside the
app on the chosen port, then **Connect** (toggle button) joins it at `127.0.0.1`. Server content is
read from the app's `data/host` folder — exactly like the desktop flow.

## Shared data directory

All desktop tooling and the clients share `~/.openrpgator/data` (Android uses the same layout,
`<app-files>/data`):

- `data/host` — server content: the single `*.rmap` (auto-selected), every `*.pak` (streamed to
  clients during the handshake) and Lua scripts next to the map (loaded automatically). No
  per-map/per-pack selection anywhere.
- `data/pakcache` — client-side cache of packs downloaded from remote servers (re-skipped by name
  on later connects).
- Legacy `data/maps` / `data/paks` folders are migrated into `data/host` on first run.

The server accepts `--data-dir <dir>` to point at a different root (must contain `host`). The
map/script editors save into `data/host` as well.

## Rebuilding assets

Visual assets are packed into `.pak` containers with `PakTool`; the canonical command and the
source conventions live in [docs/assets.md](assets.md). The demo host has a ready-made pack at
`examples/host/basic.pak`.

## Demo host

`examples/host/` is a fully scripted test host (NPCs, dialogues, triggers, teleports, patrols,
runes that damage/heal players, and a host-driven HUD). Runbook and manual test checklist:
[examples/host/README.md](../examples/host/README.md).