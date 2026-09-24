# Status

Target version: **0.4.0**. This repository is a real, buildable foundation/vertical slice, not a
collection of placeholder classes.

## Implemented

- Multi-module Java 21 build layout (desktop/server + Android APK).
- Engine: ECS world/components, deterministic 2.5D coordinates, box/circle/polygon collision with
  slide-on-axis movement, trigger system.
- Binary `.rmap` map format with round-trip support (`InputStream`/`OutputStream` on Android).
- LuaJ scripting runtime with a public engine API (see [scripting.md](scripting.md)).
- TCP length-framed multiplayer protocol: snapshots, input bitmask, `.pak` streaming handshake,
  `UiLayout` push UI (toasts, dialogs, host-driven HUD) with legacy-packet compatibility.
- **Map over the network**: `MapPacket` streams the authoritative ground layer right after
  `Welcome` — clients never read `.rmap` locally; without a connection the game screen is empty.
- **Client mini-sandbox**: the host pushes small Lua scripts (`Script`) that run in a restricted
  `ui.*` runtime on the client (custom HUD screens, no client hardcoding) and receives
  host-chosen custom commands back (`Cmd` → `engine.on_command`).
- Headless authoritative server (same `ServerHost` code everywhere: dedicated CLI, Swing admin
  console, desktop local server, Android local server).
- Desktop OpenGL renderer + client: local server, server settings, rebindable controls
  (incl. camera-follow toggle), in-game HUD/toasts/dialogs.
- Full map editor (tile/collision painting, `.pak` sprite placement, entity inspector with
  embedded Lua editor, undo/redo) and a standalone script editor — desktop and Android-native.
- Android client: `GameView` guest renderer (floor streamed as `MapPacket`, snapshot sprites,
  `UiLayout` renderer, scriptable HUD screens), touch controls with editable/persisted layouts,
  pinch zoom, tap-to-widget forwarding, local server, and a map-editor activity.
- `.pak` asset pipeline end-to-end (pack → stream → cache → render).
- Unit tests + smoke tests for the engine modules.

## Not yet production-complete

- Full texture atlas and animation system (single-frame `.pak` sprites/tiles work end-to-end).
- Prediction/interpolation and robust reconnect/authentication.
- Packaging/signing for each desktop target.
- Sandbox policy (full LuaJ access by design for the **host** runtime; host responsibility per
  project policy — server-pushed *client* scripts do run in a restricted `ui.*` sandbox).
- Persistence/database layer and account/auth system.

## Known gaps (roadmap)

Findings from running the scripted demo host ([examples/host/README.md](../examples/host/README.md)):

- **Tile collisions.** The `.rmap` `collision` layer does not yet affect movement —
  `CollisionWorld` only considers entities; walls are passable.
- **Animations/atlases.** Single-frame sprites stream and render; no walk cycles or state-driven
  sprite swaps.
- **Inventory/combat.** Purchase and chests are toasts only; there is no inventory component.
  Health is already host-driven (`hud.lua` stores and applies HP in Lua; the client just renders
  the `engine.layout` schema), and the mini-sandbox demo (`hud.lua` → inventory screen via
  `engine.send_script` + `ui.on_command` + `Cmd`) shows how a scripted inventory can be opened
  and driven without client changes; death/revive can be scripted the same way.
- **Persistence and multiplayer quest state.** Quest state lives in the script (the first player
  to take a quest wins it); no world saves, and players share one script state.
- **Smarter AI.** Patrols teleport between points and ignore `CollisionWorld`; no pathfinding.
- **Android device verification.** The game screen works end-to-end in the guest view (map floor,
  HUD, toasts, dialogs, controls) but still wants interactive testing on a real device (CI is
  SDK-only).