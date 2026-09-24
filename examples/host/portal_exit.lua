-- Арка-выход (16,4). Уводит игрока обратно ко входу.
entity.set_trigger(1.5)

entity.on_enter(function(player)
  player.set_position(0, 1)
  engine.notify("Вы выскользнули из города через потайной ход.")
end)