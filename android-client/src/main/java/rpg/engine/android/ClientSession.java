package rpg.engine.android;

import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import rpg.engine.network.*;

/**
 * Blocking-IO TCP client: handshake (Hello → [PakList+PakChunks] → Welcome), then snapshots
 * and push-UI events. Downloaded packs are written to the client pak cache
 * ({@code data/pakcache}) so a reconnect to the same server skips downloading again, and the
 * atlas reloads via {@link Listener#pakLoaded(String)}.
 */
final class ClientSession {
    interface Listener {
        void connected(Welcome w);
        void snapshot(Snapshot s);
        void ui(UiLayout u);
        void pakLoaded(String name); // a pak finished buffering to the cache
        void status(String s);
    }
    private final Listener listener;
    private final File pakCacheDir;
    private Socket socket;
    private OutputStream out;
    private final Object lock = new Object();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    ClientSession(Listener l, File pakCacheDir) { listener = l; this.pakCacheDir = pakCacheDir; }

    void connect(String host, int port, String name) {
        disconnect();
        new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setTcpNoDelay(true);
                out = socket.getOutputStream();
                Protocol.write(out, new Hello(name));
                InputStream in = socket.getInputStream();

                Packet p = Protocol.read(in);
                if (p instanceof PakList pl && !pl.packs().isEmpty()) {
                    for (PakList.PakSeq seq : pl.packs()) {
                        long received = 0;
                        File target = pakCacheFile(seq.name());
                        try (FileOutputStream fos = new FileOutputStream(target)) {
                            while (received < seq.sizeBytes()) {
                                Packet chunk = Protocol.read(in);
                                if (!(chunk instanceof PakChunk c) || !c.name().equals(seq.name()))
                                    throw new IOException("corrupt pak stream for " + seq.name());
                                fos.write(c.data());
                                received += c.data().length;
                            }
                        }
                        listener.pakLoaded(seq.name());
                    }
                    p = Protocol.read(in);
                }
                if (!(p instanceof Welcome w)) throw new IOException("server rejected Hello");
                listener.connected(w);
                while (!socket.isClosed()) {
                    Packet q = Protocol.read(in);
                    if (q instanceof Snapshot s) listener.snapshot(s);
                    else if (q instanceof UiLayout u) listener.ui(u);
                    // legacy push-UI packets, kept for compatibility with older servers
                    else if (q instanceof Notify n) listener.ui(UiLayout.notify(n.text()));
                    else if (q instanceof Dialog d) listener.ui(UiLayout.dialog(d.dialogId(), d.text(), d.choices()));
                }
            } catch (Exception e) {
                listener.status("Disconnected: " + e.getMessage());
            }
        }).start();
    }

    private File pakCacheFile(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return new File(pakCacheDir, safe);
    }

    void input(double dx, double dy, int actions) {
        // socket writes are blocking-IO: never touch them on the UI thread
        try {
            sender.execute(() -> {
                try { synchronized (lock) { if (out != null) Protocol.write(out, new Input(dx, dy, actions)); } }
                catch (IOException e) { listener.status("Send failed: " + e.getMessage()); }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    void dialogResponse(long dialogId, int choice) {
        try {
            sender.execute(() -> {
                try { synchronized (lock) { if (out != null) Protocol.write(out, new DialogResponse(dialogId, choice)); } }
                catch (IOException e) { listener.status("Send failed: " + e.getMessage()); }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        out = null;
    }
}