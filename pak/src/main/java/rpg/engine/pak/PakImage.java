package rpg.engine.pak;

/**
 * RGBA raster resource: width × height pixels, 4 bytes per pixel, row-major, top row first.
 */
public record PakImage(int width, int height, byte[] rgba) {
    public PakImage {
        if (rgba == null) throw new IllegalArgumentException("rgba is null");
        if (rgba.length != width * height * 4) throw new IllegalArgumentException("rgba length mismatch");
    }
    public int bytes() { return rgba.length; }
}