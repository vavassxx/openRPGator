package rpg.engine.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Length-framed binary wire protocol for the engine network layer.
 *
 * <p>Each packet is encoded as:
 * <pre>
 *   int frameLength
 *   byte packetType
 *   packet payload
 * </pre>
 *
 * <p>The protocol deliberately contains only engine-level data. It does not
 * depend on a particular client, renderer, transport implementation, or game.
 */
public final class Protocol {
    private static final int MAX_FRAME_SIZE = 1 << 20; // 1 MiB

    private Protocol() {}

    public static void write(OutputStream output, Packet packet) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(64);
        DataOutputStream data = new DataOutputStream(frame);

        data.writeByte(packet.type());
        switch (packet) {
            case Hello h -> writeString(data, h.name());
            case Welcome w -> data.writeLong(w.entityId());
            case Input i -> {
                data.writeDouble(i.dx());
                data.writeDouble(i.dy());
                data.writeInt(i.actions());
            }
            case Snapshot s -> {
                List<Snapshot.EntityState> entities = s.entities();
                data.writeInt(entities.size());
                for (Snapshot.EntityState e : entities) {
                    data.writeLong(e.id());
                    data.writeDouble(e.x());
                    data.writeDouble(e.y());
                    data.writeDouble(e.elevation());
                }
            }
        }
        data.flush();

        byte[] bytes = frame.toByteArray();
        if (bytes.length > MAX_FRAME_SIZE) {
            throw new IOException("Packet is too large: " + bytes.length);
        }

        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    public static Packet read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int frameLength = in.readInt();
        if (frameLength <= 0 || frameLength > MAX_FRAME_SIZE) {
            throw new IOException("Invalid packet length: " + frameLength);
        }

        byte[] frame = new byte[frameLength];
        in.readFully(frame);

        DataInputStream data = new DataInputStream(new ByteArrayInputStream(frame));
        byte type = data.readByte();

        return switch (type) {
            case 1 -> new Hello(readString(data));
            case 2 -> new Welcome(data.readLong());
            case 3 -> new Input(data.readDouble(), data.readDouble(), data.readInt());
            case 4 -> readSnapshot(data);
            default -> throw new IOException("Unknown packet type: " + type);
        };
    }

    private static Snapshot readSnapshot(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 100_000) {
            throw new IOException("Invalid snapshot entity count: " + count);
        }

        List<Snapshot.EntityState> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entities.add(new Snapshot.EntityState(
                in.readLong(),
                in.readDouble(),
                in.readDouble(),
                in.readDouble()
            ));
        }
        return new Snapshot(entities);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            throw new IOException("String value cannot be null");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) {
            throw new IOException("String is too long: " + bytes.length);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 65_535) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
