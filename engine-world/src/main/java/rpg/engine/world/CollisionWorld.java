package rpg.engine.world;

import rpg.engine.core.ecs.*;
import rpg.engine.core.component.*;
import rpg.engine.core.math.*;
import java.util.*;

/** Lightweight authoritative 2D collision with optional finite vertical wall segments. */
public final class CollisionWorld {
    private final WorldRegistry registry;
    public CollisionWorld(WorldRegistry r) { registry = r; }

    public boolean canOccupy(EntityId moving, WorldPosition p) {
        var c = registry.get(moving, Collider.class);
        if (c.isEmpty()) return true;
        for (var e : registry.entities()) {
            if (e.equals(moving)) continue;
            var oc = registry.get(e, Collider.class);
            var ot = registry.get(e, Transform.class);
            if (oc.isEmpty() || ot.isEmpty()) continue;
            if (!verticalOverlap(c.get(), p.elevation(), oc.get(), ot.get().position().elevation())) continue;
            if (intersects(c.get(), p, oc.get(), ot.get().position())) return false;
        }
        return true;
    }

    public WorldPosition move(EntityId e, WorldPosition desired) {
        var t = registry.get(e, Transform.class).orElseThrow();
        if (canOccupy(e, desired)) return desired;
        var x = new WorldPosition(desired.x(), t.position().y(), desired.elevation());
        if (canOccupy(e, x)) return x;
        var y = new WorldPosition(t.position().x(), desired.y(), desired.elevation());
        if (canOccupy(e, y)) return y;
        return t.position();
    }

    public static boolean intersects(Collider a, WorldPosition ap, Collider b, WorldPosition bp) {
        if (a instanceof WallCollider wa) return wallIntersects(wa, ap, b, bp);
        if (b instanceof WallCollider wb) return wallIntersects(wb, bp, a, ap);
        if (a instanceof CircleCollider ca && b instanceof CircleCollider cb)
            return ap.xy().sub(bp.xy()).length() <= ca.radius() + cb.radius();
        if (a instanceof BoxCollider ba && b instanceof BoxCollider bb)
            return Math.abs(ap.x()-bp.x()) <= ba.half().x()+bb.half().x()
                    && Math.abs(ap.y()-bp.y()) <= ba.half().y()+bb.half().y();
        return bounds(a,ap).intersects(bounds(b,bp));
    }

    private static boolean verticalOverlap(Collider a, double az, Collider b, double bz) {
        if (a instanceof WallCollider wa) return wallZContains(wa, az, 0);
        if (b instanceof WallCollider wb) return wallZContains(wb, bz, 0);
        return true;
    }

    private static boolean wallZContains(WallCollider w, double z, double otherHeight) {
        return z + otherHeight >= w.bottom() && z <= w.top();
    }

    private static boolean wallIntersects(WallCollider wall, WorldPosition wp, Collider other, WorldPosition op) {
        if (other instanceof WallCollider) return false; // walls don't block other walls
        double radius = 0;
        if (other instanceof CircleCollider c) radius = c.radius();
        else if (other instanceof BoxCollider b) radius = Math.hypot(b.half().x(), b.half().y());
        else if (other instanceof PolygonCollider p) {
            for (Vec2 v : p.vertices()) radius = Math.max(radius, Math.hypot(v.x(), v.y()));
        }
        radius += wall.thickness() * 0.5;
        return pointSegmentDistanceSquared(op.x(), op.y(),
                wp.x() + wall.start().x(), wp.y() + wall.start().y(),
                wp.x() + wall.end().x(), wp.y() + wall.end().y()) <= radius * radius;
    }

    private static double pointSegmentDistanceSquared(double px,double py,double ax,double ay,double bx,double by) {
        double dx=bx-ax, dy=by-ay, len2=dx*dx+dy*dy;
        double t=len2 == 0 ? 0 : ((px-ax)*dx+(py-ay)*dy)/len2;
        t=Math.max(0,Math.min(1,t));
        double qx=ax+t*dx, qy=ay+t*dy;
        double ex=px-qx, ey=py-qy;
        return ex*ex+ey*ey;
    }

    private record Rect(double minX,double minY,double maxX,double maxY) {
        boolean intersects(Rect r) { return minX<=r.maxX&&maxX>=r.minX&&minY<=r.maxY&&maxY>=r.minY; }
    }
    private static Rect bounds(Collider c, WorldPosition p) {
        if(c instanceof BoxCollider b) return new Rect(p.x()-b.half().x(),p.y()-b.half().y(),p.x()+b.half().x(),p.y()+b.half().y());
        if(c instanceof CircleCollider q) return new Rect(p.x()-q.radius(),p.y()-q.radius(),p.x()+q.radius(),p.y()+q.radius());
        if(c instanceof WallCollider w) {
            double pad=w.thickness()*0.5;
            double minx=Math.min(w.start().x(),w.end().x())+p.x()-pad,maxx=Math.max(w.start().x(),w.end().x())+p.x()+pad;
            double miny=Math.min(w.start().y(),w.end().y())+p.y()-pad,maxy=Math.max(w.start().y(),w.end().y())+p.y()+pad;
            return new Rect(minx,miny,maxx,maxy);
        }
        var v=((PolygonCollider)c).vertices(); double minx=Double.POSITIVE_INFINITY,miny=Double.POSITIVE_INFINITY,maxx=-minx,maxy=-miny;
        for(var x:v){minx=Math.min(minx,p.x()+x.x());maxx=Math.max(maxx,p.x()+x.x());miny=Math.min(miny,p.y()+x.y());maxy=Math.max(maxy,p.y()+x.y());}
        return new Rect(minx,miny,maxx,maxy);
    }
}
