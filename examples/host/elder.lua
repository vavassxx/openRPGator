-- Старейшина (12,11). Раздаёт простенькое поручение; запоминает игрока (локальная переменная живёт в скрипте).
entity.set_trigger(2.0)
local quest = false

entity.on_interact(function(player)
  if not quest then
    engine.dialog(player,
      "Здравствуй, путник. Торговец у лавки жалуется на крыс. Поможешь нашему городу?",
      {"Берусь за поручение", "Не сегодня"},
      function(choice)
        if choice == 1 then
          quest = true
          engine.notify("Старейшина: Отлично. Поговори с Торговцем у лавки, он сообразит.")
        else
          engine.notify("Старейшина: Как знаешь, дела города подождут.")
        end
      end)
  else
    engine.dialog(player, "Как продвигается поручение про крыс?",
      {"Крысы скоро сбегут отсюда", "Всё ещё ищу Торговца"},
      function(choice)
        engine.notify(choice == 1 and "Старейшина: Рад слышать! Держи наше спасибо."
            or "Старейшина: Он за углом, у колодца. Не заблудишься.")
      end)
  end
end)