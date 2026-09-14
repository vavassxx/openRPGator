package rpg.engine.core.event;
import java.util.*;import java.util.concurrent.*;import java.util.function.Consumer;
public final class EventBus { private final Map<Class<?>,CopyOnWriteArrayList<Consumer<Object>>> handlers=new ConcurrentHashMap<>(); public <T> void on(Class<T> t,Consumer<T> h){handlers.computeIfAbsent(t,k->new CopyOnWriteArrayList<>()).add((Consumer<Object>)(Object)h);} public void emit(Object e){for(var h:handlers.getOrDefault(e.getClass(),new CopyOnWriteArrayList<>()))h.accept(e);} }
