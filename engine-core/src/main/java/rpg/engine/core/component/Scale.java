package rpg.engine.core.component;

import rpg.engine.core.ecs.Component;

/**
 * Render scale of an entity relative to its sprite's native size. Absent implies 1.0.
 * Set from the map (.rmap entity scale) or at runtime via Lua {@code entity.set_scale(n)}.
 */
public record Scale(double value) implements Component {}