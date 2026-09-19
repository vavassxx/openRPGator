package rpg.engine.editor.map;

import rpg.engine.core.io.DataDir;
import rpg.engine.core.math.WorldPosition;
import rpg.engine.map.MapEntity;
import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.map.TileLayer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.*;

/**
 * Full desktop map editor. Top-down canvas with tile painting, collision editing, entity
 * placement with explicit sprite/prefab binding (previews from {@code .pak} assets), an entity
 * inspector, an embedded Lua script editor for entity scripts, and undo/redo.
 *
 * Maps and packs default to the shared data directory ({@code ~/.openrpgator/data}): the packs
 * placed there are picked up automatically for texture previews.
 */
public final class EditorFrame extends JFrame {

    enum Mode { PAINT("Paint"), COLLISION("Collision"), ENTITY("Entity");
        final String label; Mode(String s) { label = s; } }

    private RMap map;
    private Path mapPath;
    private final PakVisuals visuals = new PakVisuals();
    private Mode mode = Mode.PAINT;
    private boolean erase;
    private int selectedTile = 1;
    private String selectedPrefab = "";
    private Integer selectedEntity;

    private final ArrayDeque<RMap> undo = new ArrayDeque<>();
    private final ArrayDeque<RMap> redo = new ArrayDeque<>();
    private boolean editingSession;

    // ── UI ────────────────────────────────────────────────────────
    private final EditorCanvas canvas;
    private final JLabel status = new JLabel(" ");
    private final JComboBox<String> modeCombo = new JComboBox<>();
    private final JToggleButton eraseToggle = new JToggleButton("Erase");
    private final JSpinner tileSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 4095, 1));
    private final JCheckBox collisionCheck = new JCheckBox("Layer collision");
    private final AssetPalette palette;

    private final DefaultListModel<String> entityModel = new DefaultListModel<>();
    private final JList<String> entityList = new JList<>(entityModel);
    private final JTextField idField = new JTextField();
    private final JComboBox<String> prefabCombo = new JComboBox<>();
    private final JTextField xField = new JTextField();
    private final JTextField yField = new JTextField();
    private final JTextField zField = new JTextField("0");
    private final JTextField scriptField = new JTextField();
    private final ScriptPane scriptPane = new ScriptPane();
    private final JTabbedPane rightTabs = new JTabbedPane();

    public EditorFrame() {
        super("openRPGator Map Editor");
        canvas = new EditorCanvas(this);
        palette = new AssetPalette(new AssetPalette.Listener() {
            @Override public void tileSelected(int id) { selectedTile = id; tileSpinner.setValue(id); status("Tile selected: " + id); }
            @Override public void spriteSelected(String prefab) { selectedPrefab = prefab == null ? "" : prefab; status("Prefab: " + (prefab.isEmpty() ? "(plain)" : prefab)); }
        });

        setLayout(new BorderLayout());
        add(buildMenuBar(), BorderLayout.NORTH);
        add(buildNorth(), BorderLayout.NORTH);

        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, palette, canvas);
        center.setResizeWeight(0.0);
        center.setDividerLocation(220);
        add(center, BorderLayout.CENTER);
        add(buildRight(), BorderLayout.EAST);
        add(status, BorderLayout.SOUTH);

        bindKeys();
        setSize(1280, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        newMap("untitled", 32, 32);
        autoLoadPaks();
        refreshScripts();
    }

    // ── Frame model API used by the canvas ────────────────────────
    RMap map() { return map; }
    PakVisuals visuals() { return visuals; }
    Mode mode() { return mode; }
    boolean erase() { return erase; }
    int selectedTile() { return selectedTile; }
    Integer selectedEntity() { return selectedEntity; }

    void beginEditing() {
        if (!editingSession) {
            pushUndo(map);
            editingSession = true;
        }
    }

    void endEditing() {
        editingSession = false;
        redo.clear();
    }

    int entityAt(int x, int y) {
        for (int i = map.entities().size() - 1; i >= 0; i--) {
            var p = map.entities().get(i).position();
            if ((int) Math.floor(p.x()) == x && (int) Math.floor(p.y()) == y) return i;
        }
        return -1;
    }

    void setTileAt(int x, int y, int id) {
        TileLayer l = map.layers().get(0);
        int[] t = l.tiles().clone();
        t[y * map.width() + x] = id;
        commitLayer(new TileLayer(l.name(), l.width(), l.height(), t, l.collision()));
    }

    void setCollisionAt(int x, int y) {
        TileLayer l = map.layers().get(0);
        int[] t = l.tiles().clone();
        int i = y * map.width() + x;
        if (erase) t[i] = 0;
        else if (t[i] == 0) t[i] = selectedTile;
        commitLayer(new TileLayer(l.name(), l.width(), l.height(), t, true));
    }

    private void commitLayer(TileLayer layer) {
        List<TileLayer> layers = new ArrayList<>(map.layers());
        layers.set(0, layer);
        map = new RMap(map.name(), map.tileSize(), map.width(), map.height(), layers, map.entities());
        canvas.repaint();
        collisionCheck.setSelected(layer.collision());
        syncUi();
    }

    void addEntityAt(int x, int y) {
        String prefab = selectedPrefab.isEmpty() ? "entity" : selectedPrefab;
        List<MapEntity> es = new ArrayList<>(map.entities());
        es.add(new MapEntity(nextEntityId(), prefab, new WorldPosition(x, y, 0), null));
        map = new RMap(map.name(), map.tileSize(), map.width(), map.height(), map.layers(), es);
        selectedEntity = es.size() - 1;
        canvas.repaint();
        syncUi();
    }

    void moveSelectedEntityTo(int x, int y) {
        if (selectedEntity == null || selectedEntity < 0 || selectedEntity >= map.entities().size()) return;
        List<MapEntity> es = new ArrayList<>(map.entities());
        MapEntity e = es.get(selectedEntity);
        es.set(selectedEntity, new MapEntity(e.id(), e.prefab(), new WorldPosition(x, y, e.position().elevation()), e.script()));
        map = new RMap(map.name(), map.tileSize(), map.width(), map.height(), map.layers(), es);
        canvas.repaint();
    }

    void setSelectedEntity(Integer index) {
        selectedEntity = index;
        canvas.repaint();
        showEntity(index);
    }

    private void showEntity(Integer index) {
        boolean has = index != null && index >= 0 && index < map.entities().size();
        idField.setEnabled(has);
        prefabCombo.setEnabled(has);
        xField.setEnabled(has);
        yField.setEnabled(has);
        zField.setEnabled(has);
        scriptField.setEnabled(has);
        if (!has) {
            idField.setText(""); prefabCombo.setSelectedItem(""); xField.setText(""); yField.setText(""); zField.setText("0"); scriptField.setText("");
            return;
        }
        MapEntity e = map.entities().get(index);
        idField.setText(e.id());
        prefabCombo.setSelectedItem(e.prefab());
        xField.setText(String.valueOf(e.position().x()));
        yField.setText(String.valueOf(e.position().y()));
        zField.setText(String.valueOf(e.position().elevation()));
        scriptField.setText(e.script() == null ? "" : e.script());
    }

    private String nextEntityId() {
        int max = 0;
        for (MapEntity e : map.entities())
            if (e.id() != null && e.id().startsWith("entity_"))
                try { max = Math.max(max, Integer.parseInt(e.id().substring("entity_".length()))); } catch (NumberFormatException ignored) {}
        return "entity_" + (max + 1);
    }

    // ── File operations ───────────────────────────────────────────
    private void newMap() {
        SpinnerNumberModel wm = new SpinnerNumberModel(32, 1, 512, 1), hm = new SpinnerNumberModel(32, 1, 512, 1);
        JPanel p = new JPanel(new GridLayout(3, 2, 8, 8));
        JTextField name = new JTextField("untitled");
        JSpinner w = new JSpinner(wm), h = new JSpinner(hm);
        p.add(new JLabel("Name:")); p.add(name);
        p.add(new JLabel("Width:")); p.add(w);
        p.add(new JLabel("Height:")); p.add(h);
        if (JOptionPane.showConfirmDialog(this, p, "New map", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        pushUndo(map);
        newMap(name.getText().trim().isEmpty() ? "untitled" : name.getText().trim(), (Integer) w.getValue(), (Integer) h.getValue());
        mapPath = null;
        canvas.repaint();
        syncUi();
        refreshScripts();
    }

    private void newMap(String name, int w, int h) {
        map = new RMap(name, 32, w, h,
                List.of(new TileLayer("ground", w, h, new int[w * h], false)),
                List.of());
        selectedEntity = null;
        canvas.repaint();
        syncUi();
        status(map.name() + " " + map.width() + "×" + map.height() + " created");
    }

    private void open() {
        JFileChooser fc = chooser("Open map", DataDir.host().toFile(), "rmap");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            RMap m = RMapIO.read(fc.getSelectedFile().toPath());
            pushUndo(map);
            map = m;
            mapPath = fc.getSelectedFile().toPath();
            selectedEntity = null;
            canvas.resetView();
            canvas.repaint();
            syncUi();
            refreshScripts();
            status("Opened " + mapPath.getFileName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Open failed: " + e.getMessage());
        }
    }

    private void save() {
        if (mapPath == null) { saveAs(); return; }
        try {
            RMapIO.write(map, mapPath);
            status("Saved " + mapPath.getFileName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Save failed: " + e.getMessage());
        }
    }

    private void saveAs() {
        JFileChooser fc = chooser("Save map as", mapPath == null ? DataDir.host().toFile() : mapPath.getParent().toFile(), "rmap");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        if (!p.toString().endsWith(".rmap")) p = Path.of(p.toString() + ".rmap");
        mapPath = p;
        save();
    }

    private static JFileChooser chooser(String title, File start, String ext) {
        JFileChooser fc = new JFileChooser(start);
        fc.setDialogTitle(title);
        fc.setFileFilter(new FileNameExtensionFilter(ext.toUpperCase() + " files", ext));
        return fc;
    }

    // ── Assets ────────────────────────────────────────────────────
    private void autoLoadPaks() {
        try { DataDir.ensure(); } catch (IOException ignored) {}
        for (Path p : DataDir.hostPaks()) visuals.add(p);
        refreshPalette();
        if (!visuals.pakPaths().isEmpty())
            status("Loaded " + visuals.pakPaths().size() + " pak(s) from data/host");
    }

    private void loadPaks() {
        JFileChooser fc = new JFileChooser(DataDir.host().toFile());
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("Asset packs", "pak"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        for (var f : fc.getSelectedFiles()) visuals.add(f.toPath());
        refreshPalette();
        status("Assets: " + visuals.pakPaths().size() + " pak(s), "
                + visuals.tileCount() + " tiles, " + visuals.spriteCount() + " sprites");
    }

    private void refreshPalette() {
        if (mode == Mode.ENTITY) {
            palette.showSprites(visuals.spriteKeys(), visuals.spriteImages(), selectedPrefab);
            refreshPrefabCombo();
        } else {
            palette.showTiles(visuals.tileImages(), selectedTile);
        }
    }

    private void refreshPrefabCombo() {
        String current = prefabCombo.getEditor().getItem().toString();
        prefabCombo.removeAllItems();
        for (String k : visuals.spriteKeys()) prefabCombo.addItem(k);
        prefabCombo.setEditable(true);
        prefabCombo.setSelectedItem(current.isEmpty() ? "" : current);
    }

    // ── Undo / redo ───────────────────────────────────────────────
    private void pushUndo(RMap state) {
        undo.push(state);
        if (undo.size() > 80) undo.removeLast();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(map);
        map = undo.pop();
        selectedEntity = null;
        canvas.repaint();
        syncUi();
        refreshScripts();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(map);
        map = redo.pop();
        selectedEntity = null;
        canvas.repaint();
        syncUi();
        refreshScripts();
    }

    // ── UI assembly ───────────────────────────────────────────────
    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu file = new JMenu("File");
        item(file, "New", KeyEvent.VK_N, () -> newMap());
        item(file, "Open…", KeyEvent.VK_O, () -> open());
        item(file, "Save", KeyEvent.VK_S, () -> save());
        item(file, "Save As…", 0, () -> saveAs());

        JMenu edit = new JMenu("Edit");
        item(edit, "Undo", KeyEvent.VK_Z, () -> undo());
        item(edit, "Redo", 0, () -> redo());

        JMenu assets = new JMenu("Assets");
        item(assets, "Load pak…", 0, () -> loadPaks());
        item(assets, "Reload paks", 0, () -> { visuals.reload(); refreshPalette(); status("Reloaded paks"); });

        mb.add(file); mb.add(edit); mb.add(assets);
        return mb;
    }

    private static JMenuItem item(JMenu menu, String label, int key, Runnable action) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(e -> action.run());
        if (key != 0) mi.setAccelerator(KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK));
        menu.add(mi);
        return mi;
    }

    private JComponent buildNorth() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        for (Mode m : Mode.values()) modeCombo.addItem(m.label);
        modeCombo.addActionListener(e -> setMode(Mode.values()[modeCombo.getSelectedIndex()]));
        bar.add(new JLabel("Mode:"));
        bar.add(modeCombo);
        bar.add(eraseToggle);
        eraseToggle.addActionListener(e -> { erase = eraseToggle.isSelected(); status(erase ? "Erase ON" : ""); });
        bar.add(new JLabel("Tile:"));
        tileSpinner.setPreferredSize(new Dimension(70, 26));
        tileSpinner.addChangeListener(e -> selectedTile = (Integer) tileSpinner.getValue());
        bar.add(tileSpinner);
        collisionCheck.addActionListener(e -> toggleCollision());
        bar.add(collisionCheck);
        JButton zo = new JButton("+"), zi = new JButton("−"), rv = new JButton("Reset view");
        zo.addActionListener(e -> canvas.zoomBy(1.25));
        zi.addActionListener(e -> canvas.zoomBy(0.8));
        rv.addActionListener(e -> canvas.resetView());
        bar.add(zo); bar.add(zi); bar.add(rv);
        return bar;
    }

    private void toggleCollision() {
        TileLayer l = map.layers().get(0);
        beginEditing();
        ArrayList<TileLayer> ls = new ArrayList<>(map.layers());
        ls.set(0, new TileLayer(l.name(), l.width(), l.height(), l.tiles().clone(), collisionCheck.isSelected()));
        map = new RMap(map.name(), map.tileSize(), map.width(), map.height(), ls, map.entities());
        endEditing();
        canvas.repaint();
        status("Layer collision: " + (map.layers().get(0).collision() ? "on" : "off"));
    }

    private void setMode(Mode m) {
        mode = m;
        refreshPalette();
        status("Mode: " + m.label + (erase ? " (erase)" : ""));
    }

    private JComponent buildRight() {
        // ── Entities tab ──────────────────────────────────────────
        entityList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && entityList.getSelectedIndex() >= 0)
                setSelectedEntity(entityList.getSelectedIndex());
        });
        prefabCombo.setEditable(true);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 2;
        c.gridx = 0; c.gridy = 0;
        form.add(new JScrollPane(entityList) {{
            setPreferredSize(new Dimension(0, 150));
        }}, c);
        c.gridwidth = 1;
        c.gridy++;
        form.add(new JLabel("ID:"), c); c.gridx = 1; form.add(idField, c);
        c.gridx = 0; c.gridy++;
        form.add(new JLabel("Prefab:"), c); c.gridx = 1; form.add(prefabCombo, c);
        c.gridx = 0; c.gridy++;
        form.add(new JLabel("X / Y / Z:"), c); c.gridx = 1;
        JPanel xyz = new JPanel(new GridLayout(1, 3, 4, 4));
        xyz.add(xField); xyz.add(yField); xyz.add(zField);
        form.add(xyz, c);
        c.gridx = 0; c.gridy++;
        form.add(new JLabel("Script:"), c); c.gridx = 1; form.add(scriptField, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton apply = new JButton("Apply"); apply.addActionListener(e -> applyEntity());
        JButton del = new JButton("Delete"); del.addActionListener(e -> deleteSelectedEntity());
        JButton script = new JButton("Edit script >"); script.addActionListener(e -> editScriptOfSelected());
        buttons.add(apply); buttons.add(del); buttons.add(script);
        c.gridx = 0; c.gridy++; c.gridwidth = 2;
        form.add(buttons, c);

        form.setBorder(new EmptyBorder(6, 6, 6, 6));
        rightTabs.addTab("Entities", form);
        rightTabs.addTab("Scripts", scriptPane);
        rightTabs.setPreferredSize(new Dimension(360, 600));
        return rightTabs;
    }

    private void bindKeys() {
        String[] actions = {"undo", "redo", "deleteEntity"};
        JComponent pane = (JComponent) getContentPane();
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), actions[0]);
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), actions[1]);
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), actions[2]);
        pane.getActionMap().put(actions[0], new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { undo(); } });
        pane.getActionMap().put(actions[1], new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { redo(); } });
        pane.getActionMap().put(actions[2], new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { deleteSelectedEntity(); } });
    }

    private void applyEntity() {
        if (selectedEntity == null || selectedEntity < 0 || selectedEntity >= map.entities().size()) return;
        beginEditing();
        List<MapEntity> es = new ArrayList<>(map.entities());
        MapEntity e = es.get(selectedEntity);
        String id = idField.getText().trim();
        if (id.isEmpty()) id = e.id();
        String prefab = prefabCombo.getEditor().getItem().toString().trim();
        double x, y, z;
        try {
            x = xField.getText().trim().isEmpty() ? e.position().x() : Double.parseDouble(xField.getText().trim());
            y = yField.getText().trim().isEmpty() ? e.position().y() : Double.parseDouble(yField.getText().trim());
            z = zField.getText().trim().isEmpty() ? e.position().elevation() : Double.parseDouble(zField.getText().trim());
        } catch (NumberFormatException ex) {
            status("Bad coordinates"); return;
        }
        String script = scriptField.getText().trim();
        es.set(selectedEntity, new MapEntity(id, prefab, new WorldPosition(x, y, z), script.isEmpty() ? null : script));
        map = new RMap(map.name(), map.tileSize(), map.width(), map.height(), map.layers(), es);
        endEditing();
        syncUi();
        refreshScripts();
        status("Entity updated: " + id);
    }

    private void deleteSelectedEntity() {
        if (selectedEntity == null || selectedEntity < 0 || selectedEntity >= map.entities().size()) return;
        beginEditing();
        List<MapEntity> es = new ArrayList<>(map.entities());
        es.remove((int) selectedEntity);
        map = new RMap(map.name(), map.tileSize(), map.width(), map.height(), map.layers(), es);
        selectedEntity = null;
        endEditing();
        syncUi();
        status("Entity deleted");
    }

    private void editScriptOfSelected() {
        String name = scriptField.getText().trim();
        if (selectedEntity == null) { status("Select an entity first"); return; }
        if (name.isEmpty()) {
            name = JOptionPane.showInputDialog(this, "Script file name", idField.getText() + ".lua");
            if (name == null || name.isBlank()) return;
            scriptField.setText(name.trim());
            applyEntity();
        }
        rightTabs.setSelectedIndex(1);
        scriptPane.selectScript(name);
    }

    // ── Sync ──────────────────────────────────────────────────────
    private void syncUi() {
        // entity list
        String prev = entityList.getSelectedIndex() >= 0 ? entityModel.getElementAt(entityList.getSelectedIndex()) : null;
        entityModel.clear();
        for (MapEntity e : map.entities())
            entityModel.addElement(e.id() + " · " + e.prefab() + " @(" + e.position().x() + "," + e.position().y() + ")");
        if (prev != null) { int i = entityModel.indexOf(prev); if (i >= 0) entityList.setSelectedIndex(i); }
        collisionCheck.setSelected(map.layers().get(0).collision());
        showEntity(selectedEntity);
        status("Map: " + map.name() + " " + map.width() + "×" + map.height()
                + " · entities " + map.entities().size()
                + " · mode " + mode.label + " · tile " + selectedTile
                + (erase ? " · erase" : ""));
    }

    private void refreshScripts() {
        Set<String> names = new TreeSet<>();
        for (MapEntity e : map.entities())
            if (e.script() != null && !e.script().isBlank()) names.add(e.script());
        Path dir = mapPath == null ? null : mapPath.getParent();
        if (dir != null) for (Path p : DataDir.listIn(dir, ".lua")) names.add(p.getFileName().toString());
        scriptPane.refreshScripts(List.copyOf(names), dir == null ? DataDir.host() : dir);
    }

    private void status(String s) {
        status.setText(" " + (s == null ? "" : s));
    }
}