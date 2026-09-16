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

                Packet p = Protocol.read(in);
                if (p instanceof PakList pl && !pl.packs().isEmpty()) {
                    for (PakList.PakSeq seq : pl.packs()) {
                        int received = 0;
                        while (received < seq.sizeBytes()) {
                            Packet chunk = Protocol.read(in);
                            if (!(chunk instanceof PakChunk c) || !c.name().equals(seq.name()))
                                throw new IOException("corrupt pak stream for " + seq.name());
                            received += c.data().length;
                        }
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
