package rpg.engine.pak;

import rpg.engine.network.PakChunk;
import rpg.engine.network.PakList;
import rpg.engine.network.Protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side streaming of {@code .pak} asset packs during the connection handshake.
 * The client is expected to re-skip packs it already has cached by {@code name}+{@code size},
 * so packs are always sent in full (read loops are cheap when the client discards chunks).
 */
public final class PakStreamer {
    public static final int CHUNK_BYTES = 64 * 1024;

    private PakStreamer() {}

    /** Writes the full handshake: an (possibly empty) {@link PakList} then {@link PakChunk} slices. */
    public static void send(OutputStream out, List<Path> pakFiles) throws IOException {
        List<PakList.PakSeq> seqs = new ArrayList<>(pakFiles.size());
        for (Path f : pakFiles) seqs.add(new PakList.PakSeq(f.getFileName().toString(), (int) Files.size(f)));
        Protocol.write(out, new PakList(seqs));

        for (Path f : pakFiles) {
            String name = f.getFileName().toString();
            long size = Files.size(f);
            int offset = 0;
            try (var in = Files.newInputStream(f)) {
                byte[] buf = new byte[CHUNK_BYTES];
                while (offset < size) {
                    int n = in.read(buf);
                    if (n < 0) throw new IOException("truncated pak file: " + f);
                    byte[] data = n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
                    Protocol.write(out, new PakChunk(name, offset, data));
                    offset += n;
                }
            }
        }
    }
}