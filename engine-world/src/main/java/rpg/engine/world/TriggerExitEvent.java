package rpg.engine.world;
import rpg.engine.core.ecs.EntityId;

/** An entity left a trigger zone {@code trigger}. */
public record TriggerExitEvent(EntityId entity, EntityId trigger) {}