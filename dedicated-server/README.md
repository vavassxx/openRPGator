# Dedicated server

The server is intentionally headless and depends only on the JVM-facing engine modules.

Build/run on a desktop Linux machine:

    gradle :dedicated-server:jar
    java -jar dedicated-server/build/libs/dedicated-server-0.3.2.3.jar --map examples/village.rmap --port 27800

Termux/Android ARM64:

    pkg install openjdk-21
    java -jar dedicated-server-0.3.2.3.jar --map examples/village.rmap --port 27800

No root, systemd, Docker or GPU is required.
