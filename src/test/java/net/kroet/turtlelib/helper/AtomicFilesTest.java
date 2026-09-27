package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

    @TempDir
    Path dir;

    private List<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writesANewFileWithoutLeavingATempFile() throws IOException {
        Path target = dir.resolve("config.yml");

        AtomicFiles.write(target, "a: 1\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("a: 1\n", Files.readString(target));
        assertEquals(List.of("config.yml"), fileNames());
    }

    @Test
    void replacesTheContentOfAnExistingFile() throws IOException {
        Path target = dir.resolve("config.yml");
        Files.writeString(target, "a: 1\nb: 2\n");

        AtomicFiles.write(target, "a: 3\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("a: 3\n", Files.readString(target));
        assertEquals(List.of("config.yml"), fileNames());
    }

    @Test
    void tempFileLeftByACrashIsReplacedAndRemoved() throws IOException {
        Path target = dir.resolve("de_DE.yml");
        Files.writeString(AtomicFiles.tempFileFor(target), "half writ");

        AtomicFiles.write(target, "x: y\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("x: y\n", Files.readString(target));
        assertEquals(List.of("de_DE.yml"), fileNames());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void lockedFileIsWrittenInPlaceWithAWarning() throws IOException {
        Path target = dir.resolve("config.yml");
        Files.writeString(target, "a: 1\n");
        List<LogRecord> records = new ArrayList<>();
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

        // Like an editor holding the file: it can be written but not replaced.
        try (FileChannel editor = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE,
                ExtendedOpenOption.NOSHARE_DELETE)) {
            AtomicFiles.write(target, "a: 2\n".getBytes(StandardCharsets.UTF_8), logger);
        }

        assertEquals("a: 2\n", Files.readString(target));
        assertEquals(List.of("config.yml"), fileNames());
        assertEquals(1, records.size());
        assertTrue(records.get(0).getMessage().startsWith("Could not replace config.yml in one step"),
                records.get(0).getMessage());
    }

    @Test
    void tempFileIsHiddenAndNoYamlFile() {
        String name = AtomicFiles.tempFileFor(dir.resolve("de_DE.yml")).getFileName().toString();

        assertTrue(name.startsWith("."), name);
        assertFalse(name.endsWith(".yml"), name);
    }
}
