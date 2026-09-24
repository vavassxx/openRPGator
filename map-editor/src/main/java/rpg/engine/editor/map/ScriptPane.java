package rpg.engine.editor.map;

import rpg.engine.script.lua.LuaRuntime;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Embedded Lua script editor pane. Provides basic open/save/check/create/delete actions for
 * scripts that are attached to map entities. Syntax validation runs against the real engine
 * Lua environment (compile only, no side effects).
 */
final class ScriptPane extends JPanel {

    private final JTextArea editor = new JTextArea();
    private final JComboBox<String> combo = new JComboBox<>();
    private final JLabel status = new JLabel(" ");
    private final JButton saveButton = new JButton("Save");
    private final JButton checkButton = new JButton("Check");
    private final JButton newButton = new JButton("New…");
    private final JButton deleteButton = new JButton("Delete");

    private Path mapDir;
    private final DefaultComboBoxModel<String> comboModel = new DefaultComboBoxModel<>();

    ScriptPane() {
        setLayout(new BorderLayout());
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        add(new JScrollPane(editor), BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        combo.setModel(comboModel);
        combo.setPreferredSize(new Dimension(160, 28));
        combo.addActionListener(e -> onComboSelect());
        toolbar.add(combo);
        saveButton.addActionListener(e -> save());
        checkButton.addActionListener(e -> check());
        newButton.addActionListener(e -> newScript());
        deleteButton.addActionListener(e -> deleteCurrent());
        toolbar.add(saveButton);
        toolbar.add(checkButton);
        toolbar.add(newButton);
        toolbar.add(deleteButton);
        add(toolbar, BorderLayout.NORTH);
        add(status, BorderLayout.SOUTH);
    }

    void refreshScripts(List<String> names, Path mapDir) {
        this.mapDir = mapDir;
        comboModel.removeAllElements();
        for (String n : names) comboModel.addElement(n);
    }

    void selectScript(String name) {
        if (name == null) return;
        for (int i = 0; i < comboModel.getSize(); i++)
            if (comboModel.getElementAt(i).equals(name)) {
                combo.setSelectedIndex(i);
                return;
            }
        // not found — add and open
        comboModel.addElement(name);
        combo.setSelectedItem(name);
    }

    private void onComboSelect() {
        String name = (String) comboModel.getSelectedItem();
        if (name == null || name.isBlank() || mapDir == null) return;
        Path f = mapDir.resolve(name);
        if (Files.isRegularFile(f)) {
            try { editor.setText(Files.readString(f)); status.setText("Loaded " + name); }
            catch (IOException e) { status.setText("Load error: " + e.getMessage()); }
        } else {
            editor.setText("");
            status.setText("New file: " + name);
        }
    }

    private void save() {
        String name = (String) comboModel.getSelectedItem();
        if (name == null || name.isBlank() || mapDir == null) { status.setText("No script name"); return; }
        if (!name.endsWith(".lua")) name = name + ".lua";
        if (comboModel.getIndexOf(name) < 0) comboModel.addElement(name);
        Path f = mapDir.resolve(name);
        try {
            Files.writeString(f, editor.getText());
            status.setText("Saved " + name);
        } catch (IOException e) { status.setText("Save error: " + e.getMessage()); }
    }

    private void check() {
        String src = editor.getText();
        if (src.isBlank()) { status.setText("Nothing to check"); return; }
        try {
            LuaRuntime rt = new LuaRuntime();
            rt.globals().load(src, "check");
            status.setText("Syntax OK");
        } catch (Exception e) { status.setText(e.getMessage() == null ? "Syntax error" : e.getMessage()); }
    }

    private void newScript() {
        String name = JOptionPane.showInputDialog(this, "New Lua script name", "guard.lua");
        if (name == null || name.isBlank()) return;
        if (!name.endsWith(".lua")) name = name + ".lua";
        comboModel.addElement(name);
        combo.setSelectedItem(name);
        editor.setText("");
        status.setText("Creating " + name);
    }

    private void deleteCurrent() {
        String name = (String) comboModel.getSelectedItem();
        if (name == null || mapDir == null) return;
        Path f = mapDir.resolve(name);
        if (!Files.isRegularFile(f)) { status.setText("File not found: " + name); return; }
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete " + name + "?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        try {
            Files.delete(f);
            comboModel.removeElement(name);
            status.setText("Deleted " + name);
        } catch (IOException e) { status.setText("Delete error: " + e.getMessage()); }
    }
}