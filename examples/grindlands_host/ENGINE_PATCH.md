# Engine patch required

This host uses the `engine.on_action(function(player, action))` API added to the supplied openRPGator master.

The current master sends `Input.PRIMARY` from desktop/Android clients but `ServerHost` previously ignored it.
The patch routes PRIMARY/SECONDARY/INVENTORY as semantic actions to Lua on the next server tick.

Grindlands uses `primary` for combat and keeps `INTERACT`/`entity.on_interact` for interaction and gathering.
