package rpg.engine.map;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.core.util.VarInts;

/** Binary .rmap serializer. The stream API is also used by the Android editor. */
public final class RMapIO {
    /** v1: entity = id/prefab/position/script; v2 adds entity scale. */
    private static final int VERSION = 2;
    private static final int VERSION_1 = 1;
    private static final byte[] MAGIC = {'R','M','A','P'};
    private RMapIO() {}

    public static void write(RMap map, Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) { write(map, out); }
    }

    public static void write(RMap map, OutputStream stream) throws IOException {
        DataOutputStream out = stream instanceof DataOutputStream d ? d : new DataOutputStream(new BufferedOutputStream(stream));
        out.write(MAGIC); out.writeInt(VERSION);
        str(out, map.name()); out.writeInt(map.tileSize()); out.writeInt(map.width()); out.writeInt(map.height());
        out.writeInt(map.layers().size());
        for (var l : map.layers()) {
            str(out, l.name()); out.writeBoolean(l.collision()); out.writeInt(l.width()); out.writeInt(l.height());
            for (int t : l.tiles()) VarInts.write(out, t);
        }
        out.writeInt(map.entities().size());
        for (var e : map.entities()) {
            str(out, e.id()); str(out, e.prefab());
            out.writeDouble(e.position().x()); out.writeDouble(e.position().y()); out.writeDouble(e.position().elevation());
            str(out, e.script() == null ? "" : e.script());
            out.writeDouble(e.scale());
        }
        out.flush();
    }

    public static RMap read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) { return read(in); }
    }

    public static RMap read(InputStream stream) throws IOException {
        DataInputStream in = stream instanceof DataInputStream d ? d : new DataInputStream(new BufferedInputStream(stream));
        for (byte b : MAGIC) if (in.readByte() != b) throw new IOException("bad RMAP");
        int version = in.readInt();
        if (version != VERSION_1 && version != VERSION) throw new IOException("unsupported RMAP version: " + version);
        String n = str(in); int ts = in.readInt(), w = in.readInt(), h = in.readInt();
        var ls = new ArrayList<TileLayer>(); int layerCount = in.readInt();
        for (int i = 0; i < layerCount; i++) {
            String ln = str(in); boolean c = in.readBoolean(); int lw = in.readInt(), lh = in.readInt();
            int[] t = new int[lw * lh]; for (int j = 0; j < t.length; j++) t[j] = (int) VarInts.read(in);
            ls.add(new TileLayer(ln, lw, lh, t, c));
        }
        var es = new ArrayList<MapEntity>(); int entityCount = in.readInt();
        for (int i = 0; i < entityCount; i++) {
            MapEntity e = new MapEntity(str(in), str(in),
                    new WorldPosition(in.readDouble(), in.readDouble(), in.readDouble()),
                    emptyToNull(str(in)));
            if (version >= 2) e = new MapEntity(e.id(), e.prefab(), e.position(), e.script(), in.readDouble());
            es.add(e);
        }
        return new RMap(n, ts, w, h, ls, es);
    }

    private static void str(DataOutput out, String s) throws IOException { byte[] b = s.getBytes(StandardCharsets.UTF_8); VarInts.write(out, b.length); out.write(b); }
    private static String str(DataInput in) throws IOException { int n = (int) VarInts.read(in); if (n < 0 || n > 16_000_000) throw new IOException("invalid string length"); byte[] b = new byte[n]; in.readFully(b); return new String(b, StandardCharsets.UTF_8); }
    private static String emptyToNull(String s) { return s.isEmpty() ? null : s; }
}
