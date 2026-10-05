# openRPGator regions — step 5

Overlay this archive on `asteria-regions-mainline` after step 4.

This step wires the region runtime into `ServerHost` and exposes host-owned Lua transitions.

### What changes

- `<data>/host/regions/*.rmap` is auto-discovered when present.
- If no region maps exist, the old single `<data>/host/*.rmap` path remains supported.
- Each connected player has a current `regionId`.
- Lua gets `engine.transition(player, region, x, y [, z])`.
- Portal transitions are authoritative and server-side.
- Snapshots are filtered by each player's current region.
- Each region receives only its own player IDs in `world.players()`.
- Destination `MapPacket` is sent immediately after a transition; protocol is unchanged.
- Persistent player state is NOT copied by the engine. Migration preserves only `EntityId`; the host reconstructs only the minimal session representation (`Name`/collider), while scripts remain responsible for persistent save/load and game state.

### Apply

1. Unzip over the branch checkout.
2. Run:

    `sh apply-lua.sh`

   The helper patches the existing `LuaApi.java` and removes itself afterward.
3. Review the diff and build locally.
4. Commit:

    `git add .`
    `git commit -m "server: wire multi-region player sessions"`
    `git push`

Do not add a generic ECS state snapshot here: that would violate the host-owned player-state contract.
