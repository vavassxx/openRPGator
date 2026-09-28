# Grindlands fixes

- Target HUD is conditional and compact; it no longer reserves a large central panel when there is no target.
- Android host-widget hit testing uses the same safe-area geometry as rendering, so Inventory and Character buttons are clickable.
- Mob aggro is cleared immediately when a player dies and is also invalidated every tick for dead/offline players.
