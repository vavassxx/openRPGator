-- Grindlands merchant: sells starter weapons and buys gathered resources.
local G = _G.GRINDLANDS
if not G then error("Grindlands manager must be loaded before NPC scripts") end

entity.set_trigger(2.0)

local function weaponList()
    return {
        "Нож — 15 золота",
        "Короткий меч — 35 золота",
        "Меч — 60 золота",
        "Тесак — 80 золота",
        "Мачете — 95 золота",
        "Отмена",
    }
end

local weaponChoices = { "knife", "rsword", "sword", "cleaver", "machete" }

entity.on_interact(function(player)
    local p = G.players[player.id()]
    if not p or not p.alive then return end

    engine.dialog(player,
        "Торговец. У тебя " .. p.gold .. " золота. " ..
        "Ресурсы можно продать мне: руда 5з, трава 3з, дерево 4з. " ..
        "Сначала купи Нож — он уже позволит нормально охотиться на мобов.",
        {"Продать все ресурсы", "Купить оружие", "Купить зелье здоровья (15з)", "Спасибо, нет"},
        function(choice)
            local current = G.players[player.id()]
            if not current or not current.alive then return end

            if choice == 1 then
                local earned = (current.resources.ore or 0) * G.resourcePrices.ore +
                    (current.resources.herb or 0) * G.resourcePrices.herb +
                    (current.resources.wood or 0) * G.resourcePrices.wood
                current.resources.ore, current.resources.herb, current.resources.wood = 0, 0, 0
                current.gold = current.gold + earned
                engine.notify("Торговец: получено " .. earned .. " золота.")
            elseif choice == 2 then
                engine.dialog(player, "Выбери оружие. Золото списывается только после проверки цены.", weaponList(), function(wchoice)
                    local id = weaponChoices[wchoice]
                    if id then G.buyWeapon(player, current, id) end
                end)
            elseif choice == 3 then
                if current.gold >= G.potionPrices.hpotion then
                    current.gold = current.gold - G.potionPrices.hpotion
                    G.addItem(current, "hpotion")
                    engine.notify("Торговец: держи зелье здоровья!")
                else
                    engine.notify("Торговец: не хватает золота.")
                end
            else
                engine.notify("Торговец: заходи ещё!")
            end
        end)
end)
