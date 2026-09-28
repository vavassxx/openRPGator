-- Grindlands healer.
local G = _G.GRINDLANDS
if not G then error("Grindlands manager must be loaded before NPC scripts") end

entity.set_trigger(2.0)

entity.on_interact(function(player)
    local p = G.players[player.id()]
    if not p or not p.alive then return end

    local weapon = G.weapons[p.weapon] or G.weapons.fists
    engine.dialog(player,
        string.format("Твои статы — уровень %d, HP %d/%d, XP %d, золото %d, оружие: %s.",
            p.level, math.floor(p.hp), p.maxHp, p.xp, p.gold, weapon.name),
        {"Вылечить полностью", "Расскажи о зонах", "Спасибо"},
        function(choice)
            local current = G.players[player.id()]
            if not current or not current.alive then return end

            if choice == 1 then
                current.hp = current.maxHp
                engine.notify("Целитель: полное исцеление!")
            elseif choice == 2 then
                engine.dialog(player,
                    "Чем дальше от центра, тем опаснее. " ..
                    "Зона 1: гоблины, скелеты, зомби. " ..
                    "Зона 2: импы, орки, черти. " ..
                    "Зона 3: огры, воголы, болотники. " ..
                    "Зона 4: некроманты, большие зомби, демоны.",
                    {"Понятно"},
                    function() engine.notify("Целитель: удачи в приключениях!") end)
            else
                engine.notify("Целитель: береги себя!")
            end
        end)
end)
