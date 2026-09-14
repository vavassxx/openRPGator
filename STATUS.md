# Implementation status — 0.2.0

This repository is a real, buildable foundation/vertical slice, not a collection of placeholder classes.

Implemented:
- multi-module Java 21 build layout
- engine ECS/world/components
- deterministic 2.5D coordinates
- box/circle/polygon collision and slide-on-axis movement
- binary RMAP format with round-trip support
- RScript lexer/parser/interpreter with variables, arithmetic, comparisons, conditions, returns and host functions
- TCP length-framed multiplayer protocol
- headless authoritative dedicated server
- server JAR entry point suitable for JVM on Linux/Termux/Android ARM64
- desktop OpenGL renderer implementation
- desktop client executable
- functional map editor for creating/loading/saving maps
- functional script editor for syntax validation
- Android client module using OpenGL ES and the shared runtime
- unit-test sources and smoke tests

Not yet production-complete:
- asset pipeline, texture atlas, animation system
- prediction/interpolation and robust reconnect/authentication
- advanced editor tooling (painting/entity inspector/undo/redo)
- complete Android touch UI and Android server foreground-service wrapper
- packaging/signing for each desktop target
- script bytecode VM/JIT and hardened sandbox
- persistence/database layer and account/auth system

The architecture deliberately keeps these as subsequent layers rather than faking them with placeholder implementations.

## Android Map Editor 0.2.0
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
