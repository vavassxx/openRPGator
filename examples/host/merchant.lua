-- Торговец (8,12). Демо-магазин: одна покупка, дальше отфутболивает.
entity.set_trigger(2.0)
local sold = false

entity.on_interact(function(player)
  if sold then
    engine.notify("Торговец: Меч больше не продаю — себе оставил.")
    return
  end
  engine.dialog(player, "Добрый день! Изумрудный меч, ковка последней ночи. Всего пять монет!",
    {"Купить меч за 5 монет", "Просто смотрю"},
    function(choice)
      if choice == 1 then
        sold = true
        engine.notify("Торговец: Держи! (Инвентаря ещё нет — это демо торговли.)")
      else
        engine.notify("Торговец: Глазами торговать — так себе бизнес, но уважаю.")
      end
    end)
end)