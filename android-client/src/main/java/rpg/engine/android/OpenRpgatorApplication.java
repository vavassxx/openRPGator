package rpg.engine.android;

import android.app.Application;
import android.util.Log;

/** Application-level diagnostics so crashes in any Activity are persisted. */
public final class OpenRpgatorApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final AppLogger logger = AppLogger.get(this);
        logger.info("Application process started");
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                logger.error("Uncaught exception on " + thread.getName(), error);
            } catch (Throwable loggingFailure) {
                Log.e("openRPGator", "Failed to persist crash log", loggingFailure);
            }
            if (previous != null) previous.uncaughtException(thread, error);
            else System.exit(1);
        });
    }
}
