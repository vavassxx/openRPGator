# 0x72 DungeonTileset II v1.7 — original sources

Origin of the entity sprites in `assets-src/sprite/*.png` (player, guard, elder,
merchant, rat, chest, well, arch, exit). Tiles (`assets-src/tile/*.png`) are from a
Kenney CC0 pack, see `assets-src/KENNEY_CC0_LICENSE.txt`.

- Author: 0x72 (Deep-Fold) — https://0x72.itch.io/dungeontileset-ii
- Download: https://0x72.itch.io/dungeontileset-ii (file `0x72_DungeonTilesetII_v1.7.zip`)
- License: Creative Commons CC0 (public domain)
- Full readme of the pack: `README` (original, from the zip, `License.txt` claim below).

This folder is the unmodified content of the release zip (minus `__MACOSX`/`.DS_Store`
junk). The used frames (in `frames/`) are composed 2x into 32x64 stand-up sprite cells:

| `assets-src/sprite/*.png` | source frame (`frames/`) |
|---------------------------|--------------------------|
| `player` | `knight_m_idle_anim_f1.png` |
| `guard` | `dwarf_m_idle_anim_f1.png` |
| `elder` | `wizzard_m_idle_anim_f1.png` |
| `merchant` | `doc_idle_anim_f1.png` |
| `rat` | `goblin_idle_anim_f1.png` (pack has no rat — closest small mob) |
| `chest` | `chest_full_open_anim_f1.png` |
| `well` | `wall_fountain_basin_blue_anim_f1.png` |
| `arch` | `doors_leaf_open.png` |
| `exit` | `doors_leaf_closed.png` |
| `sky` | — tiny 2x2 placeholder kept from the previous pak (invisible conductor) |

The prehistoric `__MACOSX` entries and `.DS_Store` files from the zip are not vendored.