package rpg.engine.world;
import rpg.engine.core.ecs.EntityId;

/** An entity entered a trigger zone {@code trigger}. {@code entity} is the entering actor. */
public record TriggerEnterEvent(EntityId entity, EntityId trigger) {}

/** An entity left a trigger zone {@code trigger}. */
public record TriggerExitEvent(EntityId entity, EntityId trigger) {}

/** A player requested interaction with {@code target} (Input.INTERACT). */
public record InteractRequestedEvent(EntityId player, EntityId target) {}