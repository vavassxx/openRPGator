package rpg.engine.core.ecs;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class WorldRegistry {
 private final AtomicLong ids=new AtomicLong(); private final Set<EntityId> entities=new LinkedHashSet<>();
 private final Map<Class<?>,Map<EntityId,Object>> components=new HashMap<>();
 public EntityId create(){var e=new EntityId(ids.incrementAndGet()); entities.add(e); return e;}
 public void destroy(EntityId e){entities.remove(e); components.values().forEach(m->m.remove(e));}
 public Set<EntityId> entities(){return Collections.unmodifiableSet(entities);}
 public <T> void set(EntityId e,T c){components.computeIfAbsent(c.getClass(),k->new LinkedHashMap<>()).put(e,c);}
 public <T> Optional<T> get(EntityId e,Class<T> type){var m=components.get(type); return m==null?Optional.empty():Optional.ofNullable(type.cast(m.get(e)));}
 public <T> void remove(EntityId e,Class<T> type){var m=components.get(type);if(m!=null)m.remove(e);}
 public <T> Collection<T> all(Class<T> type){var m=components.getOrDefault(type,Map.of()); return Collections.unmodifiableCollection(m.values().stream().map(type::cast).toList());}
}
