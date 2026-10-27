# openRPGator

**openRPGator** is an extensible, host-authoritative 2.5D RPG engine built with Java 21 and LuaJ. It provides a multiplayer server, desktop and Android clients, map/script editors, streamed `.rmap` maps and `.pak` assets, and a host-facing scripting API.

The **Asteria 0.5.0** release focuses on independently simulated map regions and server-controlled transitions between them, alongside the existing multiplayer, UI, scripting, and asset-streaming pipeline.

## Quick start

Requirements: JDK 21 and Gradle 8.x. Building the Android APK additionally requires an Android SDK.

```sh
gradle test
gradle :dedicated-server:installDist
./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
  --data-dir examples --port 27800 --tick-rate 20
```

The demo content is under `examples/host`. The server expects a data directory containing a `host/` folder; it streams the map and `.pak` assets to connected clients.

Build and run the desktop client in another terminal:

```sh
gradle :desktop-client:installDist
./desktop-client/build/install/desktop-client/bin/desktop-client
```

For Android build instructions, shared data paths, and editor startup, see [Getting started](docs/getting-started.md).

## Regions and portals

A host can place region maps in `data/host/regions/`. Each `.rmap` filename (without the extension) is the region ID. A file named `default.rmap` is preferred as the initial region; otherwise the host selects a deterministic region. Portals can target another region and a destination position. Lua scripts can request transitions through `engine.transition(...)`.

See [Region architecture](docs/regions.md), [Lua API](docs/scripting.md), and the two-region demo setup in [`examples/host/regions`](examples/host/regions/README.md).

## Project documentation

- [Getting started](docs/getting-started.md) — build, run, data directories, and tools.
- [Architecture](docs/architecture.md) — modules, simulation, networking, and rendering.
- [Lua scripting API](docs/scripting.md) — host API and restricted client UI scripts.
- [Regions](docs/regions.md) — catalog loading, portals, and migration boundaries.
- [Asset pipeline](docs/assets.md) — `.pak` creation and asset conventions.
- [Release status](docs/status.md) — implemented features and known limitations.
- [0.5.0 release checklist](docs/0.5.0-release-checklist.md) — release verification.

## Scope and limitations

This is an extensible RPG engine foundation, not a finished MMO framework. Inventory, combat, quests, persistence, account systems, pathfinding, and animation systems are not provided as complete built-in game systems; hosts can implement game-specific behavior in Lua. Server-side Lua runs with the capabilities of the host process, so only run scripts from trusted hosts.

## License

GPL-3.0; see [LICENSE](LICENSE).
