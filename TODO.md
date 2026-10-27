# Release notes and remaining work

This file tracks work that is still relevant after the Asteria 0.5.0 release-preparation pass. Release verification is tracked in [the 0.5.0 checklist](docs/0.5.0-release-checklist.md); do not treat this list as a substitute for running CI.

## Release-blocking verification

- [ ] Run the full Gradle test/build workflow in CI with JDK 21 and Android SDK.
- [ ] Run the two-region demo in `examples/host/regions` and verify both portal directions on desktop and Android.
- [ ] Verify Android connection and map rendering on a real device; CI compilation is not a runtime test.
- [ ] Review generated APK/JAR artifacts and release notes before tagging `0.5.0`.

## Follow-up work (not a 0.5.0 promise)

- [ ] Improve editor workflows for authoring collision layers and region portals.
- [ ] Add integration tests for concurrent portal requests, reconnects during transitions, and malformed maps.
- [ ] Expand persistent player-state storage and define host-controlled region enter/leave hooks.
- [ ] Add animation/atlas support, pathfinding, and complete built-in inventory/combat systems.
- [ ] Add robust reconnect/authentication and account/database persistence.
