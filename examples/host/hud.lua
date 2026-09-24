-- Мировой «хост» (сущность hud на карте): скриптовая механика здоровья, HUD и
-- демо «мини-песочницы»: сервер шлёт клиенту небольшой Lua-скрипт (кастомный
-- HUD/экраны) и принимает обратно кастомные команды (engine.on_command), значения
-- которых придумывает этот же скрипт.
--
-- HP игроков хранится ЗДЕСЬ, в Lua — движок про здоровье ничего не знает.
-- Опасная руна ("lava") снимает 1 HP за тик, пока игрок стоит в зоне; светлая
-- руна ("heal") восстанавливает 1 HP за тик. Каждый тик каждому игроку шлётся
-- HOST-ИНИЦИИРОВАННАЯ СХЕМА (engine.layout): полоска (bar) + число (text) +
-- кнопка «Инвентарь» (button) — клиент только рисует виджеты, смысл им даёт
-- этот скрипт.
local hps = {}
local MAX_HP = 100
local LAVA_R = 0.9
local HEAL_R = 0.9

-- Клиентский скрипт песочницы: исполняется НА КЛИЕНТЕ, в ограниченном рантайме
-- (только ui.*: layout / send / notify / on_command / on_layout / clear).
-- Он сам открывает инвентарь локально (без хардкода в клиенте!), а выбор
-- предмета пробрасывает на сервер через ui.send → Cmd → engine.on_command.
local SANDBOX = [==[
-- "Инвентарь" — экран, который строит клиентская песочница по команде кнопки.
local open = false
local last_server = nil

ui.on_layout(function(widgets, strings)
  if not open then
    last_server = { widgets = widgets, strings = strings }
    ui.layout(widgets, strings)  -- как есть, сквозь скрипт
  end
  -- пока открыт инвентарь — игнорируем НР-бары сервера
end)

ui.on_command(function(cmd, arg)
  if cmd == 9001 then            -- кнопка "Инвентарь" на HUD
    open = true
    ui.layout({
      { type = "panel", x = 0.20, y = 0.16, w = 0.60, h = 0.62, bg = { 0.05, 0.05, 0.09, 0.96 } },
      { type = "text",  x = 0.28, y = 0.19, ref = 1, size = 16, color = { 1, 0.85, 0.4 } },
      { type = "button", x = 0.28, y = 0.30, w = 0.44, h = 0.09, ref = 2, cmd = 9002, value = "sword"  },
      { type = "button", x = 0.28, y = 0.42, w = 0.44, h = 0.09, ref = 3, cmd = 9002, value = "shield" },
      { type = "button", x = 0.28, y = 0.54, w = 0.44, h = 0.09, ref = 4, cmd = 9002, value = "potion" },
      { type = "button", x = 0.28, y = 0.68, w = 0.44, h = 0.09, ref = 5, cmd = 9003 },
    }, { "Инвентарь", "Меч — кликни", "Щит — кликни", "Зелье — кликни", "Закрыть" })
  elseif cmd == 9003 then        -- кнопка "Закрыть"
    open = false
    if last_server ~= nil then ui.layout(last_server.widgets, last_server.strings) end
  elseif cmd == 9002 then        -- выбран предмет — пробрасываем значение команды на сервер
    ui.send(9002, arg)
  end
end)
]==]

local scriptSent = {}

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

    -- Демо песочницы: первый сошедшийся игрок получает клиентский скрипт один раз.
    if not scriptSent[id] then
      engine.send_script(p, SANDBOX, "sandbox-demo")
      scriptSent[id] = true
    end

    -- Схема для клиента: панель + полоска + число + кнопка «Инвентарь».
    local layout = {
      { type = "panel", x = 0.01, y = 0.092, w = 0.24, h = 0.052, bg = { 0, 0, 0, 0.45 } },
      { type = "bar",   x = 0.022, y = 0.102, w = 0.16, h = 0.028, value = hp, max = MAX_HP,
        fill = { 0.30, 0.78, 0.35 }, back = { 0.13, 0.14, 0.19 } },
      { type = "text",  x = 0.19, y = 0.108, ref = 1, size = 12, color = { 1, 1, 1 } },
      { type = "button", x = 0.26, y = 0.092, w = 0.11, h = 0.052, ref = 3, cmd = 9001 },
    }
    engine.layout(p, layout, { "Здоровье", "HP " .. hp .. "/" .. MAX_HP, "Инвентарь" })
  end
end)

-- Кастомная команда с клиента: игрок выбрал предмет в инвентаре. Значение 9002
-- и строка "sword"/"shield"/"potion" придуманы этим же скриптом — движок и клиент
-- про них ничего не знают.
engine.on_command(function(p, code, arg)
  if code == 9002 then
    engine.notify(p.name() .. ": взят предмет «" .. arg .. "»")
  end
end)