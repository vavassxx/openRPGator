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
- functional map editor for creating/loading/saving maps
- functional Lua script editor
- Android client module using OpenGL ES and the shared runtime
- unit-test sources and smoke tests

Not yet production-complete:
- asset pipeline, texture atlas, animation system
- prediction/interpolation and robust reconnect/authentication
- advanced editor tooling (painting/entity inspector/undo/redo)
- complete Android touch UI and Android server foreground-service wrapper
- packaging/signing for each desktop target
- sandbox policy (full LuaJ access by design; host responsibility per project policy)
- persistence/database layer and account/auth system
- client-side dialogs and server→client notifications (network packets + UI rendering) — **network side done**: `engine.notify`/`engine.dialog` via `UiSink` (dedicated-server + desktop local server install it), `Notify`/`Dialog`/`DialogResponse` packets; desktop client toast/choice UI rendering still pending

The architecture deliberately keeps these as subsequent layers rather than faking them with placeholder implementations.

## Android Map Editor 0.4.0
- Android-native `.rmap` editor replaces the old Swing-only story for mobile workflows.
- Open/create/save `.rmap` through Android Storage Access Framework.
- Paint tiles, erase, collision editing and entity placement.
- Pan the map by dragging; tile palette 0..9.
- `RMapIO` now supports `InputStream`/`OutputStream`, so Android does not need filesystem paths.
- The Android app does not depend on LWJGL or the desktop renderer.


## CI packaging
- GitHub Actions workflow builds Linux x86_64 and ARM64 distributions for the dedicated server, Swing map editor and desktop client.
- Desktop client distributions resolve architecture-specific LWJGL native artifacts.
- Android map editor is assembled as a universal APK.
- Android CI compiles the shared JVM libraries at Java 17 bytecode level while the full desktop/server build remains Java 21.

## Android client input system

The Android client now uses a semantic, user-editable control layer. Gameplay receives logical actions (`MOVE_*`, `PRIMARY`, `SECONDARY`, `INTERACT`, `INVENTORY`) rather than hard-coded screen coordinates. Control widgets can be moved, resized, remapped to another action, toggled between button and joystick presentation, added, deleted, and persisted locally. Network `Input` packets carry both movement and an action bitmask so the server protocol is no longer tied to a particular touch layout.

## Android local server
The Android client now contains a Java-17-compatible embedded TCP backend. Use **Local server** to start it on the selected port, then press **Connect** with host `127.0.0.1`. The backend supports Hello/Welcome/Input/Snapshot and multiple local clients, and is stopped with the same button or when the activity closes.

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

## Пометка основному кодеру: модель .pak для клиентских ассетов

В будущем клиентский рендер (диалоги, UI-элементы, текстуры) должен перейти на модель **`.pak`-ассетов**:

- На сервере хранится `.pak`-архив с текстурами, шрифтами, UI-layout'ами, анимациями и прочими визуальными ресурсами.
- При подключении клиента сервер стримует `.pak` клиенту.
- Клиент поднимает ресурсы из `.pak` и передаёт их в распоряжение серверных скриптов — скрипт решает, *что* показать, а рендер дёргает ресурсы из уже загруженного `.pak`.
- Это отвязывает серверные скрипты от хардкоженных текстур/глифов на клиенте и позволяет обновлять визуал без пересборки клиента.

Для первой итерации (текущей) на десктопе используется встроенный bitmap-шрифт 5×7 в GL — это минимально sufficient до появления `.pak`-пайплайна.
