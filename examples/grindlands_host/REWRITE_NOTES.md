# Rewrite notes

This version is based on the openRPGator master API from the supplied repository.

## Deliberate architectural changes

1. One `game.lua` world coordinator owns mutable game state and simulation policy.
2. Entity scripts only register entity-local event handlers and state.
3. NPCs use the shared Grindlands service instead of reaching into another script's private tables.
4. The map's script assignments were rewritten accordingly and the manager entity is first.
5. UI uses the current `engine.layout` / `UiLayout` contract.
6. The client script is a transparent bridge for `ClientScriptEngine`: it re-applies incoming layouts
   and forwards widget commands. It does not reverse-engineer UI strings into game state.
7. UI `ref` values are explicitly zero-based because the current `UiLayout` renderer indexes the
   JSON `strings` array directly.
8. Inventory/character screens are authoritative server layouts and are not overwritten by the
   periodic HUD refresh while open.
9. Disconnect cleanup is performed from the authoritative `world.players()` set.
10. XP level-up uses a loop so a large XP award can advance multiple levels.

No engine source files were modified by this package.
