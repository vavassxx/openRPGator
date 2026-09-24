# Lua Scripting API

Scripts are attached to entities in `.rmap` files (the `script` field) and executed by
`GameRuntime.loadMap()`. Each script runs with `entity` / `self` bound to its owning entity as
stable facades — never raw ECS internals. Scripts can register persistent handlers which the
engine invokes each tick or on trigger/interact events.

No sandbox is applied to the **server** runtime — `JsePlatform.standardGlobals()` is used by
default. Host operators are responsible for the scripts they run. (Client-side scripts pushed by
the host are the opposite: they run in a restricted `ui.*` sandbox — see [Client UI sandbox](#client-ui-sandbox).)

## Engine table

| Lua | Return | Description |
|-----|--------|-------------|
| `engine.version` | string | API version (`"0.4.0"`) |
| `engine.tick` | number | Current world tick (updated per step) |
| `engine.log(msg)` | — | Prints `[Lua] msg` to stdout |
| `engine.on_tick(fn)` | — | Register a global tick handler (arg = tick number) |
| `engine.notify(msg)` | — | Broadcast a toast notification to all connected players (`UiSink.broadcastNotify`) |
| `engine.dialog(player, text, {choice,...}, fn(idx))` | — | Open a modal choice dialog for one player; `fn` fires with the 1-based choice on answer |
| `engine.layout(player, widgets, strings)` | — | Push a host-driven widget schema to one player (`UiSink.layoutTo`); clients render `panel`/`bar`/`text`/`button` blindly |
| `engine.send_script(player, source[, name])` | — | Push a small client-side Lua script (the "mini-sandbox") to one player (`UiSink.scriptTo` → wire packet `Script`) |
| `engine.on_command(fn)` | — | Register a handler for custom commands the client sends back (`fn(player, code, arg)`); the command values are host-chosen — engine/clients never decode them |

Dialogs/layouts/scripts require the embedding server to install a `UiSink`; without one the calls
are no-ops. `player` is an entity facade (e.g. `world.get('id')`) or a raw entity id. `engine.layout`
is the mechanism behind the scripted HUD — see [architecture.md](architecture.md) for the widget
schema and [examples/host/README.md](../examples/host/README.md) for a worked HP-bar example.

## Client UI sandbox

To open inventories and similar screens **without hardcoding them into the clients**, the host
pushes a small Lua script with `engine.send_script(player, source[, name])` (wire packet
`Script`). It runs **on the client**, inside `ClientScriptEngine` — a restricted runtime with
`io`, `os`, `package`, `debug`, `luajava` and the file loaders stripped. Only the `ui.*` table
is exposed:

| Lua | Description |
|-----|-------------|
| `ui.layout(widgets, strings)` | Replace the widget overlay (same schema as `engine.layout`) |
| `ui.clear()` | Empty the overlay (dismiss a script-built screen) |
| `ui.send(cmd, arg)` | Forward a host-chosen custom command to the server (wire packet `Cmd`) |
| `ui.notify(msg)` | Local toast (never leaves the client) |
| `ui.on_command(fn)` | `fn(cmd, arg)` fires when a `button` widget with that `cmd` is pressed |
| `ui.on_layout(fn)` | `fn(widgets, strings)` fires on each server layout; without handlers the layout passes through untouched |

Flow: the player presses a HUD `button` → the script builds the screen locally via `ui.layout`
(no round-trip) and forwards chosen values via `ui.send` → the server's
`engine.on_command(player, code, arg)` reacts. While its screen is open a script may ignore the
host's per-tick layouts (its `ui.on_layout` handler decides). Without a script, button presses
send the raw `Cmd` straight to the server. Command values (9001, `"sword"`, …) are defined
entirely by the host script — the engine and clients never interpret them.

## World table

| Lua | Return | Description |
|-----|--------|-------------|
| `world.count()` | number | Total entities in the world |
| `world.size()` | `{width,height,tile_size}` or nil | Map dimensions (nil if no map loaded) |
| `world.players()` | `{entity,...}` | Connected player facades, reported by the host server (`GameRuntime.setPlayers`) — the engine itself has no notion of "player" |
| `world.spawn(name)` | entity | Create entity with `Name(name)` + `Transform(0,0,0)` |
| `world.destroy(id)` | — | Destroy by numeric id **or** name string |
| `world.get(id)` | entity or nil | Lookup by numeric id or by `Name` component |
| `world.find(name)` | entity or nil | First entity whose `Name` matches exactly |
| `world.all()` | `{entity,...}` | Lua table (ipairs-iterable) of all entities |
| `world.get_near(x,y,r)` | `{entity,...}` | Entities within radius `r` (2D Euclidean) of `(x,y)` |
| `world.tile(x,y[,layer])` | number | Tile id at position; `-1` if out of bounds/no map |
| `world.set_tile(x,y,id[,layer])` | boolean | Set tile id; `false` if invalid bounds |

`layer` defaults to `0` (ground) when omitted.

## Entity facade

Returned by `world.spawn`, `world.get`, `world.find`, and provided as `entity` / `self` during a
bound script.

| Lua | Return | Description |
|-----|--------|-------------|
| `entity.id()` | number | Numeric id |
| `entity.name()` | string | Current `Name` component value |
| `entity.set_name(s)` | — | Replace `Name` |
| `entity.exists()` | boolean | Whether the entity still lives in the world |
| `entity.position()` | `{x,y,z}` | Snapshot table of `WorldPosition` |
| `entity.set_position(x,y,z)` | — | Overwrites `Transform` (rotation → 0) |
| `entity.set_scale(n)` | — | Overwrites visual scale |
| `entity.set_trigger(r)` | — | Attaches / updates a `Trigger` component with radius |
| `entity.trigger_radius()` | number or nil | Current trigger radius (nil if no `Trigger` component) |
| `entity.on_tick(fn)` | — | Per-entity tick handler; arg = entity facade |
| `entity.on_enter(fn)` | — | Arg = actor entity facade |
| `entity.on_exit(fn)` | — | Arg = actor entity facade |
| `entity.on_interact(fn)` | — | Arg = interacting player entity facade |

## Trigger / event model

Two complementary models are supported:

1. **Component-based auto events** — attach `Trigger` via `entity.set_trigger(radius)`.
   `TriggerSystem` runs each `GameWorld.step()`, diffing occupant sets and emitting:
   - `TriggerEnterEvent(actor, trigger)` → dispatched to trigger's `on_enter` handlers
   - `TriggerExitEvent(actor, trigger)` → dispatched to trigger's `on_exit` handlers

2. **Tick-based manual checks** — `engine.on_tick(fn)` and `entity.on_tick(fn)` fire every tick.
   Scripts can inspect positions, run Lua distance checks, update state — full manual control.

**Interact flow:**
When a player sends `Input.INTERACT`, the server checks `GameWorld.interactTarget(position, 2.0)`
for the nearest `Trigger` entity within range, then emits `InteractRequestedEvent(player, target)`
which dispatches to the target's `on_interact` handlers.

## Lifecycle

```
GameRuntime.loadMap(path)
  ├─ for each MapEntity with non-empty script:
  │    path is resolved relative to the map file (or absolute)
  │    api.executeOwned(chunk, chunkName, spawnedEntityId)
  │      sets global entity/self = facade of the owning entity
  │      executes the Lua chunk (registers handlers)
  │      restores previous entity/self globals
  └─ bindMap(RMap) — makes world.tile/set_tile/size available

GameRuntime.tick()
  ├─ world.step()          ← triggers TriggerSystem.step()
  ├─ api.setTick(tick)     ← updates engine.tick number
  └─ api.dispatchTick()    ← calls global + per-entity on_tick handlers
```

Scripts execute once at load time and register persistent handlers. `dispatchTick()` is called
once per server tick and invokes all registered tick handlers. Trigger/interact events are
dispatched synchronously via the `EventBus`.

## Server

`dedicated-server` invokes `rt.loadMap()` and runs `rt.tick()` at the configured world tick rate
(default 20 Hz). Script errors in `loadMap` are caught and logged (the server continues). Errors
inside tick handlers are caught per-handler and printed to stderr — one failing handler does not
kill the server.

## Desktop / Android

The design is host-authoritative: **scripts run only on the server** (wherever the `ServerHost`
owns the world), never inside a guest view.

- **Desktop client** — the built-in local server runs the same `ServerHost` code as the dedicated
  CLI (`DesktopLocalServer` is a thin wrapper); Lua executes there and a network-backed `UiSink`
  routes `engine.notify` / `engine.dialog` / `engine.layout` to the client socket as `UiLayout`
  packets. The guest view renders toasts, choice dialogs and HUD widgets from those packets.
- **Android** — the embedded local server (Local server button) uses the same `ServerHost`
  (`LocalServerBackend`): Lua runs inside the app and the guest `GameView` renders the same
  `UiLayout` stream. When connecting to a **remote** server, scripts run server-side as usual and
  the Android client stays a pure renderer.