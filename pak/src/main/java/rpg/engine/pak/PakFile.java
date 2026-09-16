package rpg.engine.pak;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Binary asset container for client visuals (isometric tiles, sprites, UI panels).
 *
 * The format stores decoded RGBA rasters (not compressed PNG), so clients never need an
 * image decoder. Each entry has a namespaced key, e.g. {@code tile/grass}, {@code sprite/guard},
 * {@code ui/panel}. Payload is raw RGBA, row-major, top row first.
 *
 * <pre>
 *   'P' 'K' '0' '1'                      magic
 *   int32  version                 = 1
 *   int32  entryCount
 *   entryCount × IndexEntry:
 *     int32 nameLen + UTF-8 name          (e.g. "tile/grass")
 *     int32 width, int32 height
 *     int32 dataLen, int32 dataOffset     (offset from start of file)
 *   payload: dataLen bytes of RGBA raster per entry
 * </pre>
 */
public final class PakFile {
    private static final byte[] MAGIC = {'P', 'K', '0', '1'};
    private static final int VERSION = 1;

    public static final int HEADER_BASE = 12;

    public record Entry(String name, int width, int height, int offset, int length) {
        public boolean isTile() { return name.startsWith("tile/"); }
        public boolean isSprite() { return name.startsWith("sprite/"); }
        public boolean isUi() { return name.startsWith("ui/"); }
    }

    private final String sourceName;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private PakFile(String sourceName, List<Entry> entries) {
        this.sourceName = sourceName;
        this.entries = List.copyOf(entries);
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : entries) map.put(e.name(), e);
        this.byName = Collections.unmodifiableMap(map);
    }

    /** Reads only the index; rasters stay on disk until {@link #loadImage(Path, Entry)}. */
    public static PakFile readIndex(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            DataInputStream data = new DataInputStream(in);
            for (byte b : MAGIC) if (data.readByte() != b) throw new IOException("bad PAK magic");
            if (data.readInt() != VERSION) throw new IOException("unsupported PAK version");
            int count = data.readInt();
            if (count < 0 || count > 100000) throw new IOException("invalid PAK entry count: " + count);
            List<Entry> parsed = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = readString(data);
                int w = data.readInt(), h = data.readInt(), len = data.readInt(), off = data.readInt();
                if (w < 0 || h < 0 || len < 0 || off < 0) throw new IOException("invalid PAK entry: " + name);
                if (len != w * h * 4) throw new IOException("PAK raster size mismatch: " + name);
                parsed.add(new Entry(name, w, h, off, len));
            }
            return new PakFile(path.toString(), parsed);
        }
    }

    public static PakImage loadImage(Path path, Entry e) throws IOException {
        return new PakImage(e.width(), e.height(), readRaster(path, e));
    }

    public static byte[] readRaster(Path path, Entry e) throws IOException {
        byte[] raw = new byte[e.length()];
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(e.offset());
            raf.readFully(raw);
        }
        return raw;
    }

    public static void write(Path path, Map<String, PakImage> named) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) { write(out, named); }
    }

    public static void write(OutputStream stream, Map<String, PakImage> named) throws IOException {
        List<Map.Entry<String, PakImage>> all = new ArrayList<>(named.entrySet());
        all.sort(Comparator.comparing(Map.Entry::getKey));
        long indexSize = 0;
        for (var e : all) indexSize += e.getKey().getBytes(StandardCharsets.UTF_8).length + 20;
        long dataOffset = HEADER_BASE + indexSize;

        DataOutputStream out = new DataOutputStream(stream);
        out.write(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(all.size());
        for (var e : all) {
            byte[] name = makeName(e.getKey());
            out.writeInt(name.length);
            out.write(name);
            PakImage img = e.getValue();
            out.writeInt(img.width()); out.writeInt(img.height());
            out.writeInt(img.bytes()); out.writeInt((int) dataOffset);
            dataOffset += img.bytes();
        }
        for (var e : all) out.write(e.getValue().rgba());
        out.flush();
    }

    private static byte[] makeName(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    private static String readString(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 65535) throw new IOException("invalid PAK name length");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public String sourceName() { return sourceName; }
    public List<Entry> entries() { return entries; }
    public Entry entry(String name) { return byName.get(name); }
    public boolean has(String name) { return byName.containsKey(name); }
    public PakImage image(Path path, String name) throws IOException {
        Entry e = byName.get(name);
        return e == null ? null : loadImage(path, e);
    }

    /** Sorted resource keys matching {@code prefix} ("tile/", "sprite/", "ui/"). */
    public List<String> namesByPrefix(String prefix) {
        return entries.stream().map(Entry::name).filter(n -> n.startsWith(prefix)).sorted().toList();
    }

    public int totalPayloadBytes() {
        return entries.stream().mapToInt(Entry::length).sum();
    }

    @Override public String toString() {
        return "PakFile{" + sourceName + ", " + entries.size() + " entries}";
    }
}