package rpg.engine.pak;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Packer CLI: converts a tree of PNG rasters into a {@link PakFile}.
 *
 * Layout convention: {@code <root>/tile/*.png -> "tile/<name>"},
 * {@code <root>/sprite/*.png -> "sprite/<name>"}, {@code <root>/ui/*.png -> "ui/<name>"}.
 *
 * Usage:
 *   PakTool pack --root assets-src --out assets/basic.pak
 *   PakTool list  assets/basic.pak
 *   PakTool info  assets/basic.pak          (per-entry raster bytes)
 */
public final class PakTool {
    private static final int BYTES_MAX = 256 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { usage(); return; }
        switch (args[0]) {
            case "pack" -> pack(args);
            case "list" -> list(args);
            case "info" -> info(args);
            default -> usage();
        }
    }

    private static void pack(String[] args) throws IOException {
        Path root = null;
        Path out = null;
        int maxSize = 64; // default: reject suspiciously large rasters (64 MiB)
        int resizeW = 0, resizeH = 0;
        Map<String, int[]> namespaceResize = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--max-miB" -> maxSize = Integer.parseInt(args[++i]);
                case "--resize" -> {
                    String arg = args[++i];
                    int eq = arg.indexOf('=');
                    if (eq > 0) {
                        // per-namespace resize, e.g. --resize sprite=32x64 --resize tile=32x16
                        String[] wh = arg.substring(eq + 1).split("[xX]", 2);
                        namespaceResize.put(arg.substring(0, eq),
                                new int[]{Integer.parseInt(wh[0]), Integer.parseInt(wh[1])});
                    } else {
                        // plain resize applies to every namespace (legacy behaviour)
                        String[] wh = arg.split("[xX]", 2);
                        resizeW = Integer.parseInt(wh[0]);
                        resizeH = Integer.parseInt(wh[1]);
                    }
                }
                default -> { usage(); return; }
            }
        }
        if (root == null || out == null) { usage(); return; }

        Map<String, PakImage> named = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith(".png") && !rel.endsWith(".PNG")) continue;
                String key = rel.substring(0, rel.length() - 4);
                if (!key.startsWith("tile/") && !key.startsWith("sprite/") && !key.startsWith("ui/")) {
                    System.err.println("skip (not in tile/sprite/ui/): " + p);
                    continue;
                }
                int[] ns = namespaceResize.get(key.substring(0, key.indexOf('/')));
                int rw = ns != null ? ns[0] : resizeW;
                int rh = ns != null ? ns[1] : resizeH;
                PakImage img = decode(p, rw, rh);
                if (img.bytes() > (long) maxSize * 1024 * 1024)
                    throw new IOException("raster too large: " + p + " (" + img.bytes() + " bytes)");
                named.put(key, img);
            }
        }
        if (named.isEmpty()) { System.err.println("no rasters found under " + root); return; }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        PakFile.write(out, named);
        System.out.println("packed " + named.size() + " rasters -> " + out + " (" + Files.size(out) + " bytes)");
    }

    private static PakImage decode(Path p, int resizeW, int resizeH) throws IOException {
        BufferedImage img = ImageIO.read(p.toFile());
        if (img == null) throw new IOException("not a decodable image: " + p);
        if (resizeW > 0 && resizeH > 0 && (img.getWidth() != resizeW || img.getHeight() != resizeH)) {
            BufferedImage scaled = new BufferedImage(resizeW, resizeH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(img, 0, 0, resizeW, resizeH, null);
            g.dispose();
            img = scaled;
        }
        int w = img.getWidth(), h = img.getHeight();
        byte[] rgba = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                rgba[i++] = (byte) ((argb >> 16) & 0xFF);
                rgba[i++] = (byte) ((argb >> 8) & 0xFF);
                rgba[i++] = (byte) (argb & 0xFF);
                rgba[i++] = (byte) ((argb >> 24) & 0xFF);
            }
        return new PakImage(w, h, rgba);
    }

    private static void list(String[] args) throws IOException {
        if (args.length < 2) { usage(); return; }
        PakFile pak = PakFile.readIndex(Path.of(args[1]));
        System.out.println(pak.sourceName() + " (" + pak.entries().size() + " entries)");
        for (PakFile.Entry e : pak.entries())
            System.out.printf("  %-28s %4dx%-4d %8d bytes @%d%n",
                    e.name(), e.width(), e.height(), e.length(), e.offset());
    }

    private static void info(String[] args) throws IOException {
        if (args.length < 2) { usage(); return; }
        Path path = Path.of(args[1]);
        PakFile pak = PakFile.readIndex(path);
        System.out.println(pak.sourceName() + ": " + pak.entries().size() + " entries, "
                + pak.totalPayloadBytes() + " payload bytes, file " + Files.size(path) + " bytes");
    }

    private static void usage() {
        System.out.println("""
                PakTool — .pak asset packer
                  pack --root <dir> --out <file.pak> [--resize WxH] [--resize tile=WxH] [--max-miB N]
                  list <file.pak>
                  info <file.pak>
                Root layout: tile/*.png, sprite/*.png, ui/*.png (names become the asset keys).
                --resize WxH applies to every namespace; --resize <ns>=WxH only to that one.""");
    }
}