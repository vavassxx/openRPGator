# openRPGator regions — step 6

Overlay this archive on `asteria-regions-mainline` after step 5, then run:

    sh apply-step6.sh

This is a small safety/ordering fix for the multi-region server:

- Lua transition requests now carry and validate their source region against the player's actual session region.
- A stale Lua callback from another region cannot teleport an arbitrary player.
- Portal transitions are resolved after the input's actions have been queued for the current region, avoiding an input-triggered transition changing the region before those queued actions are dispatched.

The engine still does **not** copy generic ECS/player state during migration. Persistent state remains host/script-owned.

Then review and commit:

    git add .
    git commit -m "server: validate region transitions"
    git push
