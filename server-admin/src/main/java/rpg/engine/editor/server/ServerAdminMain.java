package rpg.engine.editor.server;

import javax.swing.*;

/** Swing entry point for the graphical dedicated-server admin/console. */
public final class ServerAdminMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(ServerAdminFrame::new);
    }
}