package rpg.engine.runtime;

import rpg.engine.core.ecs.EntityId;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.MapPortal;
import rpg.engine.map.RegionCatalog;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/** Owns independently simulated region runtimes. Persistent player state belongs to the host. */
public final class RegionManager {
    private final Map<String, RegionRuntime> regions = new LinkedHashMap<>();

    public Collection<RegionRuntime> all() { return Collections.unmodifiableCollection(regions.values()); }
    public Set<String> ids() { return Collections.unmodifiableSet(regions.keySet()); }
    public Optional<RegionRuntime> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(regions.get(normalize(id)));
    }
    public RegionRuntime require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unknown region: " + id));
    }
    public void clear() { regions.clear(); }

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
     * Moves only the region-local ECS representation of a player.
     * Persistent state (inventory, progression, quests, etc.) is host/script owned and is not
     * inferred or copied by this method. The host may reconstruct whatever components the target
     * region requires after the move.
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

        // Preserve only the stable entity identity. The host/script layer owns player state and
        // decides which region-local components need to be reconstructed in the destination.
        sourceRegistry.destroy(entity);
        try {
            var destinationRegistry = destination.world().entities();
            destinationRegistry.adopt(entity);
            destinationRegistry.set(entity, new rpg.engine.core.component.Transform(target, 0));
            return true;
        } catch (RuntimeException failure) {
            var destinationRegistry = destination.world().entities();
            if (destinationRegistry.entities().contains(entity)) destinationRegistry.destroy(entity);
            sourceRegistry.adopt(entity);
            throw failure;
        }
    }

    public Optional<MapPortal> portalAt(String sourceId, WorldPosition position) {
        RegionRuntime source = require(sourceId);
        var map = source.map();
        return map == null ? Optional.empty() : map.portals().stream()
                .filter(portal -> distanceSquared(portal.position(), position) <= portal.radius() * portal.radius())
                .findFirst();
    }

    private static double distanceSquared(WorldPosition a, WorldPosition b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y();
        return dx * dx + dy * dy;
    }
    private static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("region id is null");
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
