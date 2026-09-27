package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The interval logic of a watcher that isn't started, so no scheduler or plugin
 * is involved.
 */
class ConfigWatcherIntervalTest {

    private final ConfigWatcher watcher = new ConfigWatcher(null, () -> null, () -> {
    }, null, true, 100L);

    @Test
    void unchangedIntervalIsNoChange() {
        assertFalse(watcher.setIntervalSeconds(5));
        assertEquals(100L, watcher.intervalTicks());
    }

    @Test
    void newIntervalIsTakenOverOnce() {
        assertTrue(watcher.setIntervalSeconds(30));
        assertEquals(600L, watcher.intervalTicks());
        assertFalse(watcher.setIntervalSeconds(30));
    }

    @Test
    void intervalIsAtLeastOneSecond() {
        assertEquals(20L, ConfigWatcher.secondsToTicks(0));
        assertEquals(20L, ConfigWatcher.secondsToTicks(-5));
        assertEquals(20L, ConfigWatcher.secondsToTicks(1));
        assertTrue(watcher.setIntervalSeconds(0));
        assertEquals(20L, watcher.intervalTicks());
        assertFalse(watcher.setIntervalSeconds(1));
    }
}
