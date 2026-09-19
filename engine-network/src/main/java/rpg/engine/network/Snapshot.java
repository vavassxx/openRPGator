package rpg.engine.network; import java.util.*; public record Snapshot(List<EntityState> entities) implements Packet {public byte type(){return 4;}
    /**
     * {@code sprite} is the entity's texture name — the {@code sprite/*} key from the asset pack
     * ({@code player} for spawned players, the entity prefab for map/Lua entities). Clients resolve
     * it by name against their loaded packs, so texture identity never depends on array ordering.
     * {@code ""} means the entity has no sprite and should render as a marker/none.
     * {@code scale} is a per-entity render-size multiplier (default 1.0), settable in the map and via
     * Lua {@code entity.set_scale(...)}. {@code elevation} is in tile units, 0 on the ground.
     */
    public record EntityState(long id,double x,double y,double elevation,String sprite,double scale){} }
