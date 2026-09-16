# Lua Scripting API — v0.4.0

Scripts are attached to entities in `.rmap` files (the `script` field) and executed by `GameRuntime.loadMap()`. Each script runs with `entity` / `self` bound to its owning entity as stable facades — never raw ECS internals. Scripts can register persistent handlers which the engine invokes each tick or on trigger/interact events.

No sandbox is applied — `JsePlatform.standardGlobals()` is used by default. Host operators are responsible for the scripts they run.

---

## Engine table

| Lua                  | Return   | Description                          |
|----------------------|----------|--------------------------------------|
| `engine.version`     | string   | API version (`"0.4.0"`)              |
| `engine.tick`        | number   | Current world tick (updated per step)|
| `engine.log(msg)`    | —        | Prints `[Lua] msg` to stdout         |
| `engine.on_tick(fn)` | —        | Register a global tick handler (arg = tick number) |
| `engine.notify(msg)` | —        | Broadcast a toast notification to all connected players (`UiSink.broadcastNotify`) |
| `engine.dialog(player, text, {choice,...}, fn(idx))` | — | Open a modal choice dialog for one player; `fn` fires with the 1-based choice on answer |

Dialogs require the embedding server to install a `UiSink`; without one both calls are no-ops. `player` is an entity facade (e.g. `world.get('id')`) or a raw entity id.

---

## World table

| Lua                        | Return                | Description                                           |
|----------------------------|-----------------------|-------------------------------------------------------|
| `world.count()`            | number                | Total entities in the world                           |
| `world.size()`             | `{width,height,tile_size}` or nil | Map dimensions (nil if no map loaded)    |
| `world.spawn(name)`        | entity                | Create entity with `Name(name)` + `Transform(0,0,0)`  |
| `world.destroy(id)`        | —                     | Destroy by numeric id **or** name string               |
| `world.get(id)`            | entity or nil         | Lookup by numeric id or by `Name` component            |
| `world.find(name)`         | entity or nil         | First entity whose `Name` matches exactly              |
| `world.all()`              | `{entity,...}`        | Lua table (ipairs-iterable) of all entities            |
| `world.get_near(x,y,r)`   | `{entity,...}`        | Entities within radius `r` (2D Euclidean) of `(x,y)`  |
| `world.tile(x,y[,layer])` | number                | Tile id at position; `-1` if out of bounds/no map      |
| `world.set_tile(x,y,id[,layer])` | boolean      | Set tile id; `false` if invalid bounds                 |

`layer` defaults to `0` (ground) when omitted.

---

## Entity facade

Returned by `world.spawn`, `world.get`, `world.find`, and provided as `entity` / `self` during a bound script.

| Lua                    | Return          | Description                                           |
|------------------------|-----------------|-------------------------------------------------------|
| `entity.id()`          | number          | Numeric id                                            |
| `entity.name()`        | string          | Current `Name` component value                        |
| `entity.set_name(s)`   | —               | Replace `Name`                                        |
| `entity.exists()`      | boolean         | Whether the entity still lives in the world            |
| `entity.position()`    | `{x,y,z}`      | Snapshot table of `WorldPosition`                      |
| `entity.set_position(x,y,z)` | —          | Overwrites `Transform` (rotation → 0)                 |
| `entity.set_trigger(r)` | —              | Attaches / updates a `Trigger` component with radius  |
| `entity.trigger_radius()` | number or nil  | Current trigger radius (nil if no `Trigger` component)|
| `entity.on_tick(fn)`   | —               | Per-entity tick handler; arg = entity facade           |
| `entity.on_enter(fn)`  | —               | Arg = actor entity facade                             |
| `entity.on_exit(fn)`   | —               | Arg = actor entity facade                             |
| `entity.on_interact(fn)`| —              | Arg = interacting player entity facade                |

---

## Trigger / event model

Two complementary models are supported:

1. **Component-based auto events** — attach `Trigger` via `entity.set_trigger(radius)`.  
   `TriggerSystem` runs each `GameWorld.step()`, diffing occupant sets and emitting:
   - `TriggerEnterEvent(actor, trigger)` → dispatched to trigger's `on_enter` handlers
   - `TriggerExitEvent(actor, trigger)`  → dispatched to trigger's `on_exit` handlers

2. **Tick-based manual checks** — `engine.on_tick(fn)` and `entity.on_tick(fn)` fire every tick.  
   Scripts can inspect positions, run Lua distance checks, update state — full manual control.

**Interact flow:**  
When a player sends `Input.INTERACT`, the server checks `GameWorld.interactTarget(position, 2.0)` for the nearest `Trigger` entity within range, then emits `InteractRequestedEvent(player, target)` which dispatches to the target's `on_interact` handlers.

---

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

Scripts execute once at load time and register persistent handlers. `dispatchTick()` is called once per server tick and invokes all registered tick handlers. Trigger/interact events are dispatched synchronously via the `EventBus`.

---

## Server

`dedicated-server` invokes `rt.loadMap()` and runs `rt.tick()` at 20 Hz. Script errors in `loadMap` are caught and logged (the server continues). Errors inside tick handlers are caught per-handler and printed to stderr — one failing handler does not kill the server.

## Desktop / Android

Scripts are loaded client-side only when `GameRuntime.loadMap()` is called by the desktop client (`desktop-client` installs a `UiSink` so `engine.notify`/`engine.dialog` route to the local socket). Android's `LocalServerBackend` does **not** currently use `GameRuntime` and therefore does not run Lua. Client-side UI for dialogs and notifications is planned for a future iteration.
