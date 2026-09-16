-- Крыса (9,14). Патрулирует квадратик вокруг своей точки каждые 10 тиков.
-- Второй способ "движения": сущность сама переставляет себя через me.set_position в on_tick.
entity.set_trigger(1.5)
local hx, hy = entity.position().x, entity.position().y

entity.on_tick(function(me)
  if engine.tick() % 10 ~= 0 then return end
  local t = math.floor(engine.tick() / 10) % 4
  if t == 0 then me.set_position(hx, hy)
  elseif t == 1 then me.set_position(hx + 2, hy)
  elseif t == 2 then me.set_position(hx + 2, hy + 2)
  else me.set_position(hx, hy + 2) end
end)

entity.on_interact(function(player)
  engine.dialog(player, "ПИ! *крыса выхватывает у вас крошку и отбегает*",
    {"Полюбоваться крысой"},
    function(choice)
      engine.notify("Крыса довольно урчит. Вам почему-то спокойно.")
    end)
end)