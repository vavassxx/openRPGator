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
- headless server runnable as a plain JAR on JVM/Termux
- desktop map editor and script editor
- desktop software renderer/client with no native dependency in the core
- desktop client settings screen (map file + resources directory for the built-in local server)
- `.pak` asset packs: PNG rasters packed into a binary container, streamed to clients during the
  connection handshake, cached locally (`~/.openrpgator/paks`) with a download progress screen, and
  rendered as entity sprites / tile textures by the desktop client

## Build

GitHub Actions builds the Linux x86_64/arm64 distributions and the universal Android map-editor APK. Locally, any Gradle 8.7.x installation can be used:

    gradle build

The server can be run with:

    gradle :dedicated-server:installDist
    ./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
        --map examples/village.rmap --port 27800

To stream visual assets to clients, build the pack with `PakTool` and pass it to the server:

    gradle :pak:installDist
    java -cp "pak/build/install/pak/lib/*" rpg.engine.pak.PakTool pack \
        --root assets-src --out assets/basic.pak --resize 32x64
    ./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
        --map examples/host/town.rmap --pak assets/basic.pak --port 27800

The desktop client shows a loading screen with progress while packs are downloaded and caches
them under `~/.openrpgator/paks` (re-skipped by name+size on later connects). Sprite indices refer
to `sprite/<n>` entries keyed by the alphabetically sorted map entity ids; `sprite/player` is used
for entities the server marks with resource `-1`. Assets in `assets/` are CC0-licensed (Kenney —
see `assets-src/KENNEY_CC0_LICENSE.txt`).

A fully scripted test host (NPCs, dialogues, triggers, teleports, patrols) lives in `examples/host/`
— see [examples/host/README.md](examples/host/README.md) for the runbook and the open work items.

The core/server modules do not depend on LWJGL, AWT, Android or native libraries.


## Automated builds

Every push runs `.github/workflows/build.yml`. It produces:

- `openRPGator-linux-x86_64.tar.gz` — server, Swing map editor and desktop client with x86_64 LWJGL natives.
- `openRPGator-linux-arm64.tar.gz` — server, Swing map editor and desktop client with ARM64 LWJGL natives.
- `openRPGator-map-editor-universal.apk` — the Android-native `.rmap` map editor as a universal APK.

The Android application is currently the map editor; there is not yet a separate Android client or Android server application. The JVM server itself is headless and can run directly under Termux on ARM64.

## License

GPL-3.0. See [LICENSE](LICENSE).
