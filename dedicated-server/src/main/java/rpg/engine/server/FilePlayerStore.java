package rpg.engine.server;

import rpg.engine.script.PlayerStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Simple atomic per-user JSON files. User names are encoded as SHA-256 file names. */
public final class FilePlayerStore implements PlayerStore {
    private final Path dir;
    public FilePlayerStore(Path dir) { this.dir = dir; try { Files.createDirectories(dir); } catch (IOException e) { throw new UncheckedIOException(e); } }
    private Path file(String key) {
        try { var md=java.security.MessageDigest.getInstance("SHA-256"); byte[] d=md.digest(key.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte x:d)b.append(String.format("%02x",x)); return dir.resolve(b+".json"); }
        catch(Exception e){ throw new IllegalStateException(e); }
    }
    @Override public synchronized String load(String key) {
        try { Path p=file(key); if(!Files.isRegularFile(p)) return null; try (InputStream in = Files.newInputStream(p)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); } }
        catch(IOException e){ System.err.println("[save] load failed: "+e.getMessage()); return null; }
    }
    @Override public synchronized void save(String key, String json) {
        try { Path p=file(key), tmp=dir.resolve(p.getFileName()+".tmp"); Files.writeString(tmp,json,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING); try { Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); } catch(AtomicMoveNotSupportedException e){ Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING); } }
        catch(IOException e){ System.err.println("[save] write failed: "+e.getMessage()); }
    }
}
