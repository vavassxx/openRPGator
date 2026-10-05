#!/bin/sh
set -eu
LUA_API="engine-script/src/main/java/rpg/engine/script/lua/LuaApi.java"
GAME_RUNTIME="engine-runtime/src/main/java/rpg/engine/runtime/GameRuntime.java"
[ -f "$LUA_API" ] && [ -f "$GAME_RUNTIME" ] || { echo "Missing target files"; exit 1; }
python3 - "$LUA_API" "$GAME_RUNTIME" <<'PY'
from pathlib import Path
import sys
lua_path, game_path = map(Path, sys.argv[1:])
s = lua_path.read_text()
repls = [
('''    private volatile PlayerStore playerStore;\n    private volatile RegionTransitionSink regionTransitionSink;\n    private volatile WorldPosition spawnPoint = new WorldPosition(0, 0, 0);\n''', '''    private volatile PlayerStore playerStore;\n    private volatile RegionTransitionSink regionTransitionSink;\n    private volatile String regionId = "default";\n    private volatile WorldPosition spawnPoint = new WorldPosition(0, 0, 0);\n'''),
('''    public void setPlayerStore(PlayerStore store) { this.playerStore = store; }\n    public void setRegionTransitionSink(RegionTransitionSink sink) { this.regionTransitionSink = sink; }\n    public WorldPosition spawnPoint() { return spawnPoint; }\n''', '''    public void setPlayerStore(PlayerStore store) { this.playerStore = store; }\n    public void setRegionTransitionSink(RegionTransitionSink sink) { this.regionTransitionSink = sink; }\n    /** Sets the region identity used when Lua requests a cross-region transition. */\n    public void setRegionId(String regionId) {\n        if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("regionId is blank");\n        this.regionId = regionId.trim().toLowerCase(Locale.ROOT);\n    }\n    public WorldPosition spawnPoint() { return spawnPoint; }\n'''),
('''                return regionTransitionSink.transition(null, playerId, targetRegion, x, y, z)\n                        ? TRUE : FALSE;\n''', '''                return regionTransitionSink.transition(regionId, playerId, targetRegion, x, y, z)\n                        ? TRUE : FALSE;\n''')]
for old,new in repls:
    if old not in s: raise SystemExit('LuaApi expected block not found')
    s=s.replace(old,new,1)
lua_path.write_text(s)
s=game_path.read_text()
old='''        world = new GameWorld(entityIdBase);\n        scripts.bindWorld(world);\n'''
new='''        world = new GameWorld(entityIdBase);\n        scripts.api().setRegionId(this.regionId);\n        scripts.bindWorld(world);\n'''
if old not in s: raise SystemExit('GameRuntime expected constructor block not found')
game_path.write_text(s.replace(old,new,1))
PY
echo 'Step 7 applied.'
