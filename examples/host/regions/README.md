# Two-region portal smoke test

This directory contains two `.rmap` v3 maps used by the Asteria 0.5.0 region demo:

- `default.rmap` is the initial region and has a portal at `(10, 10)` targeting `forest`.
- `forest.rmap` has a return portal at `(5, 5)` targeting `default`.

Both maps reuse the demo town's tile layout but intentionally contain no map entities or Lua script references, so the portal test stays isolated and needs no additional asset pack. The portal records are embedded in the `.rmap` files; they are server-side region portals, separate from the older scripted demonstration arches in `../portal.lua` and `../portal_exit.lua`.

## Run

From the repository root:

```sh
gradle :dedicated-server:installDist
./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
  --data-dir examples --port 27800 --tick-rate 20
```

Connect with the desktop client, move to the portal position, and verify that the destination map arrives and the player entity remains connected. Walk back through the return portal to verify the reverse transition. `default.rmap` is intentionally named to exercise deterministic initial-region selection.

The sample validates the map/catalog/portal path, not game-specific persistence. Inventory, quests, currency, and other persistent character data remain host-owned.
