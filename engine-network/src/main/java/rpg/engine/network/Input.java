package rpg.engine.network;

/** Semantic input frame. Movement is kept numeric for compatibility; actions are a bitmask. */
public record Input(double dx, double dy, int actions) implements Packet {
    public Input(double dx, double dy) { this(dx, dy, 0); }
    public static final int PRIMARY=1, SECONDARY=2, INTERACT=4, INVENTORY=8;
    public boolean has(int action){ return (actions & action) != 0; }
    public byte type(){ return 3; }
}
