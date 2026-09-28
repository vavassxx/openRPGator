-- Grindlands resource entity binding.
local G = _G.GRINDLANDS
if not G then error("Grindlands manager must be loaded before resource scripts") end

local id = entity.id()
local name = entity.name()
local resourceType = string.match(name, "^res_%d+_(.+)$")
if not G.resourceNames[resourceType] then error("Unknown resource type: " .. tostring(resourceType)) end

local state = { type=resourceType, depleted=false, respawnAt=0, originalScale=.75 }
G.resources[id] = state
entity.set_trigger(1.5)

entity.on_interact(function(player)
    if state.depleted then return end
    local p = G.players[player.id()]
    if not p or not p.alive then return end

    p.resources[resourceType] = (p.resources[resourceType] or 0) + 1
    state.depleted = true
    state.respawnAt = engine.tick() +
        G.config.resource.minRespawn * G.tickRate +
        math.random(0, (G.config.resource.maxRespawn-G.config.resource.minRespawn) * G.tickRate)
    entity.set_scale(.01)

    engine.notify("Собрано: " .. G.resourceNames[resourceType] ..
        " (всего: " .. p.resources[resourceType] .. ")")
end)
