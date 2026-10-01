package rpg.engine.map;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Loads and validates the server's collection of independently simulated regions. */
public final class RegionCatalog {
    private final Map<String, RMap> regions;

    private RegionCatalog(Map<String, RMap> regions) {
        this.regions = Map.copyOf(regions);
    }

    public static RegionCatalog load(Path directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("directory is null");
        if (!Files.isDirectory(directory)) throw new IOException("region directory not found: " + directory);
        Map<String, RMap> loaded = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".rmap")).sorted().toList()) {
                String file = path.getFileName().toString();
                String id = file.substring(0, file.length() - ".rmap".length()).trim().toLowerCase(Locale.ROOT);
                if (id.isBlank()) throw new IOException("blank region id: " + path);
                if (!id.matches("[a-z0-9][a-z0-9._-]*")) throw new IOException("invalid region id '" + id + "'");
                if (loaded.putIfAbsent(id, RMapIO.read(path)) != null) throw new IOException("duplicate region id: " + id);
            }
        }
        for (var entry : loaded.entrySet()) {
            Set<String> portalIds = new HashSet<>();
            for (MapPortal portal : entry.getValue().portals()) {
                if (!portalIds.add(portal.id())) throw new IOException("duplicate portal id '" + portal.id() + "' in region " + entry.getKey());
                String target = normalizeId(portal.targetRegion());
                if (!loaded.containsKey(target)) throw new IOException("portal '" + portal.id() + "' in region " + entry.getKey() + " targets missing region '" + target + "'");
            }
        }
        return new RegionCatalog(loaded);
    }

    public Optional<RMap> find(String id) { return Optional.ofNullable(regions.get(normalizeId(id))); }
    public RMap require(String id) { return find(id).orElseThrow(() -> new NoSuchElementException("unknown region: " + id)); }
    public Set<String> ids() { return Collections.unmodifiableSet(regions.keySet()); }
    public Map<String, RMap> asMap() { return Collections.unmodifiableMap(regions); }

    private static String normalizeId(String id) {
        return Objects.requireNonNull(id, "region id").trim().toLowerCase(Locale.ROOT);
    }
}
