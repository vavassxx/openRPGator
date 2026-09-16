package rpg.engine.server;

import rpg.engine.core.io.DataDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Headless authoritative dedicated server entry point.
 *
 * <pre>
 *   --data-dir PATH       data directory (default ~/.openrpgator/data)
 *   --map FILE            .rmap; bare name resolves against &lt;data-dir&gt;/maps
 *   --pak FILE[.pak]      repeatable; bare name resolves against &lt;data-dir&gt;/paks,
 *                         directories stream all *.pak inside them
 *   --port PORT           listen port (default 27800)
 *   --help
 * </pre>
 *
 * With no {@code --map} the server serves the single map found under the data maps directory;
 * with no {@code --pak} it streams every pack under the data paks directory. Pressing Ctrl+C
 * (or SIGTERM) stops the server cleanly.
 */
public final class ServerMain {

    private static final int DEFAULT_PORT = 27800;

    public static void main(String[] args) throws Exception {
        Path dataDir = DataDir.root();
        boolean mapGiven = false;
        String mapRef = null;
        boolean paksGiven = false;
        List<String> pakRefs = new ArrayList<>();
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dataDir = Path.of(args[++i]);
                case "--map" -> { mapRef = args[++i]; mapGiven = true; }
                case "--pak" -> { pakRefs.add(args[++i]); paksGiven = true; }
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--help" -> {
                    System.out.println("""
                            --data-dir PATH   data directory (default ~/.openrpgator/data)
                            --map FILE        .rmap; bare name resolves against <data-dir>/maps
                            --pak FILE[.pak]  repeatable; dirs stream all *.pak inside them
                            --port PORT       listen port (default 27800)
                            --help""");
                    return;
                }
                default -> System.err.println("Unknown option: " + args[i]);
            }
        }

        ServerHost.Config cfg = ServerConfig.resolve(dataDir,
                new ServerConfig.Criteria(mapGiven, mapRef, paksGiven, pakRefs, port),
                System.err::println);

        ServerHost host = new ServerHost(System.out::println);
        host.start(cfg);
        CountDownLatch wait = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { host.stop(); wait.countDown(); }));
        try {
            wait.await();
        } catch (InterruptedException e) {
            host.stop();
        }
    }
}