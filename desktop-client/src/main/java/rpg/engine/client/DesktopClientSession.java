package rpg.engine.client;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import rpg.engine.network.*;

/**
 * Blocking-IO TCP client that connects to a server, receives snapshots
 * and push UI events (notify / dialog) and sends input and dialog responses.
 *
 * Handshake: Hello -> (PakList + PakChunks)* -> Welcome. Packs are re-skipped on the
 * client side when already cached (by name + size), but every chunk still has to be
 * read off the wire to reach Welcome.
 */
final class DesktopClientSession {
    interface Listener {
        void onConnected(Welcome w);
        void onSnapshot(Snapshot s);
        void onUi(UiLayout u);
        void onStatus(String s);
        void onPakStart(long totalBytes);            // total pak bytes expected (0 = none)
        void onPakProgress(long received, long totalBytes);
        boolean onPakCached(String name, long size); // true => skip buffering for this pak
        void onPakDone(String name, byte[] data);
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

                InputStream in = s.getInputStream();
                long total = 0;
                long received = 0;

                // Read the handshake: an (optional, possibly empty) PakList, then the
                // chunk stream, then Welcome.
                Packet p = Protocol.read(in);
                if (p instanceof PakList pl) {
                    List<PakList.PakSeq> seqs = pl.packs();
                    total = seqs.stream().mapToLong(PakList.PakSeq::sizeBytes).sum();
                    listener.onPakStart(total);
                    boolean[] cached = new boolean[seqs.size()];
                    for (int i = 0; i < seqs.size(); i++)
                        cached[i] = listener.onPakCached(seqs.get(i).name(), seqs.get(i).sizeBytes());
                    for (int i = 0; i < seqs.size(); i++) {
                        PakList.PakSeq seq = seqs.get(i);
                        byte[] buf = cached[i] ? null : new byte[seq.sizeBytes()];
                        int offset = 0;
                        while (offset < seq.sizeBytes()) {
                            Packet q = Protocol.read(in);
                            if (!(q instanceof PakChunk chunk) || !chunk.name().equals(seq.name()))
                                throw new IOException("corrupt pak stream for " + seq.name());
                            if (chunk.offset() != offset)
                                throw new IOException("pak chunk offset desync for " + seq.name()
                                        + " (expected " + offset + ")");
                            if (buf != null) System.arraycopy(chunk.data(), 0, buf, offset, chunk.data().length);
                            offset += chunk.data().length;
                            received += chunk.data().length;
                            listener.onPakProgress(received, total);
                        }
                        if (buf != null) listener.onPakDone(seq.name(), buf);
                    }
                    p = Protocol.read(in); // Welcome comes after the pak stream
                }
                if (!(p instanceof Welcome w)) throw new IOException("server rejected Hello");
                listener.onConnected(w);
                while (!s.isClosed()) {
                    Packet q = Protocol.read(in);
                    switch (q) {
                        case Snapshot snap -> listener.onSnapshot(snap);
                        case UiLayout u -> listener.onUi(u);
                        // legacy push-UI packets, kept for compatibility with older servers
                        case Notify n -> listener.onUi(UiLayout.notify(n.text()));
                        case Dialog d -> listener.onUi(UiLayout.dialog(d.dialogId(), d.text(), d.choices()));
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