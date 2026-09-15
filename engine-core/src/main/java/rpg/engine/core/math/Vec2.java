package rpg.engine.core.math;
public record Vec2(double x,double y){ public static final Vec2 ZERO=new Vec2(0,0); public Vec2 add(Vec2 o){return new Vec2(x+o.x,y+o.y);} public Vec2 sub(Vec2 o){return new Vec2(x-o.x,y-o.y);} public Vec2 mul(double s){return new Vec2(x*s,y*s);} public double length(){return Math.hypot(x,y);} public Vec2 normalized(){double l=length();return l==0?ZERO:mul(1/l);} }
