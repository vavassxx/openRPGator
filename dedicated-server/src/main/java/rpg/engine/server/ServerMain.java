package rpg.engine.server;

import rpg.engine.core.io.DataDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Headless authoritative dedicated server entry point.
 *
 * <pre>
 *   --data-dir PATH        data directory (default ~/.openrpgator/data)
 *   --port PORT            listen port (default 27800)
 *   --tick-rate HZ         world tick rate in Hz (default 20, 1..240)
 *   --help
 * </pre>
 *
 * The server content lives in the {@code host} sub-folder of the data directory: the single
 * {@code *.rmap} there is auto-selected, every {@code *.pak} is streamed to clients and Lua
 * scripts next to the map are pulled in automatically. Pressing Ctrl+C (or SIGTERM) stops the
 * server cleanly.
 */
public final class ServerMain {

    private static final int DEFAULT_PORT = 27800;

    public static void main(String[] args) throws Exception {
        Path dataDir = DataDir.root();
        int port = DEFAULT_PORT;
        int tickHz = ServerConfig.DEFAULT_TICK_HZ;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dataDir = Path.of(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--tick-rate" -> {
                    try { tickHz = Integer.parseInt(args[++i]); }
                    catch (NumberFormatException e) { System.err.println("Invalid --tick-rate: " + args[i]); }
                }
                case "--help" -> {
                    System.out.println("""
                            --data-dir PATH   data directory (default ~/.openrpgator/data)
                            --port PORT       listen port (default 27800)
                            --tick-rate HZ    world tick rate in Hz (default 20, 1..240)
                            --help""");
                    return;
                }
                default -> System.err.println("Unknown option: " + args[i]);
            }
        }

        ServerHost.Config cfg = ServerConfig.resolve(dataDir, port, tickHz, System.err::println);

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