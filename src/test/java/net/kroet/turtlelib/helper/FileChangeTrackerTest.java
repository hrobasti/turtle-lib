package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.FileChangeTracker.Changes;
import net.kroet.turtlelib.helper.FileChangeTracker.FileKeyChanges;
import net.kroet.turtlelib.helper.FileChangeTracker.KeyChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileChangeTrackerTest {
    @TempDir
    Path dataDir;

    private final List<String> warnings = new ArrayList<>();

    private FileChangeTracker tracker() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return FileChangeTracker.builder(dataDir.toFile(), logger)
                .yamlFile("config.yml")
                .folder("lang", ".yml")
                .build();
    }

    private void write(String path, String content) throws IOException {
        Path target = dataDir.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void firstCallOnlyTakesTheSnapshot() throws IOException {
        write("config.yml", "a: 1\n");
        FileChangeTracker tracker = tracker();

        Changes changes = tracker.compareAndUpdate();

        assertTrue(changes.firstSnapshot());
        assertTrue(changes.isEmpty());
        assertTrue(tracker.hasSnapshot());
    }

    @Test
    void unchangedFilesReportNothing() throws IOException {
        write("config.yml", "a: 1\n");
        write("lang/en_US.yml", "x: y\n");
        FileChangeTracker tracker = tracker();
        tracker.compareAndUpdate();

        Changes changes = tracker.compareAndUpdate();

        assertFalse(changes.firstSnapshot());
        assertTrue(changes.isEmpty());
        assertFalse(changes.hasListedChanges());
    }

    @Test
    void yamlChangesAreListedPerSetting() throws IOException {
        write("config.yml", "a: 1\nsection:\n  b: true\n  list: [X, Y]\nold: 5\n");
        FileChangeTracker tracker = tracker();
        tracker.compareAndUpdate();
        write("config.yml", "# comment only changes nothing\na: 2\nsection:\n  b: true\n  list: [X, Z]\nnew: n\n");

        Changes changes = tracker.compareAndUpdate();

        FileKeyChanges config = changes.keyChanges().get(0);
        assertEquals("config.yml", config.file());
        assertEquals(List.of(new KeyChange("a", "2"), new KeyChange("new", "n"), new KeyChange("old", null),
                new KeyChange("section.list", "X, Z")), config.changes());
        assertEquals("a=2, new=n, old=<removed>, section.list=X, Z", config.describe());
    }

    @Test
    void folderFilesAreReportedAsChangedAddedOrRemoved() throws IOException {
        write("lang/en_US.yml", "x: y\n");
        write("lang/de_DE.yml", "x: y\n");
        write("lang/notes.txt", "ignored\n");
        FileChangeTracker tracker = tracker();
        tracker.compareAndUpdate();
        write("lang/en_US.yml", "x: z\n");
        Files.delete(dataDir.resolve("lang/de_DE.yml"));
        write("lang/fr_FR.yml", "x: y\n");
        write("lang/notes.txt", "still ignored\n");
        write("lang/.seen-defaults.yml", "reported_old_duplicates: {}\n");

        Changes changes = tracker.compareAndUpdate();

        assertEquals(List.of("lang/en_US.yml"), changes.changedFiles());
        assertEquals(List.of("lang/fr_FR.yml"), changes.addedFiles());
        assertEquals(List.of("lang/de_DE.yml"), changes.removedFiles());
        assertEquals(List.of("lang/de_DE.yml", "lang/en_US.yml", "lang/fr_FR.yml"), changes.allChangedFiles());
        assertTrue(changes.hasListedChanges());
        assertFalse(changes.isEmpty());
    }

    @Test
    void invalidYamlIsReportedAndKeepsTheOldSnapshot() throws IOException {
        write("config.yml", "a: 1\n");
        FileChangeTracker tracker = tracker();
        tracker.compareAndUpdate();
        write("config.yml", "a: [unclosed\n");

        Changes broken = tracker.compareAndUpdate();

        assertFalse(broken.isEmpty());
        assertFalse(broken.hasListedChanges());
        assertTrue(broken.keyChanges().isEmpty());
        assertEquals(List.of("config.yml"), broken.unreadableFiles());
        assertEquals(1, warnings.size());

        write("config.yml", "a: 3\n");
        Changes fixed = tracker.compareAndUpdate();
        assertEquals("a=3", fixed.keyChanges().get(0).describe());
    }

    @Test
    void deletedYamlFileListsAllSettingsAsRemoved() throws IOException {
        write("config.yml", "a: 1\n");
        FileChangeTracker tracker = tracker();
        tracker.compareAndUpdate();
        Files.delete(dataDir.resolve("config.yml"));

        assertEquals("a=<removed>", tracker.compareAndUpdate().keyChanges().get(0).describe());
    }
}
