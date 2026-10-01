# Regions

A region is an independently simulated map instance owned by the server. The engine does not
assign MMO semantics to the word: a region may be a town, dungeon, event map, tutorial, arena,
or overworld.

## Map format

`.rmap` version 3 adds `MapPortal` records. Versions 1 and 2 remain readable and simply contain
no portals. A portal has a source position/radius, a target region id and a destination position.

## Catalog

`RegionCatalog` loads `*.rmap` files from one server directory. The filename stem is the stable
region id. Startup validation rejects duplicate ids, invalid ids, duplicate portal ids within a
region, and portals pointing at unknown regions.

## Migration

`WorldRegistry.adopt(EntityId)` is the low-level primitive needed by a host to move an existing
entity id into another region registry without changing the player's identity. The eventual
host-level transition API should own the complete detach/attach operation; callers should not
need to manipulate ECS registries directly.
