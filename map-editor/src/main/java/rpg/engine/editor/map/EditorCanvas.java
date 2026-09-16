package rpg.engine.editor.map;

import rpg.engine.map.RMap;
import rpg.engine.map.TileLayer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * Editable top-down grid canvas. Paints tiles/collision/entities from the frame model,
 * supports zoom (wheel), pan (right-drag), entity select/move and live painting.
 * All mutations are routed through {@link EditorFrame} so undo/redo stays consistent.
 */
final class EditorCanvas extends JPanel {

    static final int CELL = 48;

    private final EditorFrame frame;
    private double zoom = 1.0;
    private double ox = 100, oy = 60;

    private boolean painting;
    private boolean draggingEntity;
    private int lastCellX, lastCellY;
    private boolean panning;
    private double panLastX, panLastY;

    EditorCanvas(EditorFrame frame) {
        this.frame = frame;
        setBackground(new Color(30, 31, 35));
        setFocusable(true);
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.getButton() == MouseEvent.BUTTON3 || e.getButton() == MouseEvent.BUTTON2) {
                    panning = true;
                    panLastX = e.getX(); panLastY = e.getY();
                    return;
                }
                int[] c = cellAt(e.getX(), e.getY());
                if (c == null) return;
                frame.beginEditing();
                if (frame.mode() == EditorFrame.Mode.ENTITY) {
                    int idx = frame.entityAt(c[0], c[1]);
                    if (idx >= 0) {
                        draggingEntity = true;
                        frame.setSelectedEntity(idx);
                    } else {
                        frame.addEntityAt(c[0], c[1]);
                    }
                } else {
                    painting = true;
                    lastCellX = c[0]; lastCellY = c[1];
                    paintCell(c[0], c[1]);
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (panning) {
                    ox += e.getX() - panLastX;
                    oy += e.getY() - panLastY;
                    panLastX = e.getX(); panLastY = e.getY();
                    repaint();
                    return;
                }
                if (draggingEntity) {
                    int[] c = cellAt(e.getX(), e.getY());
                    if (c != null) frame.moveSelectedEntityTo(c[0], c[1]);
                    return;
                }
                if (painting) {
                    int[] c = cellAt(e.getX(), e.getY());
                    if (c != null && (c[0] != lastCellX || c[1] != lastCellY)) {
                        lastCellX = c[0]; lastCellY = c[1];
                        paintCell(c[0], c[1]);
                    }
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                panning = false;
                painting = false;
                draggingEntity = false;
                frame.endEditing();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double factor = e.getPreciseWheelRotation() < 0 ? 1.12 : 1 / 1.12;
                zoomAt(e.getX(), e.getY(), factor);
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    RMap map() { return frame.map(); }
    double zoom() { return zoom; }

    void zoomBy(double factor) { zoom = clampZoom(zoom * factor); repaint(); }
    void resetView() {
        zoom = 1.0;
        ox = 100; oy = 60;
        repaint();
    }

    private void zoomAt(double px, double py, double factor) {
        double nx = clampZoom(zoom * factor);
        if (nx == zoom) return;
        double wx = (px - ox) / zoom, wy = (py - oy) / zoom;
        ox = px - wx * nx;
        oy = py - wy * nx;
        zoom = nx;
    }

    private static double clampZoom(double z) { return Math.max(0.2, Math.min(8.0, z)); }

    private int[] cellAt(double px, double py) {
        RMap m = map();
        if (m == null) return null;
        int sx = (int) Math.floor((px - ox) / (CELL * zoom));
        int sy = (int) Math.floor((py - oy) / (CELL * zoom));
        if (sx < 0 || sy < 0 || sx >= m.width() || sy >= m.height()) return null;
        return new int[]{sx, sy};
    }

    private void paintCell(int x, int y) {
        if (frame.mode() == EditorFrame.Mode.PAINT) {
            frame.setTileAt(x, y, frame.erase() ? 0 : frame.selectedTile());
        } else if (frame.mode() == EditorFrame.Mode.COLLISION) {
            frame.setCollisionAt(x, y);
        }
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());

        RMap m = map();
        if (m == null || m.layers().isEmpty()) {
            g.setColor(new Color(90, 90, 100));
            g.drawString("no map", 20, 30);
            return;
        }

        double s = CELL * zoom;
        TileLayer ground = m.layers().get(0);
        PakVisuals visuals = frame.visuals();

        for (int y = 0; y < m.height(); y++) {
            for (int x = 0; x < m.width(); x++) {
                int id = ground.tiles()[y * m.width() + x];
                double l = ox + x * s, t = oy + y * s, r = l + s, b = t + s;
                g.setColor(tileColor(id));
                g.fillRect((int) l, (int) t, (int) s + 1, (int) s + 1);
                if (id > 0) {
                    g.setColor(new Color(0, 0, 0, 90));
                    g.fillRect((int) l, (int) t, (int) s + 1, (int) s + 1);
                }
                g.setColor(new Color(255, 255, 255, 40));
                g.drawRect((int) l, (int) t, (int) s, (int) s);
                if (ground.collision() && id != 0) {
                    g.setColor(new Color(255, 60, 60, 70));
                    g.fillRect((int) l, (int) t, (int) s, (int) s);
                }
            }
        }
        for (int layer = 1; layer < m.layers().size(); layer++) {
            TileLayer lc = m.layers().get(layer);
            if (!lc.collision()) continue;
            for (int y = 0; y < lc.height() && y < m.height(); y++)
                for (int x = 0; x < lc.width() && x < m.width(); x++)
                    if (lc.tiles()[y * lc.width() + x] != 0) {
                        double l = ox + x * s, t = oy + y * s;
                        g.setColor(new Color(255, 60, 60, 40));
                        g.fillRect((int) l, (int) t, (int) s, (int) s);
                    }
        }

        // ── Entities ─────────────────────────────────────────────
        for (int i = 0; i < m.entities().size(); i++) {
            var e = m.entities().get(i);
            double ex = ox + e.position().x() * s + s / 2;
            double ey = oy + e.position().y() * s + s / 2;
            java.awt.image.BufferedImage img = visuals.spriteImageFor(e.prefab());
            boolean sel = frame.selectedEntity() != null && frame.selectedEntity() == i;
            if (img != null) {
                double scale = Math.min((s * 0.9) / img.getWidth(), (s * 0.9) / img.getHeight());
                int w = (int) (img.getWidth() * scale), h = (int) (img.getHeight() * scale);
                g.drawImage(img, (int) (ex - w / 2.0), (int) (ey - h / 2.0), w, h, null);
                g.setColor(new Color(255, 255, 255, 90));
                g.drawRect((int) (ex - w / 2.0), (int) (ey - h / 2.0), w, h);
            } else {
                g.setColor(Color.MAGENTA);
                int radius = Math.max(5, (int) (s * 0.22));
                g.fillOval((int) ex - radius, (int) ey - radius, radius * 2, radius * 2);
            }
            if (sel) {
                g.setColor(Color.ORANGE);
                g.setStroke(new BasicStroke(2));
                g.drawRect((int) (ex - s / 2), (int) (ey - s / 2), (int) s, (int) s);
                g.setStroke(new BasicStroke(1));
            }
            g.setColor(Color.WHITE);
            g.drawString(e.id(), (int) ex + 6, (int) ey - 6);
        }
    }

    static Color tileColor(int id) {
        if (id == 0) return new Color(48, 49, 54);
        float h = (id * 37) % 360;
        return Color.getHSBColor(h / 360f, 0.48f, 0.42f);
    }
}