# openRPGator region integration — step 2

Overlay for branch `asteria-regions-mainline`.

This step moves the **region identity/lifecycle boundary** into `engine-runtime` without yet
changing `ServerHost` or Lua transition dispatch.

Included:
- `GameWorld(long entityIdBase)` — region-local entity namespace.
- `GameRuntime(regionId, entityIdBase)` — explicitly region-local simulation runtime.
- `RegionRuntime` — host-facing lifecycle wrapper for a region.
- Backward-compatible no-arg constructors remain available.

Not included deliberately:
- ServerHost multi-region orchestration.
- Player migration.
- Lua `engine.transition`.
- Network protocol changes.

Those belong in the next slices, after this boundary is in place.
