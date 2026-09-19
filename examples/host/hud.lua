-- Мировой «хост» (сущность hud на карте): скриптовая механика здоровья и HUD.
--
-- HP игроков хранится ЗДЕСЬ, в Lua — движок про здоровье ничего не знает.
-- Опасная руна ("lava") снимает 1 HP за тик, пока игрок стоит в зоне; светлая
-- руна ("heal") восстанавливает 1 HP за тик. Каждый тик каждому игроку шлётся
-- HOST-ИНИЦИИРОВАННАЯ СХЕМА (engine.layout): полоска (bar) + число (text) —
-- клиент только рисует виджеты, смысл им даёт этот скрипт.
local hps = {}
local MAX_HP = 100
local LAVA_R = 0.9
local HEAL_R = 0.9

local function dist2(a, b)
  return (a.x - b.x) ^ 2 + (a.y - b.y) ^ 2
end

engine.on_tick(function(t)
  local lava = world.find("lava")
  local heal = world.find("heal")
  for _, p in ipairs(world.players()) do
    local id = p.id()
    local hp = hps[id] or MAX_HP
    local pos = p.position()

    if lava ~= nil and dist2(pos, lava.position()) <= LAVA_R * LAVA_R then
      hp = math.max(0, hp - 1)
      if hp == 0 then engine.notify("Осторожно: вы на грани! Сойдите с пылающей руны.") end
    end
    if heal ~= nil and dist2(pos, heal.position()) <= HEAL_R * HEAL_R then
      hp = math.min(MAX_HP, hp + 1)
    end
    hps[id] = hp

    -- Схема для клиента: панель + полоска + числовое значение рядом.
    local layout = {
      { type = "panel", x = 0.01, y = 0.092, w = 0.24, h = 0.052, bg = { 0, 0, 0, 0.45 } },
      { type = "bar",   x = 0.022, y = 0.102, w = 0.16, h = 0.028, value = hp, max = MAX_HP,
        fill = { 0.30, 0.78, 0.35 }, back = { 0.13, 0.14, 0.19 } },
      { type = "text",  x = 0.19, y = 0.108, ref = 1, size = 12, color = { 1, 1, 1 } }
    }
    engine.layout(p, layout, { "Здоровье", "HP " .. hp .. "/" .. MAX_HP })
  end
end)