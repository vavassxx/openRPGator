-- Колодец (11,13). Интерактив с одноразовым "эффектом".
entity.set_trigger(1.5)

entity.on_interact(function(player)
  engine.notify("Колодец: вы набрали полную флягу воды. (Фляги пока нет — демо.)")
end)