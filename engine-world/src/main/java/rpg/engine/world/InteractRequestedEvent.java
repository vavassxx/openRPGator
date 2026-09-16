package rpg.engine.world;
import rpg.engine.core.ecs.EntityId;

/** A player requested interaction with {@code target} (Input.INTERACT). */
public record InteractRequestedEvent(EntityId player, EntityId target) {}