# openRPGator — Asteria regions integration, Step 7

Small integration fix for `asteria-regions-mainline`.

`ServerHost` validates the source region of Lua transition requests (Step 6), but
`LuaApi.engine.transition()` was still passing `null` as that source. This made all
Lua-driven transitions fail the new validation.

This patch gives each Lua API instance its owning region id, initializes it from
`GameRuntime`, and passes it to `RegionTransitionSink`.

No player-state snapshot/copy or new lifecycle architecture is introduced. Host/Lua
remains responsible for persistent player state.

Apply from repository root:

    sh /path/to/apply-step7.sh

Then build/test normally and commit:

    git add .
    git commit -m "fix: preserve region source for Lua transitions"
    git push
