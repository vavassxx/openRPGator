package rpg.engine.render;
public interface Renderer extends AutoCloseable {void begin(int width,int height);void tile(int x,int y,int id);void sprite(double x,double y,double elevation,int resource);void end();@Override default void close(){} }
