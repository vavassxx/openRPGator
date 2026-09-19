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
import java.util.Properties;

/**
 * Graphical console for the dedicated server. Configures the shared data directory
 * ({@code ~/.openrpgator/data}), selects a port and runs the server in-process so live log
 * output is shown right next to the controls.
 *
 * <p>The server content is not configured by hand: everything is auto-discovered in the
 * {@code host} sub-folder of the data directory (single {@code *.rmap}, {@code *.pak} packs,
 * Lua scripts next to the map).
 *
 * <p>Settings persist to {@code ~/.openrpgator/server.properties}.
 */
public final class ServerAdminFrame extends JFrame {

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".openrpgator", "server.properties");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextField dataDirField = new JTextField(30);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(27800, 1, 65535, 1));
    private final JSpinner tickSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 240, 1));
    private final JButton startButton = new JButton("Start server");
    private final JLabel status = new JLabel("Stopped");
    private final JTextArea log = new JTextArea();
    private final JLabel hostInfo = new JLabel(" ");

    private volatile ServerHost host;

    public ServerAdminFrame() {
        super("openRPGator — Server admin");
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

        c.gridx = 0; c.gridy = 1; p.add(new JLabel("Host content:"), c);
        c.gridx = 1; c.gridwidth = 3; p.add(hostInfo, c);

        c.gridx = 0; c.gridy = 2; p.add(new JLabel("Port:"), c);
        c.gridx = 1; c.gridwidth = 1; p.add(portSpinner, c);

        c.gridx = 0; c.gridy = 3; p.add(new JLabel("Tick rate (Hz):"), c);
        c.gridx = 1; p.add(tickSpinner, c);
        JLabel tickHint = new JLabel("world ticks per second, default 20");
        tickHint.setForeground(new Color(0x80, 0x80, 0x80));
        c.gridx = 2; p.add(tickHint, c);

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

    private void refresh() {
        Path dataDir = Path.of(dataDirField.getText());
        Path hostDir = dataDir.resolve("host");
        java.util.List<Path> maps = DataDir.listIn(hostDir, ".rmap");
        java.util.List<Path> paks = DataDir.listIn(hostDir, ".pak");
        String map = maps.isEmpty() ? "no map" : maps.get(0).getFileName().toString();
        if (maps.size() > 1) map += " (+" + (maps.size() - 1) + " more)";
        hostInfo.setText("map: " + map + "   ·   packs: " + paks.size()
                + "   ·   scripts: auto (next to map)");
        log("Data dir: " + dataDir + "   host: " + hostDir);
        log("Maps: " + maps.stream().map(p -> p.getFileName().toString()).toList()
                + "   Packs: " + paks.stream().map(p -> p.getFileName().toString()).toList());
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
            Files.createDirectories(dataDir.resolve("host"));
        } catch (IOException | InvalidPathException e) {
            log("Bad data directory: " + e.getMessage());
            return;
        }
        dataDirField.setText(dataDir.toString());

        int port = (Integer) portSpinner.getValue();
        int tickHz = (Integer) tickSpinner.getValue();
        ServerHost.Config cfg = ServerConfig.resolve(dataDir, port, tickHz, this::log);

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
                hostInfo.setText(m.name() + " " + m.width() + "×" + m.height()
                        + " · " + m.entities().size() + " entities · " + m.layers().size() + " layer(s)");
            } catch (Exception e) {
                hostInfo.setText("map read error");
            }
        } else {
            hostInfo.setText("no map loaded");
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
        String tickHz = p.getProperty("tick");
        try { if (tickHz != null) tickSpinner.setValue(Integer.parseInt(tickHz)); } catch (NumberFormatException ignored) {}
    }

    private void saveConfig() {
        try {
            Properties p = new Properties();
            p.setProperty("data", dataDirField.getText().trim());
            p.setProperty("port", String.valueOf(portSpinner.getValue()));
            p.setProperty("tick", String.valueOf(tickSpinner.getValue()));
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) { p.store(out, "openRPGator server admin"); }
        } catch (IOException ignored) {}
    }
}