# Asset pipeline (`pak`)

Visual assets live as PNG sources in `assets-src/` and ship as **`.pak` containers** — see
[architecture.md](architecture.md) for the format, streaming and rendering. This page is the
canonical place for packing.

## Pack command

From the repository root:

    gradle :pak:installDist
    java -cp "pak/build/install/pak/lib/*" rpg.engine.pak.PakTool pack \
        --root assets-src --out assets/basic.pak \
        --resize sprite=32x64 --resize tile=32x16

Then place the pack where servers will find it (the demo host folder):

    cp assets/basic.pak examples/host/

`--resize` fits large sprites (e.g. Kenney 256×512 → 32×64); the per-namespace form
(`--resize tile=32x16`) keeps tiles in their own proportions. The tool packs `tile/*.png`,
`sprite/*.png` and `ui/*.png`.

## Source layout (`assets-src`)

- `tile/0..11.png` — floor/wall tiles (Kenney CC0, isometric dungeon). Diamond cells 32×16.
- `sprite/*.png` — 32×64 stand-up sprite cells (feet at the bottom row). Entity prefab names
  (`arch`, `chest`, `elder`, `exit`, `guard`, `merchant`, `player`, `rat`, `well`, `sky`, `lava`,
  `heal`) must match the prefabs in the maps — the server maps prefab → pak key exactly.
- `KENNEY_CC0_LICENSE.txt` — license of the Kenney-derived parts (tiles).

Original sprite sheets (0x72 DungeonTileset II v1.7, CC0) are vendored in
`vendor/0x72-DungeonTilesetII-v1.7/`; see its `README.md` for the mapping table. Assets in
`assets/` are CC0-licensed (Kenney).

## Delivery and caching

- During the handshake the server streams every `.pak` from `data/host` (`PakList` → `PakChunk` →
  `Welcome`).
- Clients cache packs in `data/pakcache` by name and re-skip them on later connects.
- The demo tile set `tile/0..11` is packed into `assets/basic.pak` so clients render textured iso
  tiles, with a flat-color fill as fallback.