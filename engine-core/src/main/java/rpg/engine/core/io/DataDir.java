package rpg.engine.core.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared desktop data-directory convention used by the dedicated server, the desktop client's
 * built-in local server, the map/script editors and the Swing server admin.
 *
 * <pre>
 *   ~/.openrpgator/data/
 *       maps/   *.rmap
 *       paks/   *.pak
 * </pre>
 *
 * Maps and asset packs dropped into this directory are picked up by the server, streamed to
 * clients and offered in the editors without needing full absolute paths. Android uses its own
 * equivalent layout under the app storage root (see {@code AppStorage}).
 */
public final class DataDir {
    private DataDir() {}

    public static Path root() {
        return Path.of(System.getProperty("user.home"), ".openrpgator", "data");
    }

    public static Path maps() { return root().resolve("maps"); }
    public static Path paks() { return root().resolve("paks"); }

    /** Creates the {maps,paks} subdirectories; safe to call repeatedly. */
    public static void ensure() throws IOException {
        Files.createDirectories(maps());
        Files.createDirectories(paks());
    }

    /** Sorted {@code *.rmap} paths from the shared maps directory. */
    public static List<Path> mapFiles() { return listIn(maps(), ".rmap"); }

    /** Sorted {@code *.pak} paths from the shared paks directory. */
    public static List<Path> pakFiles() { return listIn(paks(), ".pak"); }

    /**
     * Resolves a bare map name against the shared maps directory. Absolute paths and paths
     * that already exist relative to the working directory are returned as-is.
     */
    public static Path resolveMap(String ref) { return resolveIn(maps(), ref); }

    /** Same as {@link #resolveMap(String)} but against the shared paks directory. */
    public static Path resolvePak(String ref) { return resolveIn(paks(), ref); }

    public static Path resolveIn(Path dir, String ref) {
        if (ref == null || ref.isBlank()) return null;
        Path p = Path.of(ref);
        if (p.isAbsolute() || ref.contains("/") || ref.contains("\\") || Files.exists(p))
            return p;
        return dir.resolve(p);
    }

    /** Sorted paths (case-insensitive extension) directly inside {@code dir}. */
    public static List<Path> listIn(Path dir, String ext) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        String suffix = ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (suffix.isEmpty() || name.endsWith(suffix)) out.add(p);
            }
        } catch (IOException ignored) {
        }
        out.sort(Path::compareTo);
        return out;
    }
}