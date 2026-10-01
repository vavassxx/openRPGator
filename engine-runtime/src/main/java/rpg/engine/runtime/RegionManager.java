package rpg.engine.runtime;

import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.MapPortal;
import rpg.engine.map.RegionCatalog;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Owns the set of independently simulated region runtimes for a server instance.
 *
 * <p>This class deliberately contains no networking and no player/session state. A host may
 * therefore use it from the dedicated server, an embedded Android server, tests, or tooling.
 * Entity migration is performed as an atomic detach/adopt operation while the caller owns the
 * server/world lock.
 */
public final class RegionManager {
    private final Map<String, RegionRuntime> regions = new LinkedHashMap<>();

    public Collection<RegionRuntime> all() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(regions.keySet());
    }

    public Optional<RegionRuntime> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(regions.get(normalize(id)));
    }

    public RegionRuntime require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unknown region: " + id));
    }

    public void clear() { regions.clear(); }

    /** Loads every catalog entry and assigns a non-overlapping entity-id namespace to each region. */
    public void load(RegionCatalog catalog) throws IOException {
        Objects.requireNonNull(catalog, "catalog");
        regions.clear();
        long index = 1;
        for (RegionCatalog.Entry entry : catalog.all()) {
            if (index > 0xFFFFL) throw new IllegalArgumentException("too many regions for entity namespace");
            RegionRuntime runtime = new RegionRuntime(entry.id(), index << 48);
            runtime.loadMap(entry.path());
            regions.put(runtime.id(), runtime);
            index++;
        }
    }

    /** Adds a single already-loaded runtime. Primarily useful for embedded hosts and tests. */
    public void add(RegionRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (regions.putIfAbsent(runtime.id(), runtime) != null)
            throw new IllegalArgumentException("duplicate region: " + runtime.id());
    }

    public void tick(BiConsumer<String, RegionRuntime> beforeTick) {
        for (Map.Entry<String, RegionRuntime> entry : regions.entrySet()) {
            if (beforeTick != null) beforeTick.accept(entry.getKey(), entry.getValue());
            entry.getValue().tick();
        }
    }

    /**
     * Moves an entity between region worlds. Caller must hold the host's world lock.
     * Only the region-local ECS representation moves; persistent player/session state remains host-owned.
     */
    public boolean migrate(EntityId entity, String fromId, String toId, WorldPosition target) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(target, "target");
        String from = normalize(fromId);
        String to = normalize(toId);
        if (from.equals(to)) return false;
        RegionRuntime source = require(from);
        RegionRuntime destination = require(to);

        var sourceRegistry = source.world().entities();
        if (!sourceRegistry.entities().contains(entity)) return false;

        var name = sourceRegistry.get(entity, rpg.engine.core.component.Name.class).orElse(null);
        var prefab = sourceRegistry.get(entity, rpg.engine.core.component.Prefab.class).orElse(null);
        var scale = sourceRegistry.get(entity, rpg.engine.core.component.Scale.class).orElse(null);
        var collider = sourceRegistry.get(entity, rpg.engine.core.component.Collider.class).orElse(null);

        sourceRegistry.destroy(entity);
        var destinationRegistry = destination.world().entities();
        destinationRegistry.adopt(entity);
        if (name != null) destinationRegistry.set(entity, name);
        if (prefab != null) destinationRegistry.set(entity, prefab);
        if (scale != null) destinationRegistry.set(entity, scale);
        if (collider != null) destinationRegistry.set(entity, collider);
        destinationRegistry.set(entity, new rpg.engine.core.component.Transform(target, 0));
        return true;
    }

    public Optional<MapPortal> portalAt(String sourceId, WorldPosition position) {
        RegionRuntime source = require(sourceId);
        var map = source.map();
        return map == null ? Optional.empty() : map.portals().stream()
                .filter(portal -> distanceSquared(portal.position(), position) <= portal.radius() * portal.radius())
                .findFirst();
    }

    private static double distanceSquared(WorldPosition a, WorldPosition b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return dx * dx + dy * dy;
    }

    private static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("region id is null");
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
