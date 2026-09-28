-- Grindlands: world/game coordinator.
-- The map contains one game_manager entity with this script. All mutable game state lives here;
-- entity scripts only own their entity's event bindings and call the services below.

local G = {
    tickRate = 20,
    players = {},
    mobs = {},
    resources = {},
    coins = {},
    uiSent = {},
}

G.commands = {
    USE_ITEM = 9002,
    OPEN_INVENTORY = 9001,
    CLOSE_SCREEN = 9003,
    OPEN_CHARACTER = 9004,
    BUY_WEAPON = 9005,
}

G.config = {
    spawn = { x = 32, y = 32 },
    safeRadius = 4,
    respawnSeconds = 4,
    player = { maxHp = 100, hpRegen = 0.25 },
    resource = { minRespawn = 6, maxRespawn = 12 },
    coin = { minRespawn = 15, maxRespawn = 25 },
    mob = { minRespawn = 10, maxRespawn = 15 },
    combat = { range = 2.0, cooldownTicks = 8, baseDamage = 5 },
}

G.weapons = {
    fists  = { name="Кулаки", dmg=0 },
    knife  = { name="Нож", dmg=3 },
    rsword = { name="Короткий меч", dmg=5 },
    sword  = { name="Меч", dmg=7 },
    cleaver= { name="Тесак", dmg=8 },
    machete= { name="Мачете", dmg=9 },
    mace   = { name="Булава", dmg=10 },
    axe    = { name="Топор", dmg=11 },
    hammer = { name="Молот", dmg=12 },
    katana = { name="Катана", dmg=13 },
    spear  = { name="Копьё", dmg=14 },
    baton  = { name="Дубинка", dmg=15 },
    waraxe = { name="Боевой топор", dmg=16 },
    waxe   = { name="Тяжёлый топор", dmg=18 },
    dsword = { name="Длинный меч", dmg=20 },
    ksword = { name="Рыцарский меч", dmg=22 },
    lsword = { name="Легендарный меч", dmg=25 },
    anime  = { name="Аниме-меч", dmg=28 },
    saw    = { name="Пила", dmg=30 },
    bsword = { name="Кровавый меч", dmg=34 },
    gsword = { name="Золотой меч", dmg=38 },
    bhammer= { name="Большой молот", dmg=42 },
}

G.weaponPrices = {
    knife=15, rsword=35, sword=60, cleaver=80, machete=95, mace=120, axe=135,
    hammer=150, katana=180, spear=210, baton=240, waraxe=300, waxe=340,
    dsword=420, ksword=500, lsword=700, anime=900, saw=1100, bsword=1400,
    gsword=1800, bhammer=2400,
}

G.items = {
    hpotion = { name="Зелье здоровья", kind="heal", amount=40 },
    gpotion = { name="Большое зелье", kind="heal", amount=70 },
    mpotion = { name="Зелье маны", kind="noop" },
}

G.mobTypes = {
    goblin  = { hp=35,  dmg=6,  xp=12, gold=5,  aggro=5, speed=0.035, atkcd=30 },
    skelet  = { hp=45,  dmg=8,  xp=16, gold=7,  aggro=5, speed=0.032, atkcd=32 },
    tzombie = { hp=60,  dmg=10, xp=22, gold=9,  aggro=5, speed=0.025, atkcd=36 },
    imp     = { hp=55,  dmg=11, xp=24, gold=11, aggro=6, speed=0.038, atkcd=28 },
    orc     = { hp=80,  dmg=14, xp=30, gold=14, aggro=6, speed=0.030, atkcd=34 },
    chort   = { hp=95,  dmg=16, xp=36, gold=17, aggro=6, speed=0.032, atkcd=34 },
    ogre    = { hp=140, dmg=22, xp=55, gold=25, aggro=7, speed=0.022, atkcd=40 },
    wogol   = { hp=160, dmg=25, xp=62, gold=28, aggro=7, speed=0.025, atkcd=38 },
    swampy  = { hp=180, dmg=28, xp=70, gold=32, aggro=7, speed=0.023, atkcd=40 },
    necro   = { hp=220, dmg=34, xp=90, gold=42, aggro=8, speed=0.027, atkcd=42 },
    bzombie = { hp=260, dmg=39, xp=105,gold=48, aggro=8, speed=0.020, atkcd=45 },
    bdemon  = { hp=320, dmg=48, xp=130,gold=60, aggro=9, speed=0.024, atkcd=48 },
}

G.loot = {
    goblin  = { {"knife", .20}, {"hpotion", .20} },
    skelet  = { {"rsword", .16}, {"hpotion", .20} },
    tzombie = { {"sword", .12}, {"hpotion", .25}, {"gpotion", .06} },
    imp     = { {"cleaver", .12}, {"machete", .10}, {"hpotion", .20} },
    orc     = { {"mace", .10}, {"axe", .10}, {"gpotion", .08} },
    chort   = { {"hammer", .10}, {"katana", .07}, {"gpotion", .10} },
    ogre    = { {"spear", .10}, {"baton", .10}, {"waraxe", .08}, {"gpotion", .12} },
    wogol   = { {"waxe", .09}, {"dsword", .08}, {"gpotion", .14} },
    swampy  = { {"ksword", .08}, {"lsword", .06}, {"gpotion", .16} },
    necro   = { {"anime", .07}, {"saw", .06}, {"gpotion", .18} },
    bzombie = { {"bsword", .06}, {"gsword", .05}, {"gpotion", .20} },
    bdemon  = { {"bhammer", .05}, {"gsword", .08}, {"gpotion", .24} },
}

G.resourceNames = { ore="руда", herb="трава", wood="дерево" }
G.resourcePrices = { ore=5, herb=3, wood=4 }
G.potionPrices = { hpotion=15, mpotion=10, gpotion=25 }

function G.xpForLevel(level)
    return 50 + (level - 1) * 35
end

function G.maxHpForLevel(level)
    return G.config.player.maxHp + (level - 1) * 20
end

function G.distanceSquared(a, b)
    local dx, dy = a.x - b.x, a.y - b.y
    return dx * dx + dy * dy
end

function G.player(pid)
    return G.players[pid]
end

function G.ensurePlayer(player)
    local pid = player.id()
    local p = G.players[pid]
    if p then return p end

    p = {
        hp = G.config.player.maxHp,
        maxHp = G.config.player.maxHp,
        level = 1,
        xp = 0,
        gold = 0,
        weapon = "fists",
        inventory = {},
        resources = { ore=0, herb=0, wood=0 },
        attackCooldownUntil = 0,
        alive = true,
        respawnAt = 0,
        screen = "hud",
    }
    G.players[pid] = p
    player.set_position(G.config.spawn.x, G.config.spawn.y)
    engine.notify("Добро пожаловать! Кулаки уже наносят урон. Собирай монеты или ресурсы, продавай их торговцу и покупай оружие.")
    return p
end

function G.itemName(id)
    if G.weapons[id] then return G.weapons[id].name end
    if G.items[id] then return G.items[id].name end
    return id
end

function G.hasItem(p, id)
    for _, item in ipairs(p.inventory) do
        if item == id then return true end
    end
    return false
end

function G.removeItem(p, id)
    for i, item in ipairs(p.inventory) do
        if item == id then
            table.remove(p.inventory, i)
            return true
        end
    end
    return false
end

function G.addItem(p, id)
    if not G.weapons[id] and not G.items[id] then return false end
    table.insert(p.inventory, id)
    return true
end

function G.addXp(player, p, amount)
    p.xp = p.xp + amount
    while p.xp >= G.xpForLevel(p.level) do
        p.xp = p.xp - G.xpForLevel(p.level)
        p.level = p.level + 1
        p.maxHp = G.maxHpForLevel(p.level)
        p.hp = p.maxHp
        engine.notify(player.name() .. " достиг уровня " .. p.level ..
            "! Макс. HP: " .. p.maxHp)
    end
end

function G.dropLoot(player, p, mobType)
    local tableForMob = G.loot[mobType]
    if not tableForMob then return end
    for _, drop in ipairs(tableForMob) do
        if math.random() < drop[2] then
            G.addItem(p, drop[1])
            engine.notify("Получен предмет: " .. G.itemName(drop[1]))
        end
    end
end

function G.buyWeapon(player, p, id)
    local weapon = G.weapons[id]
    local price = G.weaponPrices[id]
    if not weapon or not price then return end
    if p.gold < price then
        engine.notify("Торговец: нужно " .. price .. " золота, а у тебя " .. p.gold .. ".")
        return
    end
    p.gold = p.gold - price
    G.addItem(p, id)
    G.useItem(player, p, id)
    p.screen = "hud"
    G.hud(player, p)
    engine.notify("Куплено: " .. weapon.name .. " за " .. price .. " золота.")
end

function G.useItem(player, p, id)
    if not p.alive or not G.hasItem(p, id) then return end

    local weapon = G.weapons[id]
    if weapon then
        if p.weapon ~= "fists" then G.addItem(p, p.weapon) end
        G.removeItem(p, id)
        p.weapon = id
        engine.notify("Экипировано: " .. weapon.name .. " (+" .. weapon.dmg .. ")")
        return
    end

    local item = G.items[id]
    if not item then return end

    if item.kind == "heal" then
        G.removeItem(p, id)
        local old = p.hp
        p.hp = math.min(p.maxHp, p.hp + item.amount)
        engine.notify(G.itemName(id) .. ": +" .. math.floor(p.hp - old) .. " HP")
    elseif item.kind == "noop" then
        G.removeItem(p, id)
        engine.notify("Зелье маны выпито. Мана пока не используется.")
    end
end

function G.findMobTarget(player, range)
    local pos = player.position()
    local bestId, bestState, bestD2 = nil, nil, range * range
    for id, m in pairs(G.mobs) do
        if not m.dead then
            local mob = world.get(id)
            if mob and mob.exists() then
                local d2 = G.distanceSquared(pos, mob.position())
                if d2 <= bestD2 then
                    bestId, bestState, bestD2 = id, m, d2
                end
            end
        end
    end
    return bestId, bestState
end

function G.attackMob(player, mobId)
    local p = G.players[player.id()]
    local m = G.mobs[mobId]
    if not p or not p.alive or not m or m.dead then return false end

    local mob = world.get(mobId)
    if not mob or not mob.exists() then return false end
    if G.distanceSquared(player.position(), mob.position()) > G.config.combat.range * G.config.combat.range then
        return false
    end

    local now = engine.tick()
    if now < p.attackCooldownUntil then return false end
    p.attackCooldownUntil = now + G.config.combat.cooldownTicks

    local weapon = G.weapons[p.weapon] or G.weapons.fists
    local damage = G.config.combat.baseDamage + p.level * 2 + weapon.dmg + math.random(0, 3)
    m.hp = math.max(0, m.hp - damage)
    m.target = player
    if m.hp == 0 then
        m.dead = true
        m.target = nil
        m.respawnAt = now + (G.config.mob.minRespawn * G.tickRate) +
            math.random(0, (G.config.mob.maxRespawn - G.config.mob.minRespawn) * G.tickRate)
        mob.set_scale(.01)
        G.addXp(player, p, m.xp)
        p.gold = p.gold + m.gold
        G.dropLoot(player, p, m.type)
        engine.notify("Побеждён " .. m.type .. ": +" .. m.xp .. " XP, +" .. m.gold .. " золота")
    end
    return true
end

function G.hud(player, p)
    local weapon = G.weapons[p.weapon] or G.weapons.fists
    local targetId, target = G.findMobTarget(player, G.config.combat.range)
    local strings = {
        "HP " .. math.floor(p.hp) .. "/" .. p.maxHp,
        "Ур. " .. p.level,
        "Золото: " .. p.gold,
        weapon.name,
    }
    local layout = {
        {type="panel",x=.01,y=.01,w=.47,h=.115,bg={0,0,0,.70}},
        {type="bar",x=.025,y=.018,w=.20,h=.024,value=p.hp,max=p.maxHp,fill={.85,.20,.20},back={.13,.14,.19}},
        {type="text",x=.235,y=.014,ref=0,size=12,color={1,1,1}},
        {type="bar",x=.025,y=.057,w=.20,h=.016,value=p.xp,max=G.xpForLevel(p.level),fill={.25,.60,.95},back={.13,.14,.19}},
        {type="text",x=.235,y=.050,ref=1,size=10,color={.7,.85,1}},
        {type="text",x=.025,y=.086,ref=2,size=11,color={1,.85,.4}},
        {type="text",x=.13,y=.086,ref=3,size=11,color={1,.85,.2}},
        {type="button",x=.69,y=.01,w=.13,h=.045,ref=4,cmd=G.commands.OPEN_INVENTORY},
        {type="button",x=.83,y=.01,w=.13,h=.045,ref=5,cmd=G.commands.OPEN_CHARACTER},
    }

    -- Цель больше не занимает огромную центральную плашку. Показываем её только
    -- когда рядом действительно есть живой моб: компактный HUD под верхней строкой.
    if target then
        local names = {
            goblin="Гоблин", skelet="Скелет", tzombie="Зомби", imp="Бес",
            orc="Орк", chort="Чорт", ogre="Огр", wogol="Вогол", swampy="Болотник",
            necro="Некромант", bzombie="Большой зомби", bdemon="Большой демон",
        }
        table.insert(strings, names[target.type] or target.type)
        local targetNameRef = #strings - 1
        table.insert(strings, "HP " .. math.floor(target.hp) .. "/" .. target.maxHp)
        local targetHpRef = #strings - 1
        table.insert(layout, {type="panel",x=.35,y=.14,w=.30,h=.075,bg={.02,.03,.04,.82}})
        table.insert(layout, {type="text",x=.365,y=.147,ref=targetNameRef,size=12,color={1,.85,.4}})
        table.insert(layout, {type="bar",x=.365,y=.173,w=.17,h=.014,value=target.hp,max=target.maxHp,fill={.90,.20,.20},back={.12,.13,.17}})
        table.insert(layout, {type="text",x=.545,y=.166,ref=targetHpRef,size=9,color={1,1,1}})
    end

    engine.layout(player, layout, strings)
end

function G.inventoryLayout(player, p)
    local strings = {"Инвентарь", "Закрыть"}
    local layout = {
        {type="panel",x=.18,y=.12,w=.64,h=.72,bg={.02,.03,.04,.94}},
        {type="text",x=.22,y=.15,ref=0,size=18,color={1,.85,.4}},
        {type="button",x=.70,y=.15,w=.09,h=.045,ref=1,cmd=G.commands.CLOSE_SCREEN},
    }
    local y = .22
    for _, id in ipairs(p.inventory) do
        table.insert(strings, G.itemName(id))
        table.insert(layout, {type="button",x=.23,y=y,w=.54,h=.045,ref=#strings-1,cmd=G.commands.USE_ITEM,value=id})
        y = y + .052
        if y > .78 then break end
    end
    if #p.inventory == 0 then
        table.insert(strings, "Инвентарь пуст")
        table.insert(layout, {type="text",x=.23,y=.24,ref=#strings-1,size=13,color={.8,.8,.8}})
    end
    engine.layout(player, layout, strings)
end

function G.characterLayout(player, p)
    local weapon = G.weapons[p.weapon] or G.weapons.fists
    local strings = {
        "Персонаж", "Закрыть",
        "Уровень: " .. p.level,
        "HP: " .. math.floor(p.hp) .. "/" .. p.maxHp,
        "XP: " .. p.xp .. "/" .. G.xpForLevel(p.level),
        "Золото: " .. p.gold,
        "Оружие: " .. weapon.name .. " (+" .. weapon.dmg .. ")",
        "Руда: " .. (p.resources.ore or 0),
        "Трава: " .. (p.resources.herb or 0),
        "Дерево: " .. (p.resources.wood or 0),
    }
    local layout = {
        {type="panel",x=.22,y=.18,w=.56,h=.58,bg={.02,.03,.04,.94}},
        {type="text",x=.27,y=.21,ref=0,size=18,color={1,.85,.4}},
        {type="button",x=.66,y=.21,w=.09,h=.045,ref=1,cmd=G.commands.CLOSE_SCREEN},
    }
    local y=.30
    for i=3,#strings do
        table.insert(layout,{type="text",x=.28,y=y,ref=i-1,size=13,color={.88,.88,.9}})
        y=y+.043
    end
    engine.layout(player,layout,strings)
end

function G.resetScreen(player, p)
    G.hud(player, p)
end

function G.handleCommand(player, code, arg)
    local p = G.players[player.id()]
    if not p then return end
    if code == G.commands.BUY_WEAPON then
        G.buyWeapon(player, p, arg)
    elseif code == G.commands.USE_ITEM then
        G.useItem(player, p, arg)
        p.screen = "inventory"
        G.inventoryLayout(player, p)
    elseif code == G.commands.OPEN_INVENTORY then
        p.screen = "inventory"
        G.inventoryLayout(player, p)
    elseif code == G.commands.OPEN_CHARACTER then
        p.screen = "character"
        G.characterLayout(player, p)
    elseif code == G.commands.CLOSE_SCREEN then
        p.screen = "hud"
        G.resetScreen(player, p)
    end
end

function G.updatePlayers(now)
    local online = {}
    for _, player in ipairs(world.players()) do
        local pid = player.id()
        online[pid] = true
        local p = G.ensurePlayer(player)

        if p.alive then
            local pos = player.position()
            if G.distanceSquared(pos, G.config.spawn) < G.config.safeRadius * G.config.safeRadius
                and p.hp < p.maxHp then
                p.hp = math.min(p.maxHp, p.hp + G.config.player.hpRegen)
            end
        end

        if p.hp <= 0 and p.alive then
            p.alive = false
            local lost = math.floor(p.gold * .10)
            p.gold = p.gold - lost
            p.respawnAt = now + G.config.respawnSeconds * G.tickRate
            -- Смерть немедленно сбрасывает агро всех мобов, которые держали этого игрока.
            for _, m in pairs(G.mobs) do
                if m.target and m.target.id() == pid then
                    m.target = nil
                    m.attackAt = 0
                end
            end
            engine.notify(player.name() .. " пал! Потеряно " .. lost ..
                " золота. Возрождение через " .. G.config.respawnSeconds .. " сек.")
        end

        if not p.alive and now >= p.respawnAt then
            p.alive = true
            p.hp = math.floor(p.maxHp * .5)
            player.set_position(G.config.spawn.x, G.config.spawn.y)
            engine.notify(player.name() .. " возродился в городе.")
        end

        if not G.uiSent[pid] then
            engine.send_script(player, [[
                -- Transparent bridge: server layouts remain visible, and their buttons
                -- are forwarded back to the authoritative server.
                ui.on_layout(function(widgets, strings)
                    ui.layout(widgets, strings)
                end)
                ui.on_command(function(code, arg)
                    ui.send(code, arg)
                end)
            ]], "grindlands-ui")
            G.uiSent[pid] = true
        end

        if now % 5 == 0 and p.screen == "hud" then G.hud(player, p) end
    end

    for pid in pairs(G.players) do
        if not online[pid] then
            G.players[pid] = nil
            G.uiSent[pid] = nil
        end
    end
end

local function updateMobs(now)
    for id, m in pairs(G.mobs) do
        local me = world.get(id)
        if me == nil or not me.exists() then
            G.mobs[id] = nil
        elseif m.dead then
            if now >= m.respawnAt then
                m.hp = m.maxHp
                m.dead = false
                m.target = nil
                me.set_position(m.homeX, m.homeY)
                me.set_scale(1)
            end
        else
            local pos = me.position()
            local nearest, nearestD2 = nil, math.huge
            for _, player in ipairs(world.players()) do
                local p = G.players[player.id()]
                if p and p.alive then
                    local pp = player.position()
                    local d2 = G.distanceSquared(pos, pp)
                    if d2 < nearestD2 then nearest, nearestD2 = player, d2 end
                end
            end

            local aggro2 = m.aggro * m.aggro
            if m.target then
                local targetState = G.players[m.target.id()]
                if not targetState or not targetState.alive then
                    m.target = nil
                end
            end
            if nearest and nearestD2 <= aggro2 then
                m.target = nearest
            elseif m.target and G.distanceSquared(pos, m.target.position()) > aggro2 * 6.25 then
                m.target = nil
            end

            if m.target then
                local tp = m.target.position()
                local dx, dy = tp.x-pos.x, tp.y-pos.y
                local d = math.sqrt(dx*dx + dy*dy)
                if d <= 1.3 then
                    if now >= m.attackAt then
                        local target = G.players[m.target.id()]
                        if target and target.alive then
                            target.hp = math.max(0, target.hp - m.dmg)
                            if target.hp <= 0 then
                                m.target = nil
                            end
                        else
                            m.target = nil
                        end
                        m.attackAt = now + m.atkcd
                    end
                elseif d > 0 then
                    me.set_position(pos.x + dx/d*m.speed, pos.y + dy/d*m.speed)
                end
            else
                local dx, dy = m.homeX-pos.x, m.homeY-pos.y
                local d = math.sqrt(dx*dx + dy*dy)
                if d > .5 then
                    me.set_position(pos.x + dx/d*m.speed*.6, pos.y + dy/d*m.speed*.6)
                end
            end
        end
    end
end

local function updateResources(now)
    for id, r in pairs(G.resources) do
        local me = world.get(id)
        if me == nil or not me.exists() then
            G.resources[id] = nil
        elseif r.depleted and now >= r.respawnAt then
            r.depleted = false
            me.set_scale(r.originalScale)
        end
    end
end

local function updateCoins(now)
    for id, c in pairs(G.coins) do
        local me = world.get(id)
        if me == nil or not me.exists() then
            G.coins[id] = nil
        elseif c.collected and now >= c.respawnAt then
            c.collected = false
            me.set_scale(.5)
        end
    end
end

-- One global tick coordinator. Entity scripts register data and interaction handlers;
-- simulation policy stays here instead of being duplicated across 50+ entity callbacks.
engine.on_tick(function(now)
    G.updatePlayers(now)
    updateMobs(now)
    updateResources(now)
    updateCoins(now)
end)

engine.on_action(function(player, action)
    if action == "primary" then
        local targetId = G.findMobTarget(player, G.config.combat.range)
        if targetId then
            G.attackMob(player, targetId)
        end
    end
end)

engine.on_command(function(player, code, arg)
    G.handleCommand(player, code, arg)
end)

_G.GRINDLANDS = G
