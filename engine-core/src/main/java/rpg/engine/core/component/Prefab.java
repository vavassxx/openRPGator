package rpg.engine.core.component;

import rpg.engine.core.ecs.Component;

/** Semantic asset/behaviour tag for a spawned entity, mirrored from {@code MapEntity.prefab()}. */
public record Prefab(String value) implements Component {}