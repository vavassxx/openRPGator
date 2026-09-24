-- Арка у входа (0,1). Стоит игрок рядом — предлагает войти и телепортирует в центр города.
entity.set_trigger(1.2)

entity.on_enter(function(player)
  engine.dialog(player, "Тёмная арка дрожит, внутрь тянет прохладой... Войти в город?",
    {"Ныряю в арку"},
    function(choice)
      player.set_position(10, 10)
      engine.notify("Арка выплюнула вас на центральную площадь города.")
    end)
end)