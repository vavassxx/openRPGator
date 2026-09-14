package rpg.engine.core.component;
import rpg.engine.core.ecs.Component; import rpg.engine.core.math.WorldPosition;
public record Transform(WorldPosition position,double rotation) implements Component {}
