#!/bin/sh
set -eu
python3 engine-script/src/main/java/rpg/engine/script/lua/LuaApi.patch.py
rm engine-script/src/main/java/rpg/engine/script/lua/LuaApi.patch.py
