package rpg.engine.network; public record Welcome(long entityId) implements Packet {public byte type(){return 2;}}
