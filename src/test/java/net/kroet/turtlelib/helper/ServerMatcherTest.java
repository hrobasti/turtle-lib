package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ServerMatcherTest {

    @Test
    void withoutRulesEveryVersionButABlankOneIsSupported() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder().build();

        assertTrue(matcher.isSupported("26.3"));
        assertTrue(matcher.isSupported("1.8.8"));
        assertFalse(matcher.isSupported(""));
        assertFalse(matcher.isSupported("  "));
        assertFalse(matcher.isSupported(null));
    }

    @Test
    void exactVersionMatchesOnlyThatVersion() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder().allowVersion(" 26.3.1 ").allowVersion(" ").build();

        assertTrue(matcher.isSupported("26.3.1"));
        assertTrue(matcher.isSupported(" 26.3.1 "));
        assertFalse(matcher.isSupported("26.3"));
        assertFalse(matcher.isSupported("26.3.2"));
        assertFalse(matcher.isSupported("26.3.10"));
    }

    @Test
    void rangeIsInclusiveOnBothEnds() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder().allowRange("26.1", "26.3").build();

        assertTrue(matcher.isSupported("26.1"));
        assertTrue(matcher.isSupported("26.2.5"));
        assertTrue(matcher.isSupported("26.3"));
        assertFalse(matcher.isSupported("26.0.9"));
        assertFalse(matcher.isSupported("26.3.1"));
        assertFalse(matcher.isSupported("26.4"));
    }

    @Test
    void rangeMayBeOpenOnOneSide() {
        ServerMatcher from = ServerMatcher.rulesBuilder().allowRange("26.2", null).build();
        ServerMatcher upTo = ServerMatcher.rulesBuilder().allowRange(" ", "26.1").build();

        assertTrue(from.isSupported("26.2"));
        assertTrue(from.isSupported("27.0"));
        assertFalse(from.isSupported("26.1.9"));
        assertTrue(upTo.isSupported("25.9"));
        assertTrue(upTo.isSupported("26.1"));
        assertFalse(upTo.isSupported("26.1.1"));
    }

    @Test
    void rangeWithoutBoundsAddsNoRule() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder().allowRange(null, " ").build();

        assertTrue(matcher.isSupported("1.0"));
    }

    @Test
    void minorSeriesAcceptsTheSeriesAndItsHotfixes() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder().allowMinorSeries("26.3").build();

        assertTrue(matcher.isSupported("26.3"));
        assertTrue(matcher.isSupported("26.3.1"));
        assertTrue(matcher.isSupported("26.3.12"));
        assertTrue(matcher.isSupported("26.3.1-pre1"));
        assertFalse(matcher.isSupported("26.30"));
        assertFalse(matcher.isSupported("26.4"));
        assertFalse(matcher.isSupported("26.2.9"));
        assertFalse(matcher.isSupported("26"));
    }

    @Test
    void minorSeriesCanBeGivenAsAnyVersionOfTheSeries() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder().allowMinorSeries("26.3.2").build();

        assertTrue(matcher.isSupported("26.3"));
        assertTrue(matcher.isSupported("26.3.5"));
    }

    // Prereleases belong to their series; in a range they still sort below the
    // release, and snapshots match no series.
    @Test
    void preReleaseOfTheFirstVersionIsPartOfTheSeries() {
        ServerMatcher series = ServerMatcher.rulesBuilder().allowMinorSeries("26.3").build();
        ServerMatcher range = ServerMatcher.rulesBuilder().allowRange("26.3", "26.3.99").build();

        assertEquals("26.3", ServerMatcher.minorPrefix("26.3-pre1"));
        assertTrue(series.isSupported("26.3-pre1"));
        assertTrue(series.isSupported("26.3-rc2"));
        assertFalse(series.isSupported("26w14a"));
        assertFalse(range.isSupported("26.3-pre1"));
    }

    @Test
    void minorPrefixKeepsMajorAndMinor() {
        assertEquals("26.3", ServerMatcher.minorPrefix("26.3.1"));
        assertEquals("26.3", ServerMatcher.minorPrefix(" 26.3 "));
        assertEquals("26.3", ServerMatcher.minorPrefix("26.3.1-pre1"));
        assertEquals("26", ServerMatcher.minorPrefix("26"));
        assertNull(ServerMatcher.minorPrefix(" "));
        assertNull(ServerMatcher.minorPrefix(null));
    }

    @Test
    void anyMatchingRuleIsEnough() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder()
                .allowVersion("1.21.4")
                .allowRange("25.0", "25.9")
                .allowMinorSeries("26.3")
                .build();

        assertTrue(matcher.isSupported("1.21.4"));
        assertTrue(matcher.isSupported("25.5"));
        assertTrue(matcher.isSupported("26.3.1"));
        assertFalse(matcher.isSupported("26.2"));
    }

    @Test
    void builderStillRequiresAPlugin() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> ServerMatcher.builder(null));

        assertEquals("plugin", e.getMessage());
    }

    @Test
    void failingMismatchHookStillDisablesThePlugin() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder()
                .allowMinorSeries("26.3")
                .onMismatch(result -> {
                    throw new IllegalStateException("hook broke");
                })
                .build();
        List<LogRecord> logged = new ArrayList<>();
        boolean[] disabled = {false};

        matcher.enforce(matcher.evaluate("Paper", "26.4"), logger(logged), () -> disabled[0] = true);

        assertTrue(disabled[0]);
        assertEquals(Level.WARNING, logged.get(0).getLevel());
        assertEquals("hook broke", logged.get(0).getThrown().getMessage());
        assertEquals(Level.SEVERE, logged.get(1).getLevel());
    }

    // The hook's own warning is missing then, so the matcher logs one instead.
    @Test
    void failingMismatchHookStillWarns() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder()
                .allowMinorSeries("26.3")
                .incompatibleAction(ServerMatcher.IncompatibleAction.WARN_AND_CONTINUE)
                .onMismatch(result -> {
                    throw new IllegalStateException("hook broke");
                })
                .build();
        List<LogRecord> logged = new ArrayList<>();
        boolean[] disabled = {false};

        matcher.enforce(matcher.evaluate("Paper", "26.4"), logger(logged), () -> disabled[0] = true);

        assertFalse(disabled[0]);
        assertEquals(2, logged.size());
        assertTrue(logged.get(1).getMessage().contains("Proceeding"), logged.get(1).getMessage());
    }

    @Test
    void workingMismatchHookReplacesTheWarning() {
        List<String> hooked = new ArrayList<>();
        ServerMatcher matcher = ServerMatcher.rulesBuilder()
                .allowMinorSeries("26.3")
                .incompatibleAction(ServerMatcher.IncompatibleAction.WARN_AND_CONTINUE)
                .onMismatch(result -> hooked.add(result.minecraftVersion()))
                .build();
        List<LogRecord> logged = new ArrayList<>();

        matcher.enforce(matcher.evaluate("Paper", "26.4"), logger(logged), () -> {
        });

        assertEquals(List.of("26.4"), hooked);
        assertTrue(logged.isEmpty());
    }

    // Spigot lacks Paper's getMinecraftVersion(); such a server counts as
    // unsupported.
    @Test
    void serverWithoutPaperApiIsUnsupported() {
        ServerMatcher matcher = ServerMatcher.rulesBuilder().build();
        String version = ServerMatcher.readMinecraftVersion(() -> {
            throw new NoSuchMethodError("getMinecraftVersion");
        });
        ServerMatcher.MatchResult result = matcher.evaluate("CraftBukkit", version);

        assertNull(version);
        assertFalse(result.supported());
        assertTrue(result.message().contains("CraftBukkit") && result.message().contains("no Paper API"),
                result.message());
        assertEquals("unknown", result.minecraftVersion());
        assertEquals("26.3", ServerMatcher.readMinecraftVersion(() -> "26.3"));
        assertTrue(matcher.evaluate("Paper", "26.3").supported());
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
