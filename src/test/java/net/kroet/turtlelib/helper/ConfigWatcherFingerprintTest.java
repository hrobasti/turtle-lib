package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.ConfigWatcher.Fingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigWatcherFingerprintTest {

    private static final long MTIME = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    private final List<LogRecord> records = new ArrayList<>();
    private final Logger logger = capturingLogger(records);

    private File write(String text, long modified) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, text);
        assertTrue(file.toFile().setLastModified(modified));
        return file.toFile();
    }

    private Fingerprint fingerprint(File file, Fingerprint previous) {
        return ConfigWatcher.fingerprint(file, previous, logger);
    }

    @Test
    void appearingFileCountsAsAChange() throws IOException {
        File file = tempDir.resolve("config.yml").toFile();
        Fingerprint missing = fingerprint(file, Fingerprint.missing());
        assertFalse(missing.exists());
        assertTrue(missing.sameContent(Fingerprint.missing()));

        Fingerprint present = fingerprint(write("a: 1\n", MTIME), missing);

        assertTrue(present.exists());
        assertFalse(present.sameContent(missing));
    }

    @Test
    void deletedFileCountsAsAChange() throws IOException {
        File file = write("a: 1\n", MTIME);
        Fingerprint present = fingerprint(file, Fingerprint.missing());
        Files.delete(file.toPath());

        Fingerprint gone = fingerprint(file, present);

        assertFalse(gone.exists());
        assertFalse(gone.sameContent(present));
    }

    @Test
    void sameMtimeAndSizeReuseTheHashWithoutReadingTheFile() throws IOException {
        Fingerprint first = fingerprint(write("a: 1\n", MTIME), Fingerprint.missing());
        File file = write("a: 2\n", MTIME);

        Fingerprint second = fingerprint(file, first);

        assertEquals(first.hash(), second.hash());
        assertTrue(second.sameContent(first));
        assertNotEquals(first.hash(), fingerprint(file, Fingerprint.missing()).hash());
    }

    @Test
    void changedContentOfTheSameSizeIsFoundOnceTheMtimeMoves() throws IOException {
        Fingerprint first = fingerprint(write("a: 1\n", MTIME), Fingerprint.missing());

        Fingerprint second = fingerprint(write("a: 2\n", MTIME + 2_000L), first);

        assertEquals(first.size(), second.size());
        assertFalse(second.sameContent(first));
    }

    @Test
    void rewriteWithIdenticalBytesIsNoChange() throws IOException {
        Fingerprint first = fingerprint(write("a: 1\n", MTIME), Fingerprint.missing());

        Fingerprint second = fingerprint(write("a: 1\n", MTIME + 2_000L), first);

        assertNotEquals(first.modified(), second.modified());
        assertTrue(second.sameContent(first));
    }

    @Test
    void unreadableFileGetsAPlaceholderHashAndAWarning() throws IOException {
        File folder = Files.createDirectory(tempDir.resolve("config.yml")).toFile();

        Fingerprint fingerprint = fingerprint(folder, Fingerprint.missing());

        assertTrue(fingerprint.exists());
        assertEquals(-1, fingerprint.hash());
        assertEquals(1, records.size());
        assertEquals("Config watcher hash error for config.yml", records.get(0).getMessage());
    }

    @Test
    void failingCallbackIsLoggedAndTheNextOneStillRuns() {
        RuntimeException failure = new IllegalStateException("broken reload");
        int[] calls = {0};

        assertDoesNotThrow(() -> ConfigWatcher.runCallback(() -> {
            throw failure;
        }, logger));
        assertDoesNotThrow(() -> ConfigWatcher.runCallback(() -> {
            throw new AssertionError("broken too");
        }, logger));
        ConfigWatcher.runCallback(() -> calls[0]++, logger);

        assertEquals(1, calls[0]);
        assertEquals(2, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals("Config watcher callback failed", records.get(0).getMessage());
        assertSame(failure, records.get(0).getThrown());
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
