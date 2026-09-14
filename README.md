# openRPGator

Java 21, server-authoritative, 2.5D/isometric RPG engine targeting Linux/Windows/macOS and Android/Termux.

## Current vertical slice

- ECS-like world model with typed components
- 2.5D coordinates and deterministic tick loop
- lightweight collision primitives
- binary `.rmap` map format
- Lua-like RScript lexer/parser/interpreter
- script-driven entity events
- TCP server with length-framed JSON-free binary protocol
- headless server runnable as a plain JAR on JVM/Termux
- desktop map editor and script editor
- desktop software renderer/client with no native dependency in the core

## Build

GitHub Actions builds the Linux x86_64/arm64 distributions and the universal Android map-editor APK. Locally, any Gradle 8.7.x installation can be used:

    gradle build

The server can be run with:

    gradle :dedicated-server:run --args="--map examples/village.rmap --port 27800"

The core/server modules do not depend on LWJGL, AWT, Android or native libraries.


## Automated builds

Every push runs `.github/workflows/build.yml`. It produces:

- `openRPGator-linux-x86_64.tar.gz` — server, Swing map editor and desktop client with x86_64 LWJGL natives.
- `openRPGator-linux-arm64.tar.gz` — server, Swing map editor and desktop client with ARM64 LWJGL natives.
- `openRPGator-map-editor-universal.apk` — the Android-native `.rmap` map editor as a universal APK.

The Android application is currently the map editor; there is not yet a separate Android client or Android server application. The JVM server itself is headless and can run directly under Termux on ARM64.
