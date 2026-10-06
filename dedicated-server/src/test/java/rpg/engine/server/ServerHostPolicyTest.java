package rpg.engine.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerHostPolicyTest {

    @Test
    void normalizesNames() {
        assertEquals("Astra", ServerHost.normalizePlayerName("  Astra  "));
        assertEquals("Astra Wolf", ServerHost.normalizePlayerName("Astra   Wolf"));
        assertEquals("x", ServerHost.normalizePlayerName("x"));
    }

    @Test
    void rejectsUnusableNames() {
        assertThrows(IllegalArgumentException.class, () -> ServerHost.normalizePlayerName(null));
        assertThrows(IllegalArgumentException.class, () -> ServerHost.normalizePlayerName("   "));
        assertThrows(IllegalArgumentException.class, () -> ServerHost.normalizePlayerName(""));
        assertThrows(IllegalArgumentException.class, () -> ServerHost.normalizePlayerName("\u0001\u0002"));
        String tooLong = "a".repeat(ServerHost.MAX_NAME_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> ServerHost.normalizePlayerName(tooLong));
    }

    @Test
    void stripsControlCharactersButKeepsUnicode() {
        assertEquals("Astra", ServerHost.normalizePlayerName("As\ntr\u0007a"));
        assertEquals("Жук", ServerHost.normalizePlayerName("Жук"));
    }

    @Test
    void canonicalKeyIsCaseInsensitive() {
        assertEquals(ServerHost.canonicalPlayerKey("Astra"), ServerHost.canonicalPlayerKey("aStRa"));
        assertEquals("astra", ServerHost.canonicalPlayerKey("Astra"));
    }

    @Test
    void actionLimiterAllowsCapacityPerSecond() {
        ServerHost.ActionLimiter limiter = new ServerHost.ActionLimiter(5);
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "burst beyond capacity must be rejected");
    }

    @Test
    void actionLimiterRefillsOverTime() throws Exception {
        ServerHost.ActionLimiter limiter = new ServerHost.ActionLimiter(10);
        for (int i = 0; i < 10; i++) limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());
        Thread.sleep(250); // ~2.5 tokens refilled
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void actionLimiterRejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ServerHost.ActionLimiter(0));
        assertThrows(IllegalArgumentException.class, () -> new ServerHost.ActionLimiter(-1));
    }

    @Test
    void configDefaultsAreSaneAndBackwardCompatible() {
        var cfg = new ServerHost.Config(null, null, 1234, 0, null);
        assertEquals(ServerHost.DEFAULT_PLAYER_SPEED, cfg.playerSpeed());
        assertEquals(ServerHost.DEFAULT_VIEW_RADIUS, cfg.viewRadius());
        assertEquals(20, cfg.tickHz(), "invalid tickHz falls back to the server default");
        assertTrue(cfg.paks().isEmpty());
    }

    @Test
    void configRejectsInvalidTuning() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerHost.Config(null, null, 1, 20, null, 0, 24));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerHost.Config(null, null, 1, 20, null, 6, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerHost.Config(null, null, 1, 20, null, Double.NaN, 24));
    }
}