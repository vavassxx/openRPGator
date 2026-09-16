package rpg.engine.editor.server;

import rpg.engine.core.io.DataDir;
import rpg.engine.map.RMap;
import rpg.engine.map.RMapIO;
import rpg.engine.server.ServerConfig;
import rpg.engine.server.ServerHost;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Graphical console for the dedicated server. Configures the shared data directory
 * ({@code ~/.openrpgator/data}), picks a map and asset packs, selects a port and runs the
 * server in-process so live log output is shown right next to the controls.
 *
 * Settings persist to {@code ~/.openrpgator/server.properties}.
 */
public final class ServerAdminFrame extends JFrame {

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".openrpgator", "server.properties");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextField dataDirField = new JTextField(30);
    private final JComboBox<String> mapCombo = new JComboBox<>();
    private final DefaultListModel<String> pakModel = new DefaultListModel<>();
    private final JList<String> pakList = new JList<>(pakModel);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(27800, 1, 65535, 1));
    private final JButton startButton = new JButton("Start server");
    private final JLabel status = new JLabel("Stopped");
    private final JTextArea log = new JTextArea();
    private final JLabel mapInfo = new JLabel(" ");

    private volatile ServerHost host;

    public ServerAdminFrame() {
        super("openRPGator — Server admin");
        mapCombo.setEditable(true);
        setLayout(new BorderLayout(8, 8));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        add(buildControls(), BorderLayout.NORTH);
        add(buildLog(), BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);

        loadConfig();
        refresh();
        pack();
        setSize(1000, 680);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setVisible(true);
    }

    private JComponent buildControls() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; p.add(new JLabel("Data directory:"), c);
        c.gridx = 1; c.weightx = 1; p.add(dataDirField, c);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> chooseDataDir());
        c.gridx = 2; c.weightx = 0; p.add(browse, c);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        c.gridx = 3; p.add(refresh, c);

        c.gridx = 0; c.gridy = 1; p.add(new JLabel("Map (.rmap):"), c);
        c.gridx = 1; c.gridwidth = 1; c.weightx = 1; p.add(mapCombo, c);
        JButton pickMap = new JButton("Pick…");
        pickMap.addActionListener(e -> pickMap());
        c.gridx = 2; c.weightx = 0; p.add(pickMap, c);
        c.gridx = 3; p.add(mapInfo, c);

        c.gridx = 0; c.gridy = 2; c.anchor = GridBagConstraints.NORTHWEST;
        p.add(new JLabel("Packs (.pak):"), c);

        JPanel pakPane = new JPanel(new BorderLayout(4, 4));
        pakList.setVisibleRowCount(2);
        pakList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane sp = new JScrollPane(pakList);
        sp.setPreferredSize(new Dimension(200, 54));
        pakPane.add(sp, BorderLayout.CENTER);
        JPanel pakButtons = new JPanel(new GridLayout(2, 1, 4, 4));
        JButton addPak = new JButton("Pick…");
        addPak.addActionListener(e -> pickPaks());
        pakButtons.add(addPak);
        JButton clearPak = new JButton("Clear");
        clearPak.addActionListener(e -> pakModel.clear());
        pakButtons.add(clearPak);
        pakPane.add(pakButtons, BorderLayout.EAST);
        c.gridx = 1; c.gridy = 2; c.fill = GridBagConstraints.BOTH; c.gridwidth = 2; p.add(pakPane, c);

        c.gridx = 3; c.gridy = 3; c.gridwidth = 1; p.add(new JLabel("Port:"), c);
        c.gridx = 1; p.add(portSpinner, c);

        return p;
    }

    private JComponent buildLog() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return new JScrollPane(log);
    }

    private JComponent buildSouth() {
        status.setForeground(new Color(0x80, 0x60, 0x00));
        startButton.addActionListener(e -> toggle());
        JPanel p = new JPanel(new BorderLayout(8, 4));
        p.add(status, BorderLayout.WEST);
        p.add(startButton, BorderLayout.EAST);
        return p;
    }

    // ── Actions ───────────────────────────────────────────────────
    private void chooseDataDir() {
        JFileChooser fc = new JFileChooser(dataDirField.getText().isEmpty()
                ? DataDir.root().toFile() : new File(dataDirField.getText()));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dataDirField.setText(fc.getSelectedFile().getAbsolutePath());
            refresh();
        }
    }

    private void pickMap() {
        JFileChooser fc = new JFileChooser(Path.of(dataDirField.getText(), "maps").toFile());
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) { return f.isDirectory() || f.getName().endsWith(".rmap"); }
            public String getDescription() { return "RPG maps (*.rmap)"; }
        });
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            mapCombo.setSelectedItem(fc.getSelectedFile().getAbsolutePath());
    }

    private void pickPaks() {
        JFileChooser fc = new JFileChooser(Path.of(dataDirField.getText(), "paks").toFile());
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) { return f.isDirectory() || f.getName().endsWith(".pak"); }
            public String getDescription() { return "Asset packs (*.pak)"; }
        });
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            for (File f : fc.getSelectedFiles()) pakModel.addElement(f.getAbsolutePath());
    }

    private void refresh() {
        Path dataDir = Path.of(dataDirField.getText());
        Path mapsDir = dataDir.resolve("maps"), paksDir = dataDir.resolve("paks");
        String prevMap = mapCombo.getEditor().getItem().toString();
        boolean hadMap = modelContains(mapCombo, (String) mapCombo.getSelectedItem());
        mapCombo.removeAllItems();
        for (Path p : DataDir.listIn(mapsDir, ".rmap"))
            mapCombo.addItem(p.getFileName().toString());
        if (mapCombo.getItemCount() == 0) mapCombo.addItem("");
        String selected = null;
        if (hadMap) selected = prevMap;
        if (selected == null || !modelContains(mapCombo, selected))
            selected = mapCombo.getItemCount() > 1 ? mapCombo.getItemAt(0) : "";
        mapCombo.setSelectedItem(selected);

        pakModel.clear();
        for (Path p : DataDir.listIn(paksDir, ".pak"))
            pakModel.addElement(p.getFileName().toString());
        log("Data dir: " + dataDir);
    }

    private static boolean modelContains(JComboBox<String> box, Object sel) {
        if (sel == null) return false;
        for (int i = 0; i < box.getItemCount(); i++) if (box.getItemAt(i).equals(sel)) return true;
        return false;
    }

    private void toggle() {
        if (host != null && host.isRunning()) {
            host.stop();
            host = null;
            status.setText("Stopped");
            status.setForeground(new Color(0x80, 0x60, 0x00));
            startButton.setText("Start server");
            saveConfig();
            return;
        }
        start();
    }

    private void start() {
        Path dataDir;
        try {
            dataDir = Path.of(dataDirField.getText().trim());
            if (dataDirField.getText().trim().isEmpty()) dataDir = DataDir.root();
            Files.createDirectories(dataDir.resolve("maps"));
            Files.createDirectories(dataDir.resolve("paks"));
        } catch (IOException | InvalidPathException e) {
            log("Bad data directory: " + e.getMessage());
            return;
        }
        dataDirField.setText(dataDir.toString());

        String mapRef = mapCombo.getEditor().getItem().toString().trim();
        List<String> pakRefs = Collections.list(pakModel.elements());
        int port = (Integer) portSpinner.getValue();

        ServerHost.Config cfg = ServerConfig.resolve(dataDir,
                new ServerConfig.Criteria(!mapRef.isEmpty(), mapRef, true, pakRefs, port),
                this::log);

        host = new ServerHost(this::log);
        try {
            host.start(cfg);
        } catch (IOException e) {
            log("Start failed: " + e.getMessage());
            host = null;
            return;
        }
        if (cfg.map() != null && Files.isRegularFile(cfg.map())) {
            try {
                RMap m = RMapIO.read(cfg.map());
                mapInfo.setText(m.name() + " " + m.width() + "×" + m.height()
                        + " · " + m.entities().size() + " entities · " + m.layers().size() + " layer(s)");
            } catch (Exception e) {
                mapInfo.setText("map read error");
            }
        } else {
            mapInfo.setText("no map loaded");
        }
        status.setText("Running on :" + port);
        status.setForeground(new Color(0x1b, 0x8a, 0x22));
        startButton.setText("Stop server");
        saveConfig();
    }

    private void log(String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(LocalTime.now().format(STAMP) + "  " + line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    // ── Persistence ───────────────────────────────────────────────
    private void loadConfig() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) { p.load(in); } catch (IOException ignored) {}
        dataDirField.setText(p.getProperty("data", DataDir.root().toString()));
        String port = p.getProperty("port");
        try { if (port != null) portSpinner.setValue(Integer.parseInt(port)); } catch (NumberFormatException ignored) {}
        String map = p.getProperty("map");
        if (map != null && !map.isBlank()) mapCombo.setSelectedItem(map);
        String paks = p.getProperty("paks");
        if (paks != null && !paks.isBlank())
            for (String s : paks.split(",")) if (!s.isBlank()) pakModel.addElement(s.trim());
    }

    private void saveConfig() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Properties p = new Properties();
            p.setProperty("data", dataDirField.getText().trim());
            p.setProperty("port", String.valueOf(portSpinner.getValue()));
            Object m = mapCombo.getEditor().getItem();
            if (m != null && !m.toString().isBlank()) p.setProperty("map", m.toString());
            Collection<String> pa = Collections.list(pakModel.elements());
            if (!pa.isEmpty()) p.setProperty("paks", String.join(",", pa));
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                p.store(out, "openRPGator server admin config");
            }
        } catch (IOException ignored) {}
    }
}