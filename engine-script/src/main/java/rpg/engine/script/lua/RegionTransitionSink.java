package rpg.engine.script.lua;

/** Host callback used by Lua to request a player transition between regions. */
@FunctionalInterface
public interface RegionTransitionSink {
    boolean transition(String sourceRegion, long playerEntityId, String targetRegion,
                       double x, double y, double z);
}
