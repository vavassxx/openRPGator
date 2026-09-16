package rpg.engine.server;

import rpg.engine.core.io.DataDir;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds a {@link ServerHost.Config} from user input plus the shared desktop data directory
 * (default {@code ~/.openrpgator/data}). Both the CLI ({@link ServerMain}) and the Swing server
 * admin resolve through this, so the server, the local server inside the client and the editors
 * all agree on where maps and packs live.
 *
 * <pre>
 *   --map town.rmap          bare name  → data/maps/town.rmap
 *   --map /abs/path         absolute path is used as-is
 *   (no --map)              → the single *.rmap in data/maps (or none)
 *   --pak basic.pak         bare name  → data/paks/basic.pak
 *   --pak some/dir          directory   → all *.pak inside it
 *   (no --pak)              → all *.pak in data/paks
 * </pre>
 */
public final class ServerConfig {
    private ServerConfig() {}

    /** What the operator asked for on the command line or in the admin UI. */
    public record Criteria(boolean mapGiven, String mapRef,
                           boolean paksGiven, List<String> pakRefs,
                           int port) {
        public Criteria { if (pakRefs == null) pakRefs = List.of(); }
    }

    public static ServerHost.Config resolve(Path dataDir, Criteria c, Consumer<String> warn) {
        Path mapsDir = dataDir.resolve("maps");
        Path paksDir = dataDir.resolve("paks");

        Path map = null;
        if (c.mapGiven && c.mapRef != null && !c.mapRef.isBlank()) {
            map = DataDir.resolveIn(mapsDir, c.mapRef);
        } else if (!c.mapGiven) {
            List<Path> ms = DataDir.listIn(mapsDir, ".rmap");
            if (ms.size() == 1) {
                map = ms.get(0);
            } else if (ms.size() > 1) {
                warn.accept("Multiple maps under " + mapsDir + "; pass --map to choose: "
                        + names(ms));
            }
        }

        List<Path> paks;
        if (c.paksGiven && !c.pakRefs.isEmpty()) {
            List<Path> out = new ArrayList<>();
            for (String r : c.pakRefs) {
                Path p = DataDir.resolveIn(paksDir, r);
                if (p == null || !Files.exists(p)) {
                    warn.accept("Pak not found: " + r);
                    continue;
                }
                if (Files.isDirectory(p)) out.addAll(DataDir.listIn(p, ".pak"));
                else out.add(p);
            }
            paks = out;
        } else {
            paks = DataDir.listIn(paksDir, ".pak");
        }

        return new ServerHost.Config(map, paks, c.port);
    }

    private static String names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).toList().toString();
    }
}