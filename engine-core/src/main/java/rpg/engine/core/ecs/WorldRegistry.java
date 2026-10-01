package rpg.engine.core.ecs;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class WorldRegistry {
 private final AtomicLong ids;
 private final Set<EntityId> entities=new LinkedHashSet<>();
 private final Map<Class<?>,Map<EntityId,Object>> components=new HashMap<>();
 public WorldRegistry(){this(0);}
 public WorldRegistry(long startingId){if(startingId<0)throw new IllegalArgumentException("startingId < 0");ids=new AtomicLong(startingId);}
 public EntityId create(){var e=new EntityId(ids.incrementAndGet());entities.add(e);return e;}
 /** Imports an already allocated id, allowing a host to migrate an entity between region worlds. */
 public void adopt(EntityId e){if(e==null||e.value()<=0)throw new IllegalArgumentException("invalid entity id");entities.add(e);ids.accumulateAndGet(e.value(),Math::max);}
 public void destroy(EntityId e){entities.remove(e);components.values().forEach(m->m.remove(e));}
 public Set<EntityId> entities(){return Collections.unmodifiableSet(entities);}
 public <T> void set(EntityId e,T c){if(!entities.contains(e))throw new IllegalArgumentException("unknown entity: "+e);components.computeIfAbsent(c.getClass(),k->new LinkedHashMap<>()).put(e,c);}
 public <T> Optional<T> get(EntityId e,Class<T> type){var exact=components.get(type);if(exact!=null){var value=exact.get(e);if(value!=null)return Optional.of(type.cast(value));}for(var entry:components.entrySet()){if(type.isAssignableFrom(entry.getKey())){var value=entry.getValue().get(e);if(value!=null)return Optional.of(type.cast(value));}}return Optional.empty();}
 public <T> void remove(EntityId e,Class<T> type){var m=components.get(type);if(m!=null)m.remove(e);}
 public <T> Collection<T> all(Class<T> type){var m=components.getOrDefault(type,Map.of());return Collections.unmodifiableCollection(m.values().stream().map(type::cast).toList());}
}
