package rpg.engine.core.ecs;
public record EntityId(long value) { public static final EntityId INVALID = new EntityId(0); }
