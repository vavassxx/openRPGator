package rpg.engine.world;
import rpg.engine.core.ecs.*;
import rpg.engine.core.component.*;
import rpg.engine.core.event.*;
import rpg.engine.core.math.WorldPosition;
import java.util.*;

/**
 * Detects enter/exit of {@link Trigger} zones every step, diffing the current occupants
 * of each zone against the previous set, and emits {@link TriggerEnterEvent} /
 * {@link TriggerExitEvent} on the shared {@link EventBus}.
 *
 * O(T*E) per step — acceptable while worlds stay small; replace with a spatial grid later.
 */
public final class TriggerSystem {
    private final WorldRegistry registry;
    private final EventBus events;
    private final Map<EntityId, Set<EntityId>> inside = new HashMap<>();

    public TriggerSystem(WorldRegistry registry, EventBus events) { this.registry = registry; this.events = events; }

    public void step() {
        for (EntityId trigger : List.copyOf(registry.entities())) {
            Optional<Trigger> t = registry.get(trigger, Trigger.class);
            Optional<Transform> tt = registry.get(trigger, Transform.class);
            if (t.isEmpty() || tt.isEmpty()) continue;

            double r = t.get().radius();
            double tx = tt.get().position().x(), ty = tt.get().position().y();
            Set<EntityId> now = new HashSet<>();
            for (EntityId e : registry.entities()) {
                if (e.equals(trigger)) continue;
                registry.get(e, Transform.class).ifPresent(tr -> {
                    double dx = tr.position().x() - tx, dy = tr.position().y() - ty;
                    if (dx * dx + dy * dy <= r * r) now.add(e);
                });
            }

            Set<EntityId> prev = inside.computeIfAbsent(trigger, k -> new HashSet<>());
            for (EntityId e : now) if (!prev.contains(e)) events.emit(new TriggerEnterEvent(e, trigger));
            for (EntityId e : prev) if (!now.contains(e)) events.emit(new TriggerExitEvent(e, trigger));
            prev.clear();
            prev.addAll(now);
        }
        inside.keySet().retainAll(List.copyOf(registry.entities()));
    }

    /** Nearest trigger entity within {@code radius} of {@code p}, or empty. Used for interactions. */
    public Optional<EntityId> findInteract(WorldPosition p, double radius) {
        EntityId best = null;
        double bd = radius * radius;
        for (EntityId e : registry.entities()) {
            if (registry.get(e, Trigger.class).isEmpty()) continue;
            Optional<Transform> t = registry.get(e, Transform.class);
            if (t.isEmpty()) continue;
            double dx = t.get().position().x() - p.x(), dy = t.get().position().y() - p.y();
            double d = dx * dx + dy * dy;
            if (d <= bd) { bd = d; best = e; }
        }
        return best == null ? Optional.empty() : Optional.of(best);
    }
}