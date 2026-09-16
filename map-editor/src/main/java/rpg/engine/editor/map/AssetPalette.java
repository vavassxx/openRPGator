package rpg.engine.editor.map;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Vertical swatch palette. Shows tile thumbs when editing tiles and sprite thumbs (plus a bare
 * "plain" choice) when placing entities. Selection is exclusive and reported through callbacks.
 */
final class AssetPalette extends JPanel {

    interface Listener {
        void tileSelected(int id);
        void spriteSelected(String prefab);
    }

    private final Listener listener;
    private final JPanel grid = new JPanel(new GridLayout(0, 4, 4, 4));
    private ButtonGroup group = new ButtonGroup();
    private JToggleButton lastSelected;

    AssetPalette(Listener listener) {
        this.listener = listener;
        setLayout(new BorderLayout());
        grid.setOpaque(false);
        JScrollPane sp = new JScrollPane(grid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        add(sp, BorderLayout.CENTER);
        setPreferredSize(new Dimension(215, 0));
    }

    void showTiles(BufferedImage[] tiles, int selectedId) {
        grid.removeAll();
        group = new ButtonGroup();
        lastSelected = null;
        int n = tiles.length;
        for (int id = 0; id < n; id++) {
            final int fid = id;
            JToggleButton b = swatch(labelFor(id), tileIcon(tiles[id]));
            b.addActionListener(e -> { lastSelected = b; listener.tileSelected(fid); });
            addSwatch(b);
            if (id == selectedId) { b.setSelected(true); lastSelected = b; }
        }
        revalidate(); repaint();
    }

    void showSprites(String[] keys, BufferedImage[] images, String selected) {
        grid.removeAll();
        group = new ButtonGroup();
        lastSelected = null;

        JToggleButton plain = swatch("* plain *", colorIcon(new Color(80, 80, 110), 28, 28));
        plain.setToolTipText("Entity with no assigned sprite (renders as a marker)");
        plain.addActionListener(e -> { lastSelected = plain; listener.spriteSelected(""); });
        addSwatch(plain);
        if (selected == null || selected.isEmpty()) { plain.setSelected(true); lastSelected = plain; }

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            JToggleButton b = swatch(key, iconOrColor(images[i]));
            b.setToolTipText("sprite/" + key);
            b.addActionListener(e -> { lastSelected = b; listener.spriteSelected(key); });
            addSwatch(b);
            if (selected != null && selected.equals(key)) { b.setSelected(true); lastSelected = b; }
        }
        revalidate(); repaint();
    }

    private void addSwatch(JToggleButton b) {
        group.add(b);
        grid.add(b);
    }

    private static JToggleButton swatch(String label, ImageIcon icon) {
        JToggleButton b = new JToggleButton();
        b.setIcon(icon);
        b.setText(label);
        b.setHorizontalTextPosition(SwingConstants.CENTER);
        b.setVerticalTextPosition(SwingConstants.BOTTOM);
        b.setToolTipText(label);
        return b;
    }

    private static String labelFor(int id) { return "tile " + id; }

    private static ImageIcon tileIcon(BufferedImage img) {
        if (img == null) return colorIcon(new Color(40, 42, 48), 28, 28);
        return scaledIcon(img, 32, 32);
    }

    private static ImageIcon iconOrColor(BufferedImage img) {
        if (img == null) return colorIcon(new Color(120, 60, 160), 28, 28);
        return scaledIcon(img, 32, 32);
    }

    private static ImageIcon scaledIcon(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // Fit inside w×h preserving aspect, centered on a dark backing.
        g.setColor(new Color(28, 30, 34));
        g.fillRect(0, 0, w, h);
        double sx = (double) w / src.getWidth(), sy = (double) h / src.getHeight();
        double s = Math.min(sx, sy);
        int dw = (int) (src.getWidth() * s), dh = (int) (src.getHeight() * s);
        g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        g.dispose();
        return new ImageIcon(out);
    }

    private static ImageIcon colorIcon(Color c, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return new ImageIcon(out);
    }
}