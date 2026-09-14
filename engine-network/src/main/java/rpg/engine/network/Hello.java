package rpg.engine.network; public record Hello(String name) implements Packet {public byte type(){return 1;}}
