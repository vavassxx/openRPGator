package rpg.engine.client;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import rpg.engine.network.*;

/**
 * In-process authoritative server for the desktop client.
 * Mirrors android-client/LocalServerBackend (Java 17, no Android deps).
 */
final class DesktopLocalServer {
    interface Listener { void onStatus(String message); }

    private final Listener listener;
    private volatile boolean running;
    private ServerSocket server;
    private ExecutorService clients;
    private final Map<Long, Player> players = new ConcurrentHashMap<>();
    private long nextId = 1;

    DesktopLocalServer(Listener listener) { this.listener = listener; }

    synchronized void start(int port) throws IOException {
        if (running) return;
        server = new ServerSocket(port);
        server.setReuseAddress(true);
        clients = Executors.newCachedThreadPool();
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "openrpg-local-server");
        acceptor.setDaemon(true);
        acceptor.start();
        listener.onStatus("Local server listening on " + port);
    }

    synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (clients != null) clients.shutdownNow();
        players.clear();
        listener.onStatus("Local server stopped");
    }

    boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                clients.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) listener.onStatus("Local server error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        Player player = null;
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Packet hello = Protocol.read(in);
            if (!(hello instanceof Hello h)) return;
            synchronized (this) { player = new Player(nextId++, h.name()); }
            players.put(player.id, player);
            Protocol.write(out, new Welcome(player.id));
            sendSnapshot(out);
            while (running && !s.isClosed()) {
                Packet packet = Protocol.read(in);
                if (packet instanceof Input input) {
                    player.x += input.dx() * 0.1;
                    player.y += input.dy() * 0.1;
                    sendSnapshot(out);
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (player != null) players.remove(player.id);
        }
    }

    private void sendSnapshot(OutputStream out) throws IOException {
        List<Snapshot.EntityState> entities = new ArrayList<>();
        for (Player p : players.values())
            entities.add(new Snapshot.EntityState(p.id, p.x, p.y, 0));
        Protocol.write(out, new Snapshot(entities));
    }

    private static final class Player {
        final long id;
        final String name;
        double x, y;
        Player(long id, String name) { this.id = id; this.name = name; }
    }
}
