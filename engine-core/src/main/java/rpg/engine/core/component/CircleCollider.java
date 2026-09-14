package rpg.engine.core.component; public record CircleCollider(double radius) implements Collider { public CircleCollider{if(radius<0)throw new IllegalArgumentException();} }
