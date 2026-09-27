package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.ConfigWatcher.Outcome;
import net.kroet.turtlelib.helper.ConfigWatcher.SkipReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a poll reports for edited, emptied and deleted files, without a
 * scheduler or plugin.
 */
class ConfigWatcherPollTest {

    private static final String SETTINGS = "enable_timber: false\nmax_blocks: 300\n";

    @TempDir
    Path tempDir;

    private final List<LogRecord> records = new ArrayList<>();
    private final Logger logger = capturingLogger(records);
    private final List<SkipReason> skipped = new ArrayList<>();
    private final ConfigWatcher watcher = new ConfigWatcher(null, () -> null, () -> {
    }, skipped::add, true, 100L);
    private File file;
    private long mtime = 1_700_000_000_000L;

    @BeforeEach
    void startWithSettings() throws IOException {
        file = tempDir.resolve("config.yml").toFile();
        write(SETTINGS);
        watcher.refreshBaseline(file, logger);
    }

    private void write(String text) throws IOException {
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
        mtime += 2_000L;
        assertTrue(file.setLastModified(mtime));
    }

    private Outcome poll() {
        return watcher.poll(file, logger);
    }

    @Test
    void editedFileIsReportedOnce() throws IOException {
        write("enable_timber: true\nmax_blocks: 300\n");

        assertEquals(Outcome.CHANGED, poll());
        assertEquals(Outcome.NONE, poll());
    }

    @Test
    void emptiedFileIsSkippedAndReportedOnce() throws IOException {
        write("");

        assertEquals(Outcome.EMPTY, poll());
        assertEquals(Outcome.NONE, poll());
        write("# only a comment\n\n");
        assertEquals(Outcome.NONE, poll());
    }

    @Test
    void deletedFileIsSkippedAndReportedOnce() throws IOException {
        Files.delete(file.toPath());

        assertEquals(Outcome.MISSING, poll());
        assertEquals(Outcome.NONE, poll());
    }

    @Test
    void eachNewStateIsReportedAgain() throws IOException {
        write("");
        assertEquals(Outcome.EMPTY, poll());
        Files.delete(file.toPath());
        assertEquals(Outcome.MISSING, poll());
        write("\n");
        assertEquals(Outcome.EMPTY, poll());
    }

    // An editor that empties the file before writing it back causes no reload.
    @Test
    void sameSettingsAfterAnEmptyFileAreNoChange() throws IOException {
        write("");
        assertEquals(Outcome.EMPTY, poll());

        write(SETTINGS);

        assertEquals(Outcome.NONE, poll());
        write("");
        assertEquals(Outcome.EMPTY, poll());
    }

    @Test
    void newSettingsAfterADeletedFileAreAChange() throws IOException {
        Files.delete(file.toPath());
        assertEquals(Outcome.MISSING, poll());

        write("enable_timber: true\n");

        assertEquals(Outcome.CHANGED, poll());
    }

    @Test
    void invalidYamlCountsAsAChange() throws IOException {
        write("enable_timber: false\n  max_blocks: 300\n");

        assertEquals(Outcome.CHANGED, poll());
    }

    @Test
    void refreshedBaselineOfAnEmptyFileIsNotReported() throws IOException {
        write("");
        watcher.refreshBaseline(file, logger);

        assertEquals(Outcome.NONE, poll());
        write(SETTINGS);
        assertEquals(Outcome.CHANGED, poll());
    }

    @Test
    void editsWhilePausedAreNotReportedAfterwards() throws IOException {
        watcher.setEnabled(false, file, logger);
        write("enable_timber: true\n");
        watcher.setEnabled(true, file, logger);

        assertEquals(Outcome.NONE, poll());
        write("enable_timber: false\n");
        assertEquals(Outcome.CHANGED, poll());
    }

    @Test
    void anEditRightAfterTurningBackOnIsReported() throws IOException {
        watcher.setEnabled(false, file, logger);
        write("enable_timber: true\n");
        watcher.setEnabled(true, file, logger);
        write("enable_timber: true\nmax_blocks: 33\n");

        assertEquals(Outcome.CHANGED, poll());
        assertEquals(Outcome.NONE, poll());
    }

    @Test
    void keepingEnabledDoesNotResetTheBaseline() throws IOException {
        write("enable_timber: true\n");
        watcher.setEnabled(true, file, logger);

        assertEquals(Outcome.CHANGED, poll());
    }

    @Test
    void onlyFilesWithoutKeysAreEmpty() {
        assertTrue(ConfigWatcher.hasNoKeys(bytes("")));
        assertTrue(ConfigWatcher.hasNoKeys(bytes("  \r\n\n")));
        assertTrue(ConfigWatcher.hasNoKeys(bytes("# comment\n  # indented comment\n")));
        assertTrue(ConfigWatcher.hasNoKeys(bytes("{}\n")));
        assertFalse(ConfigWatcher.hasNoKeys(bytes("a: 1\n")));
        assertFalse(ConfigWatcher.hasNoKeys(bytes("section: {}\n")));
        assertFalse(ConfigWatcher.hasNoKeys(bytes("- a\n- b\n")));
    }

    @Test
    void hookGetsTheReason() {
        watcher.reportSkipped(SkipReason.EMPTY, file, logger);
        watcher.reportSkipped(SkipReason.MISSING, file, logger);

        assertEquals(List.of(SkipReason.EMPTY, SkipReason.MISSING), skipped);
        assertTrue(records.isEmpty());
    }

    @Test
    void withoutHookAnEnglishWarningIsLogged() {
        ConfigWatcher plain = new ConfigWatcher(null, () -> null, () -> {
        }, null, true, 100L);

        plain.reportSkipped(SkipReason.MISSING, file, logger);
        plain.reportSkipped(SkipReason.EMPTY, null, logger);

        assertEquals(2, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals("config.yml was deleted; the current settings stay until it has settings again or the"
                + " plugin reloads.", records.get(0).getMessage());
        assertEquals("The watched file is empty; the current settings stay until it has settings again or the"
                + " plugin reloads.", records.get(1).getMessage());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Logger capturingLogger(List<LogRecord> records) {
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
