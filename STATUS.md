# Implementation status — 0.1.0

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
