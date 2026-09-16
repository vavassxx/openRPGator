package rpg.engine.editor.map;

import javax.swing.SwingUtilities;

/** Entry point for the desktop map editor. */
public final class MapEditorMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new EditorFrame().setVisible(true));
    }
}