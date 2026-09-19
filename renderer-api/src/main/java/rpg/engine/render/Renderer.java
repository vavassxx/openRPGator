package rpg.engine.render;

/** Sprite rendering is addressed by NAME (a {@code sprite/*} pak key), never by array index. */
public interface Renderer extends AutoCloseable {
    void begin(int width, int height);
    void tile(int x, int y, int id);
    void sprite(double x, double y, double elevation, String sprite, double scale);
    void end();
    @Override default void close() {}
}