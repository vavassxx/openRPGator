package rpg.engine.core.component;
import rpg.engine.core.ecs.Component;

/** Marks an entity as an interaction/auto trigger zone. Entities within {@code radius}
 *  (2D, isometric plan view) fire enter/exit events, and players may interact with it. */
public record Trigger(double radius) implements Component {
    public Trigger { if (radius <= 0) throw new IllegalArgumentException("radius <= 0"); }
}