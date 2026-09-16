package rpg.engine.network; import java.util.*; public record Snapshot(List<EntityState> entities) implements Packet {public byte type(){return 4;}
    /**
     * {@code resource} is a server-chosen per-entity asset index (see {@code prefab} in the map).
     * 0 = default player/model; clients render {@code sprite/&lt;resource&gt;} from the loaded .pak,
     * falling back to a single-colored marker when the pack or entry is missing.
     */
    public record EntityState(long id,double x,double y,double elevation,int resource){} }
