package rpg.engine.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 17/Android-compatible length-framed binary protocol.
 *
 * Packet type ids:
 *   1  Hello(name)
 *   2  Welcome(entityId)
 *   3  Input(dx, dy, actions)
 *   4  Snapshot(entities)
 *   5  Notify(text)
 *   6  Dialog(dialogId, text, choices[])
 *   7  DialogResponse(dialogId, choice)
 *   8  PakList(packs[])
 *   9  PakChunk(name, offset, data)
 *   10 UiLayout(kind, dialogId, json) — layout + strings UI document
 */
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
        } else if (packet instanceof Input p) {
            data.writeDouble(p.dx()); data.writeDouble(p.dy()); data.writeInt(p.actions());
        } else if (packet instanceof Snapshot p) {
            data.writeInt(p.entities().size());
            for (Snapshot.EntityState e : p.entities()) {
                data.writeLong(e.id()); data.writeDouble(e.x());
                data.writeDouble(e.y()); data.writeDouble(e.elevation());
                data.writeInt(e.resource());
            }
        } else if (packet instanceof Notify p) {
            writeString(data, p.text());
        } else if (packet instanceof Dialog p) {
            data.writeLong(p.dialogId());
            writeString(data, p.text());
            List<String> choices = p.choices();
            data.writeInt(choices.size());
            for (String c : choices) writeString(data, c);
        } else if (packet instanceof DialogResponse p) {
            data.writeLong(p.dialogId());
            data.writeInt(p.choice());
        } else if (packet instanceof PakList p) {
            data.writeInt(p.packs().size());
            for (PakList.PakSeq seq : p.packs()) {
                writeString(data, seq.name());
                data.writeInt(seq.sizeBytes());
            }
        } else if (packet instanceof PakChunk p) {
            writeString(data, p.name());
            data.writeInt(p.offset());
            data.writeInt(p.data().length);
            data.write(p.data());
        } else if (packet instanceof UiLayout p) {
            writeString(data, p.kind());
            data.writeLong(p.dialogId());
            writeString(data, p.json());
        } else {
            throw new IOException("Unsupported packet: " + packet.getClass());
        }
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
            case 1 -> { return new Hello(readString(data)); }
            case 2 -> { return new Welcome(data.readLong()); }
            case 3 -> { return new Input(data.readDouble(), data.readDouble(), data.readInt()); }
            case 4 -> { return readSnapshot(data); }
            case 5 -> { return new Notify(readString(data)); }
            case 6 -> { return readDialog(data); }
            case 7 -> { return new DialogResponse(data.readLong(), data.readInt()); }
            case 8 -> { return readPakList(data); }
            case 9 -> { return readPakChunk(data); }
            case 10 -> { return new UiLayout(readString(data), data.readLong(), readString(data)); }
            default -> throw new IOException("Unknown packet type");
        }
    }

    private static Snapshot readSnapshot(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 100000) throw new IOException("Invalid snapshot entity count: " + count);
        List<Snapshot.EntityState> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            entities.add(new Snapshot.EntityState(in.readLong(), in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readInt()));
        return new Snapshot(entities);
    }

    private static PakList readPakList(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 128) throw new IOException("Invalid pak list count: " + n);
        List<PakList.PakSeq> packs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) packs.add(new PakList.PakSeq(readString(in), in.readInt()));
        return new PakList(packs);
    }

    private static PakChunk readPakChunk(DataInputStream in) throws IOException {
        String name = readString(in);
        int offset = in.readInt();
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME_SIZE) throw new IOException("Invalid pak chunk length: " + len);
        byte[] data = new byte[len];
        in.readFully(data);
        return new PakChunk(name, offset, data);
    }

    private static Dialog readDialog(DataInputStream in) throws IOException {
        long id = in.readLong();
        String text = readString(in);
        int n = in.readInt();
        if (n < 0 || n > 256) throw new IOException("Invalid dialog choice count: " + n);
        List<String> choices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) choices.add(readString(in));
        return new Dialog(id, text, choices);
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