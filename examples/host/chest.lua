-- Сундук (13,9). Интеракт вынимает награду и сундук исчезает из мира (world.destroy по id).
entity.set_trigger(1.5)
local ent = entity

entity.on_interact(function(player)
  engine.notify("Сундук открыт. Внутри — кусок пожелтевшей карты.")
  world.destroy(ent.id())
end)