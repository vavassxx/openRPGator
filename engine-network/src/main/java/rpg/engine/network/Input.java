package rpg.engine.network; public record Input(double dx,double dy) implements Packet {public byte type(){return 3;}}
