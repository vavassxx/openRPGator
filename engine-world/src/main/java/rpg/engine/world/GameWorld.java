package rpg.engine.world;
import rpg.engine.core.ecs.*;import rpg.engine.core.event.EventBus;import java.util.*;
public final class GameWorld { private final WorldRegistry registry=new WorldRegistry(); private final EventBus events=new EventBus(); private final CollisionWorld collision=new CollisionWorld(registry); private final TriggerSystem triggers=new TriggerSystem(registry,events); private long tick;
 public WorldRegistry entities(){return registry;} public EventBus events(){return events;} public CollisionWorld collision(){return collision;} public long tick(){return tick;} public void step(){tick++;triggers.step();} public EntityId spawn(){return registry.create();}
 /** Nearest trigger entity within {@code radius} of {@code p}, if any. */
 public Optional<EntityId> interactTarget(rpg.engine.core.math.WorldPosition p,double radius){return triggers.findInteract(p,radius);} }