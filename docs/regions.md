# Region runtime boundary

A `RegionRuntime` is the server-owned lifecycle object for one independently simulated map instance.
It has a stable normalized region id and contains one `GameRuntime`.

`GameRuntime` is now explicitly region-local and owns:
- the `GameWorld` / ECS registry;
- collision and trigger state;
- the region's `.rmap`;
- the region-local Lua runtime.

The region id is not an MMO-specific concept. A region can represent a town, dungeon, arena,
tutorial, event map, instance, or overworld.

Entity allocation can be namespaced per region through `GameWorld(long entityIdBase)` and
`GameRuntime(String, long)`. Cross-region player migration is intentionally not implemented here;
the host layer will own detach/attach of persistent player state in the next step.
