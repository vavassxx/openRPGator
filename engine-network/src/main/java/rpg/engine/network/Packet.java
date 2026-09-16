package rpg.engine.network;

public sealed interface Packet permits Hello, Welcome, Input, Snapshot, Notify, Dialog, DialogResponse, PakList, PakChunk {
    byte type();
}