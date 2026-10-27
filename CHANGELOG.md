# Changelog

## Asteria 0.5.0 — release candidate

### Added
- Region catalog loading from `data/host/regions/` with deterministic initial-region selection.
- `.rmap` v3 portal records and host-validated transitions between loaded regions.
- Lua `engine.transition(...)` integration for host-requested region changes.
- Server-configurable initial camera zoom per region.
- Bounded inbound packet processing and per-client rate limiting.
- Android server list, local-host-and-join flow, streamed map rendering, and configurable touch controls.

### Compatibility and scope
- `.rmap` v1/v2 maps remain readable; v3 adds portal records.
- Region migration preserves entity identity, but does not automatically persist game-specific inventory, quests, or progression.
- Server-side Lua executes with host-process capabilities. Only run trusted host scripts.
- This release is a playable engine foundation, not a production-complete MMO framework.

### Release validation
- See [the 0.5.0 release checklist](docs/0.5.0-release-checklist.md). Device-level Android behavior and end-to-end region transitions must be verified on target hardware; a successful build alone is not proof of runtime correctness.
