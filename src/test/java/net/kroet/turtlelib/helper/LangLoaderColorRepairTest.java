package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangLoaderColorRepairTest {

    @TempDir
    Path tempDir;

    @Test
    void rewritesLightRedTagsToRedAndKeepsEverythingElse() throws IOException {
        Path file = tempDir.resolve("en_US.yml");
        Files.writeString(file, "# comment\r\ncommand:\r\n  no_permission: <prefix> <light_red>Nope.</light_red>\r\n");

        boolean changed = LangLoader.repairInvalidColorTags(file.toFile(), null, "lang/en_US.yml");

        assertTrue(changed);
        assertEquals("# comment\r\ncommand:\r\n  no_permission: <prefix> <red>Nope.</red>\r\n", Files.readString(file));
    }

    @Test
    void repairsAnAnsiFileByteForByte() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        Path file = tempDir.resolve("de_DE.yml");
        Files.write(file, "command:\n  no_permission: <light_red>Zugriff verweigert, Größe</light_red>\n".getBytes(ansi));

        assertTrue(LangLoader.repairInvalidColorTags(file.toFile(), null, "lang/de_DE.yml"));

        assertArrayEquals("command:\n  no_permission: <red>Zugriff verweigert, Größe</red>\n".getBytes(ansi),
                Files.readAllBytes(file));
    }

    @Test
    void leavesFileWithoutLightRedUntouched() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        String original = "command:\n  no_permission: <prefix> <red>Nein.</red>\n";
        Files.writeString(file, original);

        assertFalse(LangLoader.repairInvalidColorTags(file.toFile(), null, "lang/de_DE.yml"));
        assertEquals(original, Files.readString(file));
    }

    // Only a whole <light_red> or </light_red> tag is repaired; other forms stay,
    // and the file isn't rewritten.
    @Test
    void otherLightRedFormsAreNotRewrittenOrReported() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        String original = "command:\n  no_permission: <!light_red>Nein. light_red> </light_red >\n";
        Files.writeString(file, original);
        FileTime written = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(file, written);

        assertFalse(LangLoader.repairInvalidColorTags(file.toFile(), null, "lang/de_DE.yml"));
        assertEquals(original, Files.readString(file));
        assertEquals(written, Files.getLastModifiedTime(file));
    }
}
