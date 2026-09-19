# Build

Requires JDK 21 and Gradle 8.10+ (Android builds additionally require the Android SDK and Android Gradle Plugin prerequisites).

Desktop/server:

    gradle build

Core smoke compilation without Gradle is possible with JDK 21 by compiling the modules in dependency order.

Dedicated server:

    gradle :dedicated-server:jar
    java -jar dedicated-server/build/libs/dedicated-server-0.4.0.jar --data-dir examples --port 27800

Termux:

    pkg install openjdk-21
    java -jar dedicated-server-0.4.0.jar --data-dir <dir> --port 27800

Server content comes from `<data-dir>/host` (single `*.rmap` auto-selected, `*.pak` streamed, `.lua`
next to the map loaded).

The server has no graphics/native dependency. Android does not execute an x86 Linux binary: the same server JAR runs on an ARM64 JVM inside Termux.
