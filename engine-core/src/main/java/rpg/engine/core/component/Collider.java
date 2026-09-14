package rpg.engine.core.component;
import rpg.engine.core.ecs.Component; import rpg.engine.core.math.Vec2;
public sealed interface Collider extends Component permits BoxCollider,CircleCollider,PolygonCollider { }
