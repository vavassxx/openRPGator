-- Grindlands coin entity binding.
local G = _G.GRINDLANDS
if not G then error("Grindlands manager must be loaded before coin scripts") end

local id = entity.id()
local state = { collected=false, respawnAt=0 }
G.coins[id] = state
entity.set_trigger(1.0)

entity.on_interact(function(player)
    if state.collected then return end
    local p = G.players[player.id()]
    if not p or not p.alive then return end

    state.collected = true
    state.respawnAt = engine.tick() +
        G.config.coin.minRespawn * G.tickRate +
        math.random(0, (G.config.coin.maxRespawn-G.config.coin.minRespawn) * G.tickRate)
    entity.set_scale(.01)

    local amount = math.random(1, 10)
    p.gold = p.gold + amount
    engine.notify("+" .. amount .. " золота")
end)
