package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangLoaderSyncFileTest {

    private static final Charset ANSI = Charset.forName("windows-1252");
    private static final String DEFAULTS = String.join("\n",
            "command:",
            "  reload: Reloaded",
            "  help: Help",
            "ui:",
            "  prefix: '<gray>[Demo]</gray>'",
            "");
    private static final String GERMAN_DEFAULTS = String.join("\n",
            "command:",
            "  reload: Neu geladen",
            "  help: Hilfe für Bäume",
            "ui:",
            "  prefix: '<gray>[Bäume]</gray>'",
            "");

    @TempDir
    Path tempDir;

    private final List<LogRecord> records = new ArrayList<>();
    private final Logger logger = capturingLogger(records);

    private LangLoader.SyncResult sync(Path file, String defaults, Map<String, String> migrations) {
        return LangLoader.syncFile(file.toFile(), "lang/" + file.getFileName(), defaults, migrations, null, logger);
    }

    @Test
    void aRejectedRenameListThrowsBeforeTheFileIsRead() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        byte[] original = "command:\n  reload: x\n    broken: y\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        for (Map<String, String> renames : List.of(
                Map.of("a.max-radius", "a.radius", "b.max-radius", "b.range"),
                Map.of("a.max-radius", "a.radius", "b.radius", "b.range"),
                Map.of("log.old-key", "log.section.new_key"))) {
            assertThrows(IllegalArgumentException.class, () -> sync(file, DEFAULTS, renames), renames.toString());
            assertThrows(IllegalArgumentException.class, () -> sync(tempDir.resolve("missing.yml"), DEFAULTS, renames),
                    renames.toString());
        }

        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(records.isEmpty());
    }

    @Test
    void invalidYamlStaysByteIdenticalAndIsReportedOnce() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        byte[] original = String.join("\r\n",
                "# Eigene Texte",
                "command:",
                "  reload: Neu geladen",
                "    help: Hilfe",
                "").getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        LangLoader.SyncResult result = sync(file, DEFAULTS, Map.of());

        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(result.unreadable());
        assertTrue(result.added().isEmpty());
        assertNull(result.target());
        assertEquals(1, records.size());
        LogRecord warning = records.get(0);
        assertEquals(Level.WARNING, warning.getLevel());
        assertEquals("lang/de_DE.yml isn't valid YAML (line 4, column 5: expected <block end>, but found"
                + " '<block mapping start>'); using the bundled texts and leaving the file unchanged until it is fixed",
                warning.getMessage());
    }

    @Test
    void invalidYamlSkipsLegacyRenameAndColorRepair() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        byte[] original = String.join("\n",
                "command:",
                "  old-help: <light_red>Hilfe</light_red>",
                "  reload: [Neu geladen",
                "").getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        LangLoader.SyncResult result = sync(file, DEFAULTS, Map.of("command.old-help", "command.help"));

        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(result.unreadable());
        assertTrue(result.added().isEmpty());
        assertEquals(1, records.size());
        assertTrue(records.get(0).getMessage().contains("isn't valid YAML (line "), records.get(0).getMessage());
    }

    @Test
    void validFileGetsMissingKeysMerged() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        Files.writeString(file, "command:\n  reload: Neu geladen\n");

        LangLoader.SyncResult result = sync(file, DEFAULTS, Map.of());

        assertFalse(result.unreadable());
        assertEquals(List.of("command.help", "ui.prefix"), result.added());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("Neu geladen", saved.getString("command.reload"));
        assertEquals("Help", saved.getString("command.help"));
        assertEquals("<gray>[Demo]</gray>", saved.getString("ui.prefix"));
        assertTrue(records.isEmpty());
    }

    @Test
    void validFileIsRenamedAndRepairedBeforeTheMerge() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        Files.writeString(file, "command:\n  reload: <light_red>Neu geladen</light_red>\n  old-help: Hilfe\n");

        LangLoader.SyncResult result = sync(file, DEFAULTS, Map.of("command.old-help", "command.help"));

        assertFalse(result.unreadable());
        assertEquals(List.of("ui.prefix"), result.added());
        assertEquals("<red>Neu geladen</red>", result.target().getString("command.reload"));
        assertEquals("Hilfe", result.target().getString("command.help"));
    }

    @Test
    void oldKeysADowngradeAddedBackAreRemovedOnce() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        Files.writeString(file, "command:\n  reload: Neu geladen\n  help: Hilfe für Bäume\n"
                + "  reload-done: Konfiguration neu geladen\nui:\n  prefix: '<gray>[Bäume]</gray>'\n");
        Map<String, String> migrations = Map.of("command.reload-done", "command.reload");
        String oldDefaults = "command:\n  reload-done: Konfiguration neu geladen\n";

        LangLoader.SyncResult first = LangLoader.syncFile(file.toFile(), "lang/de_DE.yml", GERMAN_DEFAULTS, migrations,
                oldDefaults, logger);
        LangLoader.SyncResult second = LangLoader.syncFile(file.toFile(), "lang/de_DE.yml", GERMAN_DEFAULTS, migrations,
                oldDefaults, logger);

        assertEquals("command:\n  reload: Neu geladen\n  help: Hilfe für Bäume\nui:\n  prefix: '<gray>[Bäume]</gray>'\n",
                Files.readString(file));
        assertTrue(first.added().isEmpty());
        assertTrue(second.added().isEmpty());
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
    }

    @Test
    void plainValueWhereTheDefaultsHaveASectionIsKeptWithAWarning() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        Files.writeString(file, "command: Mein Text\n");

        LangLoader.SyncResult result = sync(file, DEFAULTS, Map.of());

        assertEquals(List.of("ui.prefix"), result.added());
        assertEquals("Mein Text", YamlConfiguration.loadConfiguration(file.toFile()).getString("command"));
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals("lang/de_DE.yml: kept your text at command, where the bundled file has a section; the texts"
                + " in that section use the defaults until you rename or remove it", records.get(0).getMessage());
    }

    @Test
    void plainValueInAnAnsiFileIsKeptToo() throws IOException, InvalidConfigurationException {
        Path file = tempDir.resolve("de_DE.yml");
        Files.write(file, "command: Grüße\n".getBytes(ANSI));

        LangLoader.SyncResult result = sync(file, GERMAN_DEFAULTS, Map.of());

        assertEquals(List.of("ui.prefix"), result.added());
        YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(new String(Files.readAllBytes(file), ANSI));
        assertEquals("Grüße", saved.getString("command"));
        assertEquals("<gray>[Bäume]</gray>", saved.getString("ui.prefix"));
        assertEquals(Level.WARNING, records.get(0).getLevel());
    }

    @Test
    void utf8FileWithUmlautsIsMergedAndSavedAsUtf8LikeBefore() throws IOException, InvalidConfigurationException {
        Path file = tempDir.resolve("de_DE.yml");
        Files.writeString(file, "command:\n  reload: Grüße\n");

        LangLoader.SyncResult result = sync(file, GERMAN_DEFAULTS, Map.of());

        assertEquals(List.of("command.help", "ui.prefix"), result.added());
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(YamlLines.isValidUtf8(bytes));
        YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        assertEquals("Grüße", saved.getString("command.reload"));
        assertEquals("Hilfe für Bäume", saved.getString("command.help"));
    }

    @Test
    void completeAnsiFileIsReadAsWindows1252AndLeftAlone() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        byte[] original = String.join("\r\n",
                "command:",
                "  reload: Bäume neu geladen",
                "  help: Hilfe für Bäume",
                "ui:",
                "  prefix: '<gray>[Bäume]</gray>'",
                "").getBytes(ANSI);
        Files.write(file, original);

        LangLoader.SyncResult result = sync(file, DEFAULTS, Map.of());

        assertFalse(result.unreadable());
        assertTrue(result.added().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals("Bäume neu geladen", result.target().getString("command.reload"));
        assertTrue(records.isEmpty(), () -> records.get(0).getMessage());
    }

    @Test
    void ansiFileKeepsItsUmlautsAndGetsMissingKeysInItsOwnEncoding() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        Files.write(file, "command:\r\n  reload: Grüße aus Köln\r\n".getBytes(ANSI));

        LangLoader.SyncResult result = sync(file, GERMAN_DEFAULTS, Map.of());

        assertEquals(List.of("command.help", "ui.prefix"), result.added());
        byte[] expected = String.join("\r\n",
                "command:",
                "  reload: Grüße aus Köln",
                "  help: Hilfe für Bäume",
                "ui:",
                "  prefix: '<gray>[Bäume]</gray>'",
                "").getBytes(ANSI);
        byte[] bytes = Files.readAllBytes(file);
        assertArrayEquals(expected, bytes);
        assertFalse(YamlLines.isValidUtf8(bytes));
        assertEquals("Grüße aus Köln", result.target().getString("command.reload"));
        assertEquals("Hilfe für Bäume", result.target().getString("command.help"));
        assertTrue(records.isEmpty(), () -> records.get(0).getMessage());
    }

    @Test
    void ansiFileWithLightRedIsRepairedAndKeepsItsEncoding() throws IOException {
        Path file = tempDir.resolve("de_DE.yml");
        String text = "command:\n  reload: <light_red>Grüße</light_red>\n  help: Hilfe\nui:\n  prefix: x\n";
        Files.write(file, text.getBytes(ANSI));

        LangLoader.SyncResult result = sync(file, GERMAN_DEFAULTS, Map.of());

        assertArrayEquals(text.replace("light_red", "red").getBytes(ANSI), Files.readAllBytes(file));
        assertEquals("<red>Grüße</red>", result.target().getString("command.reload"));
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        assertEquals("Replaced invalid <light_red> color tags with <red> in lang/de_DE.yml", records.get(0).getMessage());
    }

    @Test
    void ansiFileGetsTextsItsEncodingCantStoreAsEscapes() throws IOException {
        Path file = tempDir.resolve("pl_PL.yml");
        Files.write(file, "command:\n  reload: Köln\n".getBytes(ANSI));
        String polish = "command:\n  reload: Załadowano\n  help: 'Pomoc \"łódź\" \\ 🌲'\n";

        LangLoader.SyncResult result = sync(file, polish, Map.of());

        assertEquals(List.of("command.help"), result.added());
        String written = new String(Files.readAllBytes(file), ANSI);
        assertEquals("command:\n  reload: Köln\n  help: \"Pomoc \\\"\\u0142ód\\u017A\\\" \\\\ \\U0001F332\"\n", written);
        assertEquals("Pomoc \"łódź\" \\ 🌲", result.target().getString("command.help"));
        assertTrue(records.isEmpty(), () -> records.get(0).getMessage());
    }

    @Test
    void ansiFileGetsAMultiLineTextItsEncodingCantStoreAsOneEscapedLine() throws IOException {
        Path file = tempDir.resolve("pl_PL.yml");
        Files.write(file, "command:\n  reload: Köln\n".getBytes(ANSI));
        // Laid out like warn.unsupported_server_version in the plugins' lang files.
        String polish = String.join("\n",
                "command:",
                "    reload: Załadowano",
                "warn:",
                "    unsupported_server_version:",
                "        \"<yellow>Ostrzeżenie: wymagany <gold><required_server></gold>",
                "        lub nowszy, masz <gold><mc_version></gold>.\"",
                "");

        LangLoader.SyncResult result = sync(file, polish, Map.of());

        assertEquals(List.of("warn.unsupported_server_version"), result.added());
        String folded = "<yellow>Ostrzeżenie: wymagany <gold><required_server></gold> lub nowszy, masz"
                + " <gold><mc_version></gold>.";
        assertEquals("command:\n  reload: Köln\nwarn:\n  unsupported_server_version: \"<yellow>Ostrze\\u017Cenie:"
                + " wymagany <gold><required_server></gold> lub nowszy, masz <gold><mc_version></gold>.\"\n",
                new String(Files.readAllBytes(file), ANSI));
        assertEquals(folded, result.target().getString("warn.unsupported_server_version"));
        assertTrue(records.isEmpty(), () -> records.get(0).getMessage());

        assertTrue(sync(file, polish, Map.of()).added().isEmpty());
        assertTrue(records.isEmpty(), () -> records.get(0).getMessage());
    }

    @Test
    void ansiFileSkipsKeysWhoseTextCantBeWrittenAsAnEscapedValue() throws IOException {
        Path file = tempDir.resolve("pl_PL.yml");
        byte[] original = "command:\n  reload: Köln\n".getBytes(ANSI);
        Files.write(file, original);
        String polish = "command:\n  reload: Załadowano\n  lines:\n    - Łódź\n";

        LangLoader.SyncResult result = sync(file, polish, Map.of());

        assertTrue(result.added().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(1, records.size());
        assertEquals("lang/pl_PL.yml: can't add command.lines because the file isn't saved as UTF-8 and these texts"
                + " contain characters its encoding (Windows-1252) can't store; save the file as UTF-8 to get them",
                records.get(0).getMessage());
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
