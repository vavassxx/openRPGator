package rpg.engine.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Java 17/Android-compatible length-framed binary protocol. */
public final class Protocol {
    private static final int MAX_FRAME_SIZE = 1 << 20;
    private Protocol() {}

    public static void write(OutputStream output, Packet packet) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(64);
        DataOutputStream data = new DataOutputStream(frame);
        data.writeByte(packet.type());
        if (packet instanceof Hello) {
            writeString(data, ((Hello) packet).name());
        } else if (packet instanceof Welcome) {
            data.writeLong(((Welcome) packet).entityId());
        } else if (packet instanceof Input) {
            Input p = (Input) packet;
            data.writeDouble(p.dx()); data.writeDouble(p.dy()); data.writeInt(p.actions());
        } else if (packet instanceof Snapshot) {
            Snapshot p = (Snapshot) packet;
            data.writeInt(p.entities().size());
            for (Snapshot.EntityState e : p.entities()) {
                data.writeLong(e.id()); data.writeDouble(e.x());
                data.writeDouble(e.y()); data.writeDouble(e.elevation());
            }
        } else throw new IOException("Unsupported packet: " + packet.getClass());
        data.flush();
        byte[] bytes = frame.toByteArray();
        if (bytes.length > MAX_FRAME_SIZE) throw new IOException("Packet is too large: " + bytes.length);
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(bytes.length); out.write(bytes); out.flush();
    }

    public static Packet read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int length = in.readInt();
        if (length <= 0 || length > MAX_FRAME_SIZE) throw new IOException("Invalid packet length: " + length);
        byte[] frame = new byte[length]; in.readFully(frame);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(frame));
        switch (data.readByte()) {
            case 1: return new Hello(readString(data));
            case 2: return new Welcome(data.readLong());
            case 3: return new Input(data.readDouble(), data.readDouble(), data.readInt());
            case 4: return readSnapshot(data);
            default: throw new IOException("Unknown packet type");
        }
    }

    private static Snapshot readSnapshot(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 100000) throw new IOException("Invalid snapshot entity count: " + count);
        List<Snapshot.EntityState> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entities.add(new Snapshot.EntityState(in.readLong(), in.readDouble(), in.readDouble(), in.readDouble()));
        return new Snapshot(entities);
    }
    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) throw new IOException("String value cannot be null");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) throw new IOException("String is too long");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 65535) throw new IOException("Invalid string length");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
