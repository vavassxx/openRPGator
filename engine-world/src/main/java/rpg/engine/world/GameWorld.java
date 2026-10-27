package rpg.engine.world;

import rpg.engine.core.ecs.*;
import rpg.engine.core.event.EventBus;
import java.util.*;

public final class GameWorld {
    private final WorldRegistry registry;
    private final EventBus events = new EventBus();
    private final CollisionWorld collision;
    private final TriggerSystem triggers;
    private long tick;

    public GameWorld() { this(0); }

    /** Starts local entity allocation above {@code entityIdBase}; useful for region namespaces. */
    public GameWorld(long entityIdBase) {
        registry = new WorldRegistry(entityIdBase);
        collision = new CollisionWorld(registry);
        triggers = new TriggerSystem(registry, events);
    }

    public WorldRegistry entities() { return registry; }
    public EventBus events() { return events; }
    public CollisionWorld collision() { return collision; }
    public long tick() { return tick; }
    public void step() { tick++; triggers.step(); }
    public EntityId spawn() { return registry.create(); }

    /** Nearest trigger entity within {@code radius} of {@code p}, if any. */
    public Optional<EntityId> interactTarget(rpg.engine.core.math.WorldPosition p, double radius) {
        return triggers.findInteract(p, radius);
    }
}
