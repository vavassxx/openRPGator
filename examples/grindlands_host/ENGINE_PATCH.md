# Engine patch required

This host uses the `engine.on_action(function(player, action))` API added to the supplied openRPGator master.

The current master sends `Input.PRIMARY` from desktop/Android clients but `ServerHost` previously ignored it.
The patch routes PRIMARY/SECONDARY/INVENTORY as semantic actions to Lua on the next server tick.

Grindlands uses `primary` for combat and keeps `INTERACT`/`entity.on_interact` for interaction and gathering.

## Inventory, persistence and per-player notifications

- Inventory UI now uses a 4x4 cell grid instead of a vertical list.
- Inventory can be filtered by all / weapons / consumables through command `9006`.
- The scripting API supports `engine.notify("text")` for broadcast and `engine.notify(player, "text")` for a single player.
- The scripting API supports `engine.save_player(player, table)` and `engine.load_player(player)`; the server stores one JSON document per player under `data/players/`.
- `engine.on_disconnect(function(player) ... end)` is called before the player entity is destroyed, allowing scripts to flush persistent state.
- Grindlands persists level, XP, HP/max HP, gold, equipped weapon, inventory, resources and position.
