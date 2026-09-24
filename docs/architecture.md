# Architecture

Java 21, server-authoritative, 2.5D/isometric RPG engine targeting Linux/Windows/macOS and
Android/Termux.

## Module map

| Module | Responsibility |
|--------|----------------|
| `engine-core` | ECS world (`EntityId`, `WorldRegistry`, typed components), 2.5D math (`Vec2`, `WorldPosition`), `VarInts` |
| `engine-world` | `GameWorld` (step/snapshot), `CollisionWorld` (box/circle/polygon, slide-on-axis), `TriggerSystem` |
| `engine-map` | Binary `.rmap` format: `RMap` / `RMapIO` / `TileLayer` / `MapEntity` |
| `engine-network` | Length-framed binary protocol: `Hello`, `Welcome`, `PakList`, `PakChunk`, `Snapshot`, `Input`, `MapPacket`, `UiLayout`, `Script`, `Cmd`, legacy `Notify`/`Dialog`/`DialogResponse` |
| `engine-script` | LuaJ runtime: `LuaApi` (`engine`/`world` tables), entity facades, `UiSink` |
| `engine-runtime` | `GameRuntime` — `loadMap()`, deterministic `tick()`, host bookkeeping (`world.players()`) |
| `pak` | `PakTool` (pack/unpack CLI), `PakAssets` sprite/tile index |
| `renderer-api` | `Renderer` interface; `renderer-desktop` — LWJGL implementation (no native dep in the core) |
| `dedicated-server` | Headless authoritative `ServerHost`/`ServerConfig` + CLI (`--data-dir`, `--port`, `--tick-rate`) |
| `server-admin` | Swing console running the dedicated server in-process |
| `map-editor`, `script-editor` | Desktop editors (tile/collision painting, entity + `.pak` sprite placement, embedded Lua editor, undo/redo) |
| `desktop-client` | LWJGL client: built-in local server, settings/controls screens, HUD/toasts/dialogs |
| `android-client` | `GameView` guest renderer, touch `ControlOverlay`, embedded `ServerHost` (`LocalServerBackend`), map editor activity, `AppStorage` |

Only the frontend modules depend on LWJGL/AWT/Android. The core and server modules are plain JVM.

## World model

- ECS-like entity/component world with a **deterministic tick loop**.
- 2.5D coordinates: an entity at world `(x, y)` is drawn at `ox + (x − y)·hw`, `oy + (x + y)·hh`
  (iso projection), with `elevation` lifted along `−y`.
- Movement is box/circle/polygon collision + slide-on-axis (`CollisionWorld`); the `.rmap`
  `collision` layer is not yet wired into it (walls are passable — see [status.md](status.md)).

## Network protocol

TCP, length-framed, JSON-free (except the `UiLayout` payload, which is JSON by design).

1. Client connects, sends `Hello`.
2. Server streams `PakList(8)` (name/size), then `PakChunk(9)` (64 KiB chunks, with offsets), then
   `Welcome(2)`. Old servers that only send `Welcome` are still supported.
3. Clients send `Input` (movement deltas + an action bitmask: `MOVE_*`, `PRIMARY`, `SECONDARY`,
   `INTERACT`, `INVENTORY`) — the wire is not tied to any particular touch/keyboard layout — and
   `Cmd(12)` (custom commands whose values the host chooses, never the engine).
4. The server broadcasts `Snapshot` at the world tick rate (default 20 Hz, configurable 1..240).
5. Push UI travels as `UiLayout(10)` (see below). Legacy `Notify(5)`/`Dialog(6)`/`DialogResponse(7)`
   are read for compatibility and converted to `UiLayout`.
6. Right after `Welcome` the host streams the authoritative ground layer as `MapPacket(14)`: the
   client **never reads a local `.rmap`** — without a connection (or a server without a map) the
   game screen stays empty.
7. The host can push a small client-side Lua UI script as `Script(11)` — the "mini-sandbox" (see
   below).

## Host-driven UI (Lua → `UiLayout`)

Scripts drive the UI. `engine.notify(msg)` broadcasts a toast, `engine.dialog(player, text,
{choice,...}, fn)` opens a choice dialog for one player, and `engine.layout(player, widgets,
strings)` pushes a widget **schema** plus its strings. All three go through the embedding server's
`UiSink` and out as `UiLayout` packets; clients are dumb renderers of the schema and know nothing
about its semantics (HP bars, quests, mana — all script-defined).

Widget schema (`UiLayout` kind `"layout"`; coordinates/sizes are fractions of the screen `0..1`,
colors RGB(A) `0..1`, `ref` indexes the accompanying strings list):

```
{"type":"panel","x":..,"y":..,"w":..,"h":..,"bg":[r,g,b,a]}
{"type":"bar",  "x":..,"y":..,"w":..,"h":..,"value":n,"max":m,"fill":[r,g,b],"back":[r,g,b]}
{"type":"text", "x":..,"y":..,"ref":idx,"size":n,"color":[r,g,b]}
{"type":"button","x":..,"y":..,"w":..,"h":..,"ref":idx,"cmd":n,"value":"string"}
```

