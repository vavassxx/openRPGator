# Pak sources (`assets-src`)

Invocation (from repo root):

    gradle :pak:installDist
    java -cp "pak/build/install/pak/lib/*" rpg.engine.pak.PakTool pack \
        --root assets-src --out assets/basic.pak \
        --resize sprite=32x64 --resize tile=32x16
    cp assets/basic.pak examples/host/

- `tile/0..11.png` — floor/wall tiles (Kenney CC0, isometric dungeon). Diamond cells 32x16.
- `sprite/*.png` — 32x64 stand-up sprite cells (feet at the bottom row). Entity prefab names
  (`arch`, `chest`, `elder`, `exit`, `guard`, `merchant`, `player`, `rat`, `well`, `sky`) must
  match the prefabs in `examples/host/town.rmap` — the server maps prefab → pak key exactly.
  Originals (0x72 DungeonTileset II v1.7, CC0) are vendored in `vendor/0x72-DungeonTilesetII-v1.7/`,
  see the mapping table in `vendor/0x72-DungeonTilesetII-v1.7/README.md`.
- `KENNEY_CC0_LICENSE.txt` — license of the Kenney-derived parts (tiles).