package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigWatcherYamlCheckTest {

    @TempDir
    Path tempDir;

    @Test
    void validFileHasNoError() throws IOException {
        Path file = write("enable_timber: false\nupdate_check:\n  enabled: false\n", StandardCharsets.UTF_8);
        assertNull(ConfigWatcher.describeYamlError(file.toFile()));
    }

    @Test
    void missingFileHasNoError() {
        assertNull(ConfigWatcher.describeYamlError(tempDir.resolve("config.yml").toFile()));
        assertNull(ConfigWatcher.describeYamlError(null));
    }

    @Test
    void syntaxErrorNamesLineAndColumn() throws IOException {
        Path file = write("enable_timber: false\r\nupdate_check:\r\n  enabled: false\r\n    provider: 1\r\n",
                StandardCharsets.UTF_8);
        assertEquals("line 4, column 5: expected <block end>, but found '<block mapping start>'",
                ConfigWatcher.describeYamlError(file.toFile()));
    }

    @Test
    void topLevelThatIsNoMapIsAnError() throws IOException {
        Path file = write("- just\n- a list\n", StandardCharsets.UTF_8);
        assertEquals("Top level is not a Map.", ConfigWatcher.describeYamlError(file.toFile()));
    }

    // Bukkit replaces bytes that aren't UTF-8 instead of rejecting the file.
    @Test
    void ansiFileIsAcceptedLikeBukkitDoes() throws IOException {
        Path file = write("chat_prefix_label: Bäume\n", Charset.forName("windows-1252"));
        assertNull(ConfigWatcher.describeYamlError(file.toFile()));
    }

    @Test
    void byteOrderMarkIsAccepted() throws IOException {
        Path file = write("﻿language: de_DE\n", StandardCharsets.UTF_8);
        assertNull(ConfigWatcher.describeYamlError(file.toFile()));
    }

    private Path write(String text, Charset charset) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.write(file, text.getBytes(charset));
        return file;
    }
}
