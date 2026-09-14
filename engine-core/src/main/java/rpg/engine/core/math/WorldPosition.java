package rpg.engine.core.math;
public record WorldPosition(double x,double y,double elevation){ public Vec2 xy(){return new Vec2(x,y);} public WorldPosition withXY(Vec2 p){return new WorldPosition(p.x(),p.y(),elevation);} }
