package rpg.engine.world;
import rpg.engine.core.ecs.EntityId;

/** An entity entered a trigger zone {@code trigger}. {@code entity} is the entering actor. */
public record TriggerEnterEvent(EntityId entity, EntityId trigger) {}