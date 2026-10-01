# openRPGator — Asteria regions mainline, step 1

This is an **overlay archive**, not a replacement checkout. Unpack it over a clean checkout of
`master`; it contains only the first region-engine slice and its tests/docs.

Implemented:

- `MapPortal` as a generic engine map primitive.
- `.rmap` v3 with portal serialization.
- v1/v2 `.rmap` compatibility.
- `RegionCatalog` with startup validation and normalized region IDs.
- `WorldRegistry.adopt(...)` plus monotonic imported-ID allocation.
- Regression tests for v3 portal round-trip and v2 compatibility.
- `docs/regions.md` describing the intended contract.

Not yet included deliberately:

- multi-`GameRuntime` server lifecycle;
- player detach/attach orchestration;
- per-region Lua runtime binding;
- automatic portal transition in `ServerHost`;
- editor portal CRUD;
- Asteria game content.

Those belong in the next slice, after this serialization/catalog contract is accepted. This keeps
the first archive small enough to review and avoids importing Asteria-specific assumptions into the
engine.
