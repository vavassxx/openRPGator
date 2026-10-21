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
`GameRuntime(String, long)`.

## Host region catalog

The host loads region maps from `data/host/regions/`. Region IDs are normalized by the host and
must resolve to a catalog entry; portal targets that do not resolve are rejected rather than
creating an implicit region. Region maps use the normal `.rmap` format.

## Transitions

Map portals and Lua calls through `engine.transition(...)` request a host-controlled transition.
The server validates the source region and target, detaches the player entity from the old region,
and attaches it to the target region while retaining the entity ID. The destination world supplies
the new transform; clients receive the destination map and subsequent authoritative snapshots.

This is entity migration, not a general-purpose character persistence system. Only the engine
components explicitly migrated by the current implementation are carried across regions. Host game
logic remains responsible for inventory, quests, currency, progression, and other persistent data.

## Release verification

Before relying on transitions in a public host, test missing region IDs, malformed maps, concurrent
portal requests, reconnects during transition, and a player entering a destination while other
players remain active in both regions.
