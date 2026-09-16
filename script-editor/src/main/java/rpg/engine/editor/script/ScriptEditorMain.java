package rpg.engine.editor.script;
import org.luaj.vm2.Globals;
import rpg.engine.core.io.DataDir;
import rpg.engine.script.lua.LuaRuntime;
import javax.swing.*; import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*; import java.io.File; import java.io.IOException; import java.nio.file.Files; import java.nio.file.Path;

/**
 * Standalone Lua script editor. Opens and saves {@code .lua} files (default: shared data dir
 * {@code ~/.openrpgator/data/maps}), with syntax checking against the real engine Lua environment
 * (compile only — scripts are never executed here).
 */
public final class ScriptEditorMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ScriptEditorMain().show());
    }

    private final JFrame frame = new JFrame("Lua Script Editor");
    private final JTextArea text = new JTextArea("-- entity script\nfunction onSpawn(e)\n    return true\nend\n");
    private final JLabel status = new JLabel("Ready");
    private Path current;

    private void show() {
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        frame.setJMenuBar(menuBar());
        frame.add(new JScrollPane(text), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton check = new JButton("Run check");
        check.addActionListener(e -> check());
        bottom.add(check);
        bottom.add(status);
        frame.add(bottom, BorderLayout.SOUTH);

        frame.setSize(820, 620);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private JMenuBar menuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("New", e -> newFile()));
        file.add(item("Open…", e -> open()));
        file.add(item("Save", e -> save()));
        file.add(item("Save As…", e -> saveAs()));
        mb.add(file);
        return mb;
    }

    private static JMenuItem item(String label, java.awt.event.ActionListener listener) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(listener);
        return mi;
    }

    private void newFile() {
        current = null;
        text.setText("");
        updateTitle();
        status("New file");
    }

    private void open() {
        JFileChooser fc = chooser("Open Lua script", DataDir.maps().toFile(), "lua");
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        try {
            String src = Files.readString(p);
            if (p.toString().endsWith(".lua")) setPath(p);
            text.setText(src);
            text.setCaretPosition(0);
            status("Opened " + p.getFileName());
        } catch (IOException ex) { status("Open error: " + ex.getMessage()); }
    }

    private void save() {
        if (current == null) { saveAs(); return; }
        try {
            Files.writeString(current, text.getText());
            status("Saved " + current.getFileName());
        } catch (IOException ex) { status("Save error: " + ex.getMessage()); }
    }

    private void saveAs() {
        JFileChooser fc = chooser("Save Lua script",
                current == null ? DataDir.maps().toFile() : current.getParent().toFile(), "lua");
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        if (!p.toString().endsWith(".lua")) p = Path.of(p.toString() + ".lua");
        setPath(p);
        save();
    }

    private void setPath(Path p) {
        current = p;
        updateTitle();
    }

    private void updateTitle() {
        frame.setTitle("Lua Script Editor — " + (current == null ? "(unsaved)" : current.getFileName()));
    }

    private void check() {
        String src = text.getText();
        if (src.isBlank()) { status("Nothing to check"); return; }
        try {
            LuaRuntime rt = new LuaRuntime();
            Globals g = rt.globals();
            g.load(src, current == null ? "editor" : current.getFileName().toString());
            status("Syntax OK");
        } catch (Exception ex) { status(ex.getMessage() == null ? "Syntax error" : ex.getMessage()); }
    }

    private static JFileChooser chooser(String title, File start, String ext) {
        JFileChooser fc = new JFileChooser(start);
        fc.setDialogTitle(title);
        fc.setFileFilter(new FileNameExtensionFilter(ext.toUpperCase() + " files", ext));
        return fc;
    }

    private void status(String s) { status.setText(" " + s); }
}