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
 *       host/   *.rmap   *.lua   *.pak
 * </pre>
 *
 * All server files live in the single {@code host} folder: the map the server auto-discovers,
 * the Lua scripts it pulls in (resolved next to the map) and the asset packs it streams to
 * clients. Maps and packs dropped into it are picked up automatically; there is no manual map
 * selection anymore. Android uses its own equivalent layout under the app storage root (see
 * {@code AppStorage}).
 */
public final class DataDir {
    private DataDir() {}

    public static Path root() {
        return Path.of(System.getProperty("user.home"), ".openrpgator", "data");
    }

    /** The single folder holding all server files: maps, scripts and asset packs. */
    public static Path host() { return root().resolve("host"); }

    /** Creates the host folder; safe to call repeatedly. */
    public static void ensure() throws IOException {
        Files.createDirectories(host());
        migrateLegacy();
    }

    /**
     * One-time migration for installs that still use the pre-0.4 layout
     * ({@code data/maps}, {@code data/paks}): their files are moved into {@code host}
     * so existing content keeps working without a manual move.
     */
    private static void migrateLegacy() throws IOException {
        if (Files.isDirectory(host()) && hostContainsData()) return;
        Path maps = root().resolve("maps");
        Path paks = root().resolve("paks");
        if (Files.isDirectory(maps)) moveContent(maps, host());
        if (Files.isDirectory(paks)) moveContent(paks, host());
    }

    private static boolean hostContainsData() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(host())) {
            return ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static void moveContent(Path from, Path to) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(from)) {
            for (Path p : ds) {
                Path target = to.resolve(p.getFileName().toString());
                if (!Files.exists(target)) {
                    try {
                        Files.move(p, target);
                    } catch (IOException ignored) {
                        // a file may be locked or in use; leave it where it is
                    }
                }
            }
        }
    }

    /** Sorted {@code *.rmap} paths from the host folder. */
    public static List<Path> hostMaps() { return listIn(host(), ".rmap"); }

    /** Sorted {@code *.pak} paths from the host folder. */
    public static List<Path> hostPaks() { return listIn(host(), ".pak"); }

    /**
     * The single automatically-selected host map. With zero or multiple maps a warning is
     * delivered (via {@code warn}, may be null) and {@code null} is returned for zero.
     */
    public static Path autoMap(java.util.function.Consumer<String> warn) {
        List<Path> maps = hostMaps();
        if (maps.size() == 1) return maps.get(0);
        java.util.function.Consumer<String> w = warn == null ? s -> {} : warn;
        if (maps.isEmpty()) {
            w.accept("No map in " + host() + " — server starts without a map");
            return null;
        }
        w.accept("Multiple maps in " + host() + "; using " + maps.get(0).getFileName());
        return maps.get(0);
    }

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
                if (Files.isDirectory(p)) continue;
                String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (suffix.isEmpty() || name.endsWith(suffix)) out.add(p);
            }
        } catch (IOException ignored) {
        }
        out.sort(Path::compareTo);
        return out;
    }
}