-- Grindlands mob entity binding.
local G = _G.GRINDLANDS
if not G then
    error("Grindlands manager must be loaded before mob scripts")
end

local id = entity.id()
local name = entity.name()
local mobType = string.match(name, "^mob_%d+_(.+)$")
local def = G.mobTypes[mobType]
if not def then
    error("Unknown mob type: " .. tostring(mobType))
end

local pos = entity.position()
local state = {
    type = mobType,
    hp = def.hp,
    maxHp = def.hp,
    dmg = def.dmg,
    xp = def.xp,
    gold = def.gold,
    aggro = def.aggro,
    speed = def.speed,
    atkcd = def.atkcd,
    homeX = pos.x,
    homeY = pos.y,
    target = nil,
    attackAt = 0,
    dead = false,
    respawnAt = 0,
}
G.mobs[id] = state
entity.set_trigger(2.0)

entity.on_interact(function(player)
    -- INTERACT remains a compatibility fallback for desktop/mobile interaction.
    -- Primary combat is delivered through engine.on_action("primary").
    local mobId = entity.id()
    G.attackMob(player, mobId)
end)
