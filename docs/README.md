# Documentation

The documentation is split by audience and purpose. Start with the [project README](../README.md)
for the one-paragraph overview and quick start.

| Document | What it covers |
|----------|----------------|
| [getting-started.md](getting-started.md) | Requirements, build, run the server / clients / editors, data directories, tick rate, rebuilding assets |
| [architecture.md](architecture.md) | Module map, world model, network protocol, `.pak` pipeline, host-driven UI (Lua → `UiLayout`), rendering |
| [scripting.md](scripting.md) | The Lua scripting API reference (`engine` / `world` tables, entity facades, triggers, lifecycle) |
| [assets.md](assets.md) | Asset sources and the canonical `.pak` pack command |
| [status.md](status.md) | Implemented vs. not-yet-implemented, and the known gaps (roadmap) |\n| [0.5.0-release-checklist.md](0.5.0-release-checklist.md) | Manual/CI release gate for Asteria 0.5.0 |

Module- and demo-specific runbooks live next to their code:

- [dedicated-server/README.md](../dedicated-server/README.md) — running the headless server (incl. Termux).
- [examples/host/README.md](../examples/host/README.md) — the scripted demo host (`town.rmap` + Lua).\n- [examples/host/regions/README.md](../examples/host/regions/README.md) — the isolated two-region portal smoke test.