# openRPGator region integration — step 3

Overlay this archive on `asteria-regions-mainline` after step 2.

This step introduces `RegionManager`, the engine-runtime owner for a server's set of region runtimes.
It loads `RegionCatalog`, assigns per-region entity ID namespaces, ticks all regions, resolves portals,
and provides host-locked ECS migration.

It intentionally does not modify networking or `ServerHost` yet. The next integration step can make
`ServerHost` consume this manager while keeping connection/session state outside region runtimes.

Commit:

    git add .
    git commit -m "engine: add region manager and migration"
    git push