Both clients render all four types; the bar's fill uses the `fill` key on both desktop and Android.
A `button` is the interactive control: the host picks `cmd` (and an optional payload `value`), and
when the player clicks/taps it the client forwards the command — see
[Client UI sandbox & commands](#client-ui-sandbox--commands). See
[examples/host/README.md](../examples/host/README.md) for a worked example (`hud.lua` → HP bar +
inventory screen).

## Client UI sandbox & commands

To open inventories and similar screens **without hardcoding them into the clients**, the host can
push a small Lua script with `engine.send_script(player, source)` (wire packet `Script`). The script
runs **on the client** in a restricted runtime (`ClientScriptEngine`) — a real sandbox: `io`, `os`,
`package`, `debug`, `luajava` and file loaders are stripped, only the `ui.*` API is exposed:

| Lua | Description |
|-----|-------------|
| `ui.layout(widgets, strings)` | Replace the widget overlay (same schema as `engine.layout`) |
| `ui.send(cmd, arg)` | Forward a host-chosen custom command to the server (wire packet `Cmd`) |
| `ui.notify(msg)` | Local toast (never leaves the client) |
| `ui.on_command(fn)` | `fn(cmd, arg)` fires when a button widget with that `cmd` is pressed |
| `ui.on_layout(fn)` | `fn(widgets, strings)` fires on each server layout; without handlers the layout passes through untouched |
| `ui.clear()` | Empty the overlay (dismiss a script-built screen) |

Flow: the player presses a button → the client script builds the screen locally via `ui.layout`
(fast, no round-trip), and forwards chosen values via `ui.send` → the server's
`engine.on_command(player, code, arg)` reacts. Without a script, button presses send the raw
`Cmd` straight to the server. The command values (9001, `"sword"`, …) are defined entirely by the
host script — the engine and clients never interpret them.

## `.pak` asset pipeline

Instead of hardcoded textures, assets ship as **`.pak` containers**:

- **Format:** magic `PK01`, version, then an index (name → w/h/len/offset) and raw RGBA rasters
  (uncompressed — clients need no image decoder). Namespaces: `tile/…`, `sprite/…`, `ui/…`.
- **Delivery:** streamed during the handshake (see above), cached client-side in `data/pakcache`
  by name; cached packs are re-skipped on later connects. The desktop client shows a **LOADING**
  screen with progress (Esc cancels); Android writes chunks to the cache file and reloads its atlas
  per pack.
- **Sprite indices:** the client builds its sprite array from the sorted union of `sprite/*` keys
  across all packs; `Snapshot` maps `resource` → `sprite/<n>` and `resource == -1` → `sprite/player`.
  Servers derive identical indices from the host packs via `PakAssets.spriteKeyIndex(paks)`. When no
  packs are present the engine falls back to prefab-sorted indices and flat color fills.
- **Rendering:** tiles are drawn as textured iso diamonds, entities as textured billboards, with a
  color-fill fallback when a texture is missing.

Packing sources into a `.pak`: [docs/assets.md](assets.md).

## Rendering & input

- Desktop: LWJGL window, camera transform, screen-space HUD/toasts/dialogs (`resetView()`), a 5×7
  bitmap font (ASCII + Cyrillic), one-shot key/mouse edges (`keyPressed`, `consumeKey`) for menus,
  text input and in-game rebinding. Movement is screen-relative: WASD/arrows are converted through
  the inverse iso projection (`D`/`Right` move right on screen, not along a world axis).
- Android: `GameView` draws snapshots + the `UiLayout` schema; the floor is streamed by the server
  as `MapPacket` — **without a connection the game screen is empty** (no local `.rmap` is read, no
  procedural fallback). Plain taps that miss the touch controls are forwarded to the HUD widget
  layer, so scripted buttons work on screen. `ControlOverlay` handles touch: hold-to-move, pinch
  zoom, editable control widgets (semantic actions, persisted).
- Camera follows the local player by default on both clients; the **Camera follow** action (`C` on
  desktop, a ⌖ touch button on Android, present in the default and migrated control layouts) toggles
  it off — the world then keeps the last camera position.
- Desktop controls are rebindable (`Menu → Controls`): 9 actions, defaults `W/A/S/D`, `J/K/L/I` and
  `C` (camera-follow toggle), arrows always move too; binds persist to
  `~/.openrpgator/client.properties`. Android control widgets (buttons/joysticks) are
  user-movable/resizable/remappable and persisted locally.

## GameRuntime lifecycle (server side)

```
GameRuntime.loadMap(path)   -> for each entity with a script: api.executeOwned(...) → world.tile/set_tile/size available
GameRuntime.tick()          -> world.step() (triggers) → setTick → dispatchTick (global + per-entity on_tick)
```

The host embedding server reports connected players to scripts through `world.players()`
(`GameRuntime.setPlayers`); the engine itself has no opinion about what a "player" is, so
host-driven mechanics (like HP in `hud.lua`) stay entirely in Lua. See
[docs/scripting.md](scripting.md).