# Implementation status — 0.4.0

This repository is a real, buildable foundation/vertical slice, not a collection of placeholder classes.

Implemented:
- multi-module Java 21 build layout
- engine ECS/world/components
- deterministic 2.5D coordinates
- box/circle/polygon collision and slide-on-axis movement
- binary RMAP format with round-trip support
- LuaJ runtime with public engine scripting API
- TCP length-framed multiplayer protocol
- headless authoritative dedicated server
- server JAR entry point suitable for JVM on Linux/Termux/Android ARM64
- desktop OpenGL renderer implementation
- desktop client executable
- full map editor: tile/collision painting, entity placement with `.pak` sprite previews, entity
  inspector (id/prefab/position/script), embedded Lua script editor, undo/redo
- standalone Lua script editor (open/save + syntax check)
- Swing admin console for the dedicated server (`server-admin`)
- Android client module using OpenGL ES and the shared runtime
- unit-test sources and smoke tests

Not yet production-complete:
- full texture atlas and animation system (single-frame `.pak` sprites/tiles work end-to-end)
- prediction/interpolation and robust reconnect/authentication
- complete Android touch UI and Android server foreground-service wrapper
- packaging/signing for each desktop target
- sandbox policy (full LuaJ access by design; host responsibility per project policy)
- persistence/database layer and account/auth system

The architecture deliberately keeps these as subsequent layers rather than faking them with placeholder implementations.

## Android Map Editor 0.4.0
- Android-native `.rmap` editor replaces the old Swing-only story for mobile workflows.
- Open/create/save `.rmap` through Storage Access Framework or the app host folder (`data/host`).
- Paint tiles, erase, collision editing and entity placement.
- Pan the map by dragging; tile/sprites palette rebuilt per mode. Pak atlas loads from `data/host`
  (shared layout with desktop) plus packs cached from remote servers (`data/pakcache`); tiles and
  entity sprites render as real textures, entities are placed with an explicit prefab binding.
  Long-press an entity to edit its id/prefab/script or delete it.
- `RMapIO` now supports `InputStream`/`OutputStream`, so Android does not need filesystem paths.
- The Android app does not depend on LWJGL or the desktop renderer.

## Server admin console (`server-admin`)
- Swing window to run the dedicated server in-process: data dir browse/refresh, auto-selected host
  map and pak list (read-only view of `data/host`), port, Start/Stop, live log panel, map info.
- Options are persisted to `~/.openrpgator/server.properties`; the started server uses the same
  `ServerConfig`/`ServerHost` resolution as the CLI.


## CI packaging
- GitHub Actions workflow builds Linux x86_64 and ARM64 distributions for the dedicated server, server-admin, Swing map editor and desktop client.
- Desktop client distributions resolve architecture-specific LWJGL native artifacts.
- Android map editor is assembled as a universal APK.
- Android CI compiles the shared JVM libraries at Java 17 bytecode level while the full desktop/server build remains Java 21.

## Android client input system

The Android client now uses a semantic, user-editable control layer. Gameplay receives logical actions (`MOVE_*`, `PRIMARY`, `SECONDARY`, `INTERACT`, `INVENTORY`) rather than hard-coded screen coordinates. Control widgets can be moved, resized, remapped to another action, toggled between button and joystick presentation, added, deleted, and persisted locally. Network `Input` packets carry both movement and an action bitmask so the server protocol is no longer tied to a particular touch layout.

## Android local server
The Android client now runs the **same authoritative server code as the PC build**: the
`dedicated-server` `ServerHost`/`ServerConfig` are embedded via a thin `LocalServerBackend` wrapper
(single source of truth — no re-implementation on Android). Use **Local server** to start it on the
selected port, then press **Connect** with host `127.0.0.1`. Server content is loaded from the app
`data/host` folder exactly like on desktop: the single `*.rmap` is auto-selected, `*.pak` packs are
streamed to clients during the handshake and Lua scripts next to the map are executed. The backend
supports Hello/Welcome/Input/Snapshot, dialogs/toasts via `UiLayout` and multiple local clients, and
is stopped with the same button or when the activity closes.

The **world tick rate is host-configurable everywhere** (`ServerHost.Config.tickHz`, default 20 Hz,
clamped 1..240, period = 1000/Hz): `--tick-rate` on the dedicated CLI, a "Tick rate" spinner in the
server admin console (persisted), a "Local server tick rate" field in the desktop client settings
(also `--local-tick-rate`, persisted) and the "Default connection → tick rate" field in Android
settings (`server_tick_rate` in prefs). Slower rates stretch timing-sensitive scripts, faster rates
tighten them — same engine, one knob per host.

- Android settings now allow selecting a persistent application data folder through Storage Access Framework.

## Lua scripting API 0.4.0

The scripting API has been substantially expanded (see `SCRIPTING.md` for the full reference):

- **Tick / event dispatch:** global `engine.on_tick(fn)` and per-entity `entity.on_tick(fn)`, `on_enter`, `on_exit`, `on_interact` — all dispatched every tick or on trigger/interact events.
- **World observation:** `world.all()`, `world.find(name)`, `world.get_near(x,y,r)`, `world.size()`.
- **Tile access:** `world.tile(x,y[,layer])` and `world.set_tile(x,y,id[,layer])` — read/write map tiles from Lua.
- **Entity `self` binding:** scripts attached to a map entity execute with `entity` / `self` global bound to their owning entity facade. `world.get(id)` now also accepts a name string (fallback lookup by `Name` component).
- **Trigger system (engine-world):** `Trigger` component with automatic `TriggerEnterEvent` / `TriggerExitEvent` emission via `TriggerSystem`. Interact via `Input.INTERACT` emits `InteractRequestedEvent` for the nearest trigger entity.
- **Relative script paths:** map-relative paths are resolved against the `.rmap` file's parent directory (not CWD).
- **Push UI (`engine.notify` / `engine.dialog`):** Lua can broadcast toasts or open choice dialogs for a specific player via the `UiSink` interface. The dedicated server and desktop local server install a network-backed sink (`Notify`, `Dialog`, `DialogResponse` packets); `dialog` callbacks run on the Lua tick thread after the client answers.
- **Resilient error handling:** per-handler LuaError is caught and logged per-handler during tick; per-script errors during `loadMap` are logged without crashing the server.

No sandbox is applied. `JsePlatform.standardGlobals()` is the default. Host operators own their scripts.

## Scripted test host

`examples/host/town.rmap` + Lua scripts: a password-checking guard (nested dialog), an elder with a
two-branch quest, a merchant with a one-shot sale, a destroyable chest, a patrolling rat, teleport
arches on `on_enter`, a well interact and a global-timer `sky` conductor. Verified offline by driving
`GameRuntime` directly (load → tick → simulate `Input.INTERACT` → answer `DialogResponse`). See
`examples/host/README.md` for the runbook and the remaining work (network map push, tile collision,
sprite/.pak pipeline, inventory/combat, persistence, smarter AI, Android guest check).

## Desktop client settings screen

- `Main menu → Settings` configures player name, the built-in local server's tick rate and the
  server address; the local server runs automatically from the shared `data/host` folder (no
  map/resource pickers anymore).
- Text fields support Tab to switch focus, Backspace, Enter/Esc; **Apply** persists to
  `~/.openrpgator/client.properties` (`name`, `localTick`, `connect`).
- **The desktop local server is the same `ServerHost` code as the dedicated CLI and the Android
  client** (`DesktopLocalServer` is a thin wrapper) — single source of truth. It now runs the
  configurable world tick (default 20 Hz), which previously was missing: patrols and
  periodic conductors (the demo rat, `sky.lua` toasts) now move and fire against the local server
  exactly like on the dedicated one.
