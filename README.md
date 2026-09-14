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

The repository intentionally includes the Gradle wrapper bootstrap script. If Gradle is installed:

    gradle build

The server can be run with:

    gradle :dedicated-server:run --args="--map examples/village.rmap --port 27800"

The core/server modules do not depend on LWJGL, AWT, Android or native libraries.
