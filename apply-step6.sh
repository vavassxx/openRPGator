#!/bin/sh
set -eu
FILE="dedicated-server/src/main/java/rpg/engine/server/ServerHost.java"
python3 - "$FILE" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
old='''region.scripts().api().setRegionTransitionSink((ignored, id, target, x, y, z) ->\n                    transitionPlayer(id, target, new WorldPosition(x, y, z)));'''
new='''region.scripts().api().setRegionTransitionSink((source, id, target, x, y, z) ->\n                    transitionPlayer(id, source, target, new WorldPosition(x, y, z)));'''
if old not in s: raise SystemExit("transition sink block not found")
s=s.replace(old,new,1)
old='''            Optional<MapPortal> portal = regions.portalAt(region.id(), moved);\n            portal.ifPresent(p -> transitionPlayer(entityId, p.targetRegion(), p.targetPosition()));\n            broadcastSnapshots();'''
new='''            // Resolve the portal only after the input actions have been queued for this\n            // region. A transition must not make a queued action accidentally execute in\n            // the wrong region on the next tick.\n            Optional<MapPortal> portal = regions.portalAt(region.id(), moved);\n            portal.ifPresent(p -> transitionPlayer(entityId, region.id(), p.targetRegion(), p.targetPosition()));\n            broadcastSnapshots();'''
if old not in s: raise SystemExit("portal block not found")
s=s.replace(old,new,1)
old='''    private boolean transitionPlayer(long entityId, String targetRegion, WorldPosition target) {\n        Client c = clients.get(entityId);\n        if (c == null) return false;\n        String from = c.regionId;\n        String to = targetRegion == null ? null : targetRegion.trim().toLowerCase(Locale.ROOT);'''
new='''    private boolean transitionPlayer(long entityId, String expectedSourceRegion, String targetRegion, WorldPosition target) {\n        Client c = clients.get(entityId);\n        if (c == null) return false;\n        String from = c.regionId;\n        String expected = expectedSourceRegion == null ? null : expectedSourceRegion.trim().toLowerCase(Locale.ROOT);\n        // A region's Lua runtime may only move players that currently belong to that\n        // runtime. This prevents a stale/cross-region script callback from teleporting\n        // an arbitrary connected player.\n        if (expected == null || !from.equals(expected)) return false;\n        String to = targetRegion == null ? null : targetRegion.trim().toLowerCase(Locale.ROOT);'''
if old not in s: raise SystemExit("transition method header not found")
s=s.replace(old,new,1)
old='''            Optional<MapPortal> portal = regions.portalAt(region.id(), moved);\n            portal.ifPresent(p -> transitionPlayer(entityId, p.targetRegion(), p.targetPosition()));'''
# already replaced above; ensure no stale call remains
if 'transitionPlayer(entityId, p.targetRegion(), p.targetPosition())' in s: raise SystemExit("stale transition call remains")
p.write_text(s)
PY
