package rpg.engine.client;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import rpg.engine.network.*;

/**
 * Blocking-IO TCP client that connects to a server, receives snapshots
 * and push UI events (notify / dialog) and sends input and dialog responses.
 */
final class DesktopClientSession {
    interface Listener {
        void onConnected(Welcome w);
        void onSnapshot(Snapshot s);
        void onNotify(Notify n);
        void onDialog(Dialog d);
        void onStatus(String s);
    }

    private final Listener listener;
    private volatile Socket socket;
    private volatile OutputStream out;
    private final Object lock = new Object();

    DesktopClientSession(Listener listener) { this.listener = listener; }

    void connect(String host, int port, String name) {
        disconnect();
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setTcpNoDelay(true);
                socket = s;
                out = s.getOutputStream();
                Protocol.write(out, new Hello(name));
                Packet p = Protocol.read(s.getInputStream());
                if (!(p instanceof Welcome w)) throw new IOException("server rejected Hello");
                listener.onConnected(w);
                while (!s.isClosed()) {
                    Packet q = Protocol.read(s.getInputStream());
                    switch (q) {
                        case Snapshot snap -> listener.onSnapshot(snap);
                        case Notify n -> listener.onNotify(n);
                        case Dialog d -> listener.onDialog(d);
                        default -> {}
                    }
                }
            } catch (IOException e) {
                listener.onStatus("Disconnected: " + e.getMessage());
            } finally {
                socket = null;
                out = null;
            }
        }, "openrpg-desktop-client").start();
    }

    void input(double dx, double dy, int actions) {
        try {
            synchronized (lock) {
                if (out != null) Protocol.write(out, new Input(dx, dy, actions));
            }
        } catch (IOException e) {
            listener.onStatus("Send failed: " + e.getMessage());
        }
    }

    void dialogResponse(long dialogId, int choice) {
        try {
            synchronized (lock) {
                if (out != null) Protocol.write(out, new DialogResponse(dialogId, choice));
            }
        } catch (IOException e) {
            listener.onStatus("Send failed: " + e.getMessage());
        }
    }

    void disconnect() {
        try { Socket s = socket; if (s != null) s.close(); } catch (IOException ignored) {}
        socket = null;
        out = null;
    }
}