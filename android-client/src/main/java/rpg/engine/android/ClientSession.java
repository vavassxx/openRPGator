package rpg.engine.android;

import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import rpg.engine.network.*;

final class ClientSession {
    interface Listener {
        void connected(Welcome w);
        void snapshot(Snapshot s);
        void notify(String text);
        void dialog(Dialog d);
        void status(String s);
        void pakStart(long totalBytes);            // total pak bytes expected (0 = none)
        void pakProgress(long received, long totalBytes);
        boolean pakCached(String name, long size); // true => skip buffering for this pak
        void pakDone(String name, byte[] data);
    }
    private final Listener listener;
    private Socket socket;
    private OutputStream out;
    private final Object lock = new Object();

    ClientSession(Listener l) { listener = l; }

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

                // Handshake: optional PakList, then the chunk stream, then Welcome.
                // Packs already cached (by name + size) are skipped, but every chunk still
                // has to be read off the wire to reach Welcome.
                Packet p = Protocol.read(in);
                if (p instanceof PakList pl) {
                    List<PakList.PakSeq> seqs = pl.packs();
                    long total = 0;
                    for (PakList.PakSeq s : seqs) total += s.sizeBytes();
                    listener.pakStart(total);
                    boolean[] cached = new boolean[seqs.size()];
                    for (int i = 0; i < seqs.size(); i++)
                        cached[i] = listener.pakCached(seqs.get(i).name(), seqs.get(i).sizeBytes());
                    long received = 0;
                    for (int i = 0; i < seqs.size(); i++) {
                        PakList.PakSeq seq = seqs.get(i);
                        byte[] buf = cached[i] ? null : new byte[seq.sizeBytes()];
                        int offset = 0;
                        while (offset < seq.sizeBytes()) {
                            Packet q = Protocol.read(in);
                            if (!(q instanceof PakChunk c) || !c.name().equals(seq.name()))
                                throw new IOException("corrupt pak stream for " + seq.name());
                            if (c.offset() != offset)
                                throw new IOException("pak chunk offset desync for " + seq.name());
                            if (buf != null) System.arraycopy(c.data(), 0, buf, offset, c.data().length);
                            offset += c.data().length;
                            received += c.data().length;
                            listener.pakProgress(received, total);
                        }
                        if (buf != null) listener.pakDone(seq.name(), buf);
                    }
                    p = Protocol.read(in);
                }
                if (!(p instanceof Welcome w)) throw new IOException("server rejected Hello");
                listener.connected(w);
                while (!socket.isClosed()) {
                    Packet q = Protocol.read(in);
                    if (q instanceof Snapshot s) listener.snapshot(s);
                    else if (q instanceof Notify n) listener.notify(n.text());
                    else if (q instanceof Dialog d) listener.dialog(d);
                }
            } catch (Exception e) {
                listener.status("Disconnected: " + e.getMessage());
            }
        }).start();
    }

    void input(double dx, double dy, int actions) {
        try { synchronized (lock) { if (out != null) Protocol.write(out, new Input(dx, dy, actions)); } }
        catch (IOException e) { listener.status("Send failed: " + e.getMessage()); }
    }

    void dialogResponse(long dialogId, int choice) {
        try { synchronized (lock) { if (out != null) Protocol.write(out, new DialogResponse(dialogId, choice)); } }
        catch (IOException e) { listener.status("Send failed: " + e.getMessage()); }
    }

    void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        out = null;
    }
}