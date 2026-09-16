package rpg.engine.network;

public sealed interface Packet permits Hello, Welcome, Input, Snapshot, Notify, Dialog, DialogResponse {
    byte type();
}