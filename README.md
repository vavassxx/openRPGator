# openRPGator region integration — step 4 (host-state-safe)

Overlay this archive on `asteria-regions-mainline` after the step 3 fix.

This deliberately does **not** make ECS a generic player-state transfer mechanism.
`RegionManager.migrate()` moves only the stable entity identity and destination transform between
region-local worlds. Persistent player state remains host-owned; host/Lua code decides what to save,
restore, transform, or discard and which destination components to reconstruct.

The migration rolls back the entity identity if destination adoption fails.

Commit:

    git add .
    git commit -m "engine: keep player state host-owned during region migration"
    git push