- The renderer gained one-shot key edges (`keyPressed`) and a GLFW char callback for text input.
- Movement input is now **screen-relative**: WASD/arrows are converted through the inverse isometric projection, so `D`/`Right` move the entity right on screen, `W`/`Up` up, etc. (previously raw world-axis deltas made the four keys move diagonally relative to the camera).
- `resetView()` pins the in-game HUD/toasts/dialogs to screen space after the camera transform.
- Android has its own settings screen (default host/port, controls, storage); its `LocalServerBackend`
  loads from `data/host` exactly like the desktop flow.

## Ассет-пайплайн `.pak` (реализовано)

Вместо хардкоженных текстур на клиенте ассеты распространяются как **`.pak`-паки**:

- **Формат (`pak`)**: магический `PK01`, version, index (name → w/h/len/offset), затем сырые RGBA-растры
  (без сжатия PNG — клиенту не нужен декодер изображений). Ключи пространства имён: `tile/…`, `sprite/…`, `ui/…`.
- **Упаковщик CLI (`PakTool`)**: `pack --root assets-src --out assets/basic.pak [--resize WxH] [--resize <ns>=WxH]`
  пакерует `tile/*.png`, `sprite/*.png`, `ui/*.png`; `--resize` подгоняет большие спрайты (Kenney 256×512 → 32×64),
  per-namespace вариант (`--resize tile=32x16`) оставляет тайлы в их собственных пропорциях.
- **Стриминг в хендшейке**: после `Hello` сервер шлёт `PakList(8)` (имена и размеры), затем поток
  `PakChunk(9)` (по 64 KiB с offset), и только потом `Welcome(2)`. Старый сервер (только `Welcome`)
  по-прежнему поддерживается.
- **Клиентский кэш**: паки скачиваются в `data/pakcache` (по имени); закэшированные пропускаются без
  записи на диск, но всё равно читаются с сокета до `Welcome`. Десктоп показывает экран **LOADING**
  с прогресс-баром и кнопкой отмены (Esc); Android пишет чанки в файл кэша и перезагружает атлас
  (`GameView.reloadAssets`) после каждого принятого пака.
- **Рендер**: `PakAssets` собирает `tile/*` и `sprite/*` (отсортированные) в нумерантные массивы;
  `Snapshot.EntityState.resource` маппится в `sprite/<n>`, `resource == -1` — в `sprite/player`.
  Тайлы рисуются текстурированным ромбом, спрайты — текстурированными билбордами; при отсутствии
  текстуры — цветной fallback.
- **Индексы спрайтов**: `PakAssets.spriteKeyIndex(paks)` строит индексы по отсортированному объединению
  ключей `sprite/*` из host-паков — точное совпадение с массивом спрайтов клиента. Серверы
  (dedicated, desktop/Android local server) используют его; при отсутствии паков — fallback
  `Sprites.byPrefab(map)` (отсортированные prefab сущностей) с цветными маркерами на клиенте.
  Редактор привязывает текстуру, проставляя prefab сущности равным ключу `sprite/*` из пака.
- **Android-клиент**: соединяется как десктоп — принимает `PakList`/`PakChunk` до `Welcome`, пишет их в
  `data/pakcache`, перезагружает атлас и рисует спрайты/тайлы из локальных паков
  (`data/host` ∪ `data/pakcache`) по `Snapshot.EntityState.resource()`.
- **UI по макету**: Lua push-UI (`engine.notify` / `engine.dialog`) уходит по сети пакетом `UiLayout(10)`
  — JSON-макет (ссылки на строки + сами строки); клиенты (десктоп и Android) рисуют по макету
  (toast / окно диалога с вариантами). Старые `Notify(5)`/`Dialog(6)` читаются для совместимости и
  конвертируются в `UiLayout`.
- **Пример**: `assets/basic.pak` — CC0-паки Kenney (изо-dungeon) + демо-тайлы `tile/0..11`;
  `examples/host/` — готовая host-папка (карта + луа + паки); сервер запускается с `--data-dir examples`.
