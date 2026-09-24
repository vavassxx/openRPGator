# Dedicated server

The server is intentionally headless and depends only on the JVM-facing engine modules.

Build/run on a desktop Linux machine:

    gradle :dedicated-server:jar
    java -jar dedicated-server/build/libs/dedicated-server-0.4.0.jar --data-dir examples --port 27800 --tick-rate 20

Termux/Android ARM64:

    pkg install openjdk-21
    java -jar dedicated-server-0.4.0.jar --data-dir <dir> --port 27800 --tick-rate 20

Server content lives in `<data-dir>/host`: the single `*.rmap` is auto-selected, every `*.pak` is
streamed to clients, Lua scripts next to the map are loaded. No `--map`/`--pak` flags anymore.

Optional flags: `--tick-rate HZ` sets the world tick rate (default 20, clamped to 1..240);
`--help` lists all options.

No root, systemd, Docker or GPU is required.
