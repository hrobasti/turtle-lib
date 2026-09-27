package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigKeyMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void rewriteRenamesKeyLinesInPlaceAndPreservesComments() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "# a helpful comment",
                "chat-prefix-label: 'Custom'",
                "",
                "update-check:",
                "  # nested comment",
                "  interval-hours: 12",
                ""));

        boolean changed = ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("chat-prefix-label", "chat_prefix_label", "update-check.interval-hours",
                        "update_check.interval_hours"));

        assertTrue(changed);
        String rewritten = Files.readString(file);
        assertEquals(String.join("\n",
                "# a helpful comment",
                "chat_prefix_label: 'Custom'",
                "",
                "update_check:",
                "  # nested comment",
                "  interval_hours: 12",
                ""), rewritten);
    }

    @Test
    void rewriteLeavesUnrelatedLinesAndValuesAlone() throws IOException {
        Path file = tempDir.resolve("lang.yml");
        Files.writeString(file, String.join("\n",
                "log:",
                "  update-provider-ok: '- <provider>: Version <version> [some-thing-with-hyphens]'",
                ""));

        ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "lang.yml",
                Map.of("log.update-provider-ok", "log.update_provider_ok"));

        String rewritten = Files.readString(file);
        assertEquals(String.join("\n",
                "log:",
                "  update_provider_ok: '- <provider>: Version <version> [some-thing-with-hyphens]'",
                ""), rewritten);
    }

    @Test
    void rewriteReturnsFalseWhenNoLegacyKeyIsPresent() throws IOException {
        Path file = tempDir.resolve("config.yml");
        String original = "chat_prefix_label: 'Custom'\n";
        Files.writeString(file, original);

        boolean changed = ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("chat-prefix-label", "chat_prefix_label"));

        assertFalse(changed);
        assertEquals(original, Files.readString(file));
    }

    @Test
    void rewriteLeavesAlreadyMigratedFileWithUnchangedParentAlone() throws IOException {
        Path file = tempDir.resolve("lang.yml");
        String original = "log:\n  update_provider_ok: 'ok'\n";
        Files.writeString(file, original);

        boolean changed = ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "lang.yml",
                Map.of("log.update-provider-ok", "log.update_provider_ok"));

        assertFalse(changed);
        assertEquals(original, Files.readString(file));
    }

    @Test
    void rewriteKeepsEveryByteOfAnAnsiFileWithCrlf() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Charset ansi = Charset.forName("windows-1252");
        Files.write(file, "# Bäume fällen\r\nchat-prefix-label: 'Bäume'\r\n".getBytes(ansi));

        boolean changed = ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("chat-prefix-label", "chat_prefix_label"));

        assertTrue(changed);
        assertArrayEquals("# Bäume fällen\r\nchat_prefix_label: 'Bäume'\r\n".getBytes(ansi), Files.readAllBytes(file));
    }

    @Test
    void rewriteRenamesTheFirstKeyBehindAUtf8ByteOrderMark() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "\uFEFFchat-prefix-label: 'Grün'\n", StandardCharsets.UTF_8);

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("chat-prefix-label", "chat_prefix_label")));

        assertEquals("\uFEFFchat_prefix_label: 'Grün'\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void rewriteWarnsInsteadOfCreatingADuplicateKey() throws IOException {
        Path file = tempDir.resolve("config.yml");
        String original = String.join("\n",
                "update-check:",
                "  interval-hours: 12",
                "update_check:",
                "  provider: 1",
                "");
        Files.writeString(file, original);
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capturingLogger(records);

        assertFalse(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), logger, "config.yml",
                Map.of("update-check.interval-hours", "update_check.interval_hours")));

        // The kept old section stays whole, its keys aren't renamed either.
        assertEquals(original, Files.readString(file));
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("'update-check'") && r.getMessage().contains("already exists")));
    }

    @Test
    void conflictingChainedOrMovingRenamesAreRejected() {
        File file = tempDir.resolve("config.yml").toFile();

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> ConfigKeyMigrator.rewriteLegacyKeysInFile(file, null, "config.yml",
                        Map.of("a.max-radius", "a.radius", "b.max-radius", "b.range")));
        IllegalArgumentException chain = assertThrows(IllegalArgumentException.class,
                () -> ConfigKeyMigrator.rewriteLegacyKeysInFile(file, null, "config.yml",
                        Map.of("a.max-radius", "a.radius", "b.radius", "b.range")));
        IllegalArgumentException move = assertThrows(IllegalArgumentException.class,
                () -> ConfigKeyMigrator.rewriteLegacyKeysInFile(file, null, "config.yml",
                        Map.of("old.key", "new.section.key")));

        assertTrue(conflict.getMessage().contains("'max-radius'"), conflict.getMessage());
        assertTrue(chain.getMessage().startsWith("Chained renames"), chain.getMessage());
        assertTrue(move.getMessage().contains("rewriteKeyPathsInFile"), move.getMessage());
    }

    @Test
    void textInsideValuesIsNeverRenamed() throws IOException {
        Path file = tempDir.resolve("config.yml");
        String before = String.join("\n",
                "motd: |",
                "  max-radius: stays text",
                "note: \"first line",
                "  max-radius: also text\"",
                "list: [max-radius: 1,",
                "  max-radius: 2]",
                "max-radius: 5",
                "");
        Files.writeString(file, before);

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("max-radius", "max_radius")));

        assertEquals(before.replace("max-radius: 5", "max_radius: 5"), Files.readString(file));
    }

    @Test
    void quotedAndCamelCaseKeysCountAsExistingNames() throws IOException {
        Path same = tempDir.resolve("same.yml");
        Files.writeString(same, "'max_blocks': 5\nmax-blocks: 5\n");
        Path changed = tempDir.resolve("changed.yml");
        String changedText = "maxBlocks: 3\nmax-blocks: 5\n";
        Files.writeString(changed, changedText);

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(same.toFile(), null, "same.yml",
                Map.of("max-blocks", "max_blocks")));
        assertFalse(ConfigKeyMigrator.rewriteLegacyKeysInFile(changed.toFile(), null, "changed.yml",
                Map.of("max-blocks", "maxBlocks")));

        assertEquals("'max_blocks': 5\n", Files.readString(same));
        assertEquals(changedText, Files.readString(changed));
    }

    @Test
    void sectionNamesWithCapitalsAreSections() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "Database:\n  host: a\nhostname: b\n");
        List<LogRecord> records = new ArrayList<>();

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), capturingLogger(records), "config.yml",
                Map.of("Database.host", "Database.hostname")));

        assertEquals("Database:\n  hostname: a\nhostname: b\n", Files.readString(file));
        assertTrue(records.stream().noneMatch(r -> r.getLevel() == Level.WARNING));
    }

    @Test
    void theFirstKeyOfAListItemIsRenamedToo() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "rules:\n  - max-blocks: 1\n    min-y: 2\n  - min-y: 3\n");

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("rules.max-blocks", "rules.max_blocks", "rules.min-y", "rules.min_y")));

        assertEquals("rules:\n  - max_blocks: 1\n    min_y: 2\n  - min_y: 3\n", Files.readString(file));
    }

    @Test
    void aFileThatIsntValidYamlStaysUnchanged() throws IOException {
        Path file = tempDir.resolve("config.yml");
        String before = "max-blocks: 1\n  broken: [\n";
        Files.writeString(file, before);
        List<LogRecord> records = new ArrayList<>();

        assertFalse(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), capturingLogger(records), "config.yml",
                Map.of("max-blocks", "max_blocks")));

        assertEquals(before, Files.readString(file));
        assertTrue(records.get(0).getMessage().startsWith("Skipped renaming keys in config.yml: it isn't valid YAML"
                + " (line"), records.get(0).getMessage());
    }

    @Test
    void aResultThatWouldReadBackDifferentlyIsntWritten() throws IOException {
        // Bukkit splits the dotted key into sections, the text rename can't follow.
        Path file = tempDir.resolve("config.yml");
        String before = "limits.max-blocks: 1\nmax-blocks: 2\n";
        Files.writeString(file, before);
        List<LogRecord> records = new ArrayList<>();

        assertFalse(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), capturingLogger(records), "config.yml",
                Map.of("max-blocks", "max_blocks")));

        assertEquals(before, Files.readString(file));
        assertEquals("Could not rename keys in config.yml safely; the file was left unchanged",
                records.get(0).getMessage());
    }

    @Test
    void veryLongLinesDontStopTheRename() throws IOException {
        String words = "word ".repeat(20_000).strip();
        Path file = tempDir.resolve("lang.yml");
        String tail = "items:\n  - " + words + "\n";
        Files.writeString(file, "old-key: 1\nlog:\n  long-help:\n    \"" + words + "\"\n" + tail);

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "lang.yml",
                Map.of("old-key", "new_key", "log.long-help", "log.long_help")));

        assertEquals("new_key: 1\nlog:\n  long_help:\n    \"" + words + "\"\n" + tail, Files.readString(file));
    }

    @Test
    void pathRenameChangesOnlyTheNamedPath() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "database:\n  host: a\nredis:\n  host: b\n");

        assertTrue(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), null, "config.yml",
                Map.of("database.host", "database.hostname")));

        assertEquals("database:\n  hostname: a\nredis:\n  host: b\n", Files.readString(file));
    }

    @Test
    void pathRenameMovesAKeyWithItsCommentsIntoAnotherSection() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "# Server",
                "old:",
                "  # The key",
                "  key: 1",
                "  other: 2",
                "new:",
                "  section:",
                "    existing: x",
                "# End",
                ""));

        assertTrue(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), null, "config.yml",
                Map.of("old.key", "new.section.key")));

        assertEquals(String.join("\n",
                "# Server",
                "old:",
                "  other: 2",
                "new:",
                "  section:",
                "    existing: x",
                "    # The key",
                "    key: 1",
                "# End",
                ""), Files.readString(file));
    }

    @Test
    void pathRenameCreatesMissingSectionsAndReindentsTheBlock() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "limits:",
                "  max: 5",
                "  nested:",
                "    a: 1",
                "    motd: |",
                "      first",
                "        indented",
                "last: 2",
                ""));

        assertTrue(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), null, "config.yml",
                Map.of("limits.nested", "settings.limits.nested")));

        assertEquals(String.join("\n",
                "limits:",
                "  max: 5",
                "settings:",
                "  limits:",
                "    nested:",
                "      a: 1",
                "      motd: |",
                "        first",
                "          indented",
                "last: 2",
                ""), Files.readString(file));
    }

    @Test
    void pathRenameMovesTopLevelKeysInPlaceAndUpTheTree() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "host: a\nport: 1\ndeep:\n  inner:\n    value: x\n");

        assertTrue(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), null, "config.yml",
                Map.of("host", "database.host", "deep.inner.value", "value")));

        assertEquals("database:\n  host: a\nport: 1\ndeep:\n  inner:\nvalue: x\n", Files.readString(file));
    }

    @Test
    void pathRenameFindsAnyKeyAndKeepsItsQuotes() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "Settings:\n  maxBlocks: 5\n  'chat-prefix': x\n");

        assertTrue(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), null, "config.yml",
                Map.of("Settings.maxBlocks", "Settings.max_blocks", "Settings.chat-prefix", "Settings.chat_prefix")));

        assertEquals("Settings:\n  max_blocks: 5\n  'chat_prefix': x\n", Files.readString(file));
    }

    @Test
    void pathRenameNextToAnExistingKeyRemovesOrKeepsTheOldOne() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "old: 1\nstale: 2\nnew:\n  a: 1\n  b: 3\n");
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capturingLogger(records);
        Map<String, String> renames = Map.of("old", "new.a", "stale", "new.b");

        for (int run = 0; run < 2; run++) {
            ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), logger, "config.yml", renames);
        }

        assertEquals("stale: 2\nnew:\n  a: 1\n  b: 3\n", Files.readString(file));
        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getMessage().startsWith("Not renaming 'stale' to 'new.b' in config.yml"),
                warnings.get(0).getMessage());
    }

    @Test
    void pathRenameIntoAPlainValueIsReportedInsteadOfMoved() throws IOException {
        Path file = tempDir.resolve("config.yml");
        String before = "key: 1\nstorage: none\n";
        Files.writeString(file, before);
        List<LogRecord> records = new ArrayList<>();

        assertFalse(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), capturingLogger(records), "config.yml",
                Map.of("key", "storage.key")));

        assertEquals(before, Files.readString(file));
        assertTrue(records.get(0).getMessage().contains("'storage' isn't a section written one key per line"),
                records.get(0).getMessage());
    }

    @Test
    void pathRenamesWhoseResultDependsOnTheirOrderAreRejected() {
        File file = tempDir.resolve("config.yml").toFile();
        List<Map<String, String>> invalid = List.of(
                Map.of("a", "x", "a.b", "y"),
                Map.of("a", "b", "b.c", "d"),
                Map.of("a", "a.inner"),
                Map.of("a", "x", "b", "x.y"),
                Map.of("a..b", "c"));

        for (Map<String, String> renames : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConfigKeyMigrator.rewriteKeyPathsInFile(file, null, "config.yml", renames),
                    renames.toString());
        }
    }

    @Test
    void pathRenameKeepsEveryOtherByteOfAnAnsiFileWithCrlf() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        Path file = tempDir.resolve("config.yml");
        Files.write(file, "# Bäume\r\n\r\nprefix: 'Bäume'\r\ngröße: 1\r\nsection:\r\n  x: 1\r\n".getBytes(ansi));

        assertTrue(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), null, "config.yml",
                Map.of("prefix", "section.prefix", "größe", "size")));

        assertArrayEquals("# Bäume\r\n\r\nsize: 1\r\nsection:\r\n  x: 1\r\n  prefix: 'Bäume'\r\n".getBytes(ansi),
                Files.readAllBytes(file));
    }

    @Test
    void theFirstOfSeveralOldNamesInTheFileWins() throws IOException {
        Path file = tempDir.resolve("lang.yml");
        Files.writeString(file, "command:\n  usage_admin: a\n  usage-admin: a\n");

        assertTrue(ConfigKeyMigrator.rewriteKeyPathsInFile(file.toFile(), null, "lang.yml",
                Map.of("command.usage-admin", "command.usage", "command.usage_admin", "command.usage")));

        assertEquals("command:\n  usage: a\n", Files.readString(file));
    }

    @Test
    void oldDuplicateWithTheSameValueIsRemovedAndEverythingElseStays() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        Path file = tempDir.resolve("de_DE.yml");
        String before = String.join("\r\n",
                "# Meldungen",
                "log:",
                "  felling_already_running: \"Läuft schon\"",
                "  felling-already-running: \"Läuft schon\"",
                "  other: x",
                "");
        Files.write(file, before.getBytes(ansi));
        List<LogRecord> records = new ArrayList<>();

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), capturingLogger(records), "lang/de_DE.yml",
                Map.of("log.felling-already-running", "log.felling_already_running")));

        assertArrayEquals(before.replace("  felling-already-running: \"Läuft schon\"\r\n", "").getBytes(ansi),
                Files.readAllBytes(file));
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        assertTrue(records.get(0).getMessage().endsWith(": log.felling-already-running"), records.get(0).getMessage());
    }

    @Test
    void oldDuplicateOnTheFirstLineKeepsTheByteOrderMark() throws IOException {
        String bom = String.valueOf((char) 0xFEFF);
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, bom + "chat-prefix-label: Tree\nchat_prefix_label: Tree\n", StandardCharsets.UTF_8);

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("chat-prefix-label", "chat_prefix_label")));

        assertEquals(bom + "chat_prefix_label: Tree\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void oldDuplicateWithItsOldDefaultIsRemoved() throws IOException {
        Path file = tempDir.resolve("en_US.yml");
        Files.writeString(file, "log:\n  felling_already_running: \"Already felling\"\n"
                + "  felling-already-running: \"A tree is already being felled\"\n");
        String oldDefaults = "log:\n  felling-already-running: \"A tree is already being felled\"\n";

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "lang/en_US.yml",
                Map.of("log.felling-already-running", "log.felling_already_running"),
                new ByteArrayInputStream(oldDefaults.getBytes(StandardCharsets.UTF_8))));

        assertEquals("log:\n  felling_already_running: \"Already felling\"\n", Files.readString(file));
    }

    @Test
    void oldSectionDuplicateWithItsOldDefaultsIsRemovedWhole() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "update_check:",
                "  enabled: true",
                "  interval_hours: 12",
                "update-check:",
                "  enabled: true",
                "  interval-hours: 24",
                "last: 1",
                ""));
        String oldDefaults = "update-check:\n  enabled: true\n  interval-hours: 24\n";

        assertTrue(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("update-check.interval-hours", "update_check.interval_hours"),
                new ByteArrayInputStream(oldDefaults.getBytes(StandardCharsets.UTF_8))));

        assertEquals("update_check:\n  enabled: true\n  interval_hours: 12\nlast: 1\n", Files.readString(file));
    }

    @Test
    void editedOldDuplicateIsKeptAndReportedOnlyOnce() throws IOException {
        Path file = tempDir.resolve("en_US.yml");
        String before = "log:\n  felling_already_running: \"Busy\"\n  felling-already-running: \"My own text\"\n";
        Files.writeString(file, before);
        String oldDefaults = "log:\n  felling-already-running: \"A tree is already being felled\"\n";
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capturingLogger(records);
        Map<String, String> migrations = Map.of("log.felling-already-running", "log.felling_already_running");

        for (int run = 0; run < 2; run++) {
            assertFalse(ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), logger, "lang/en_US.yml", migrations,
                    new ByteArrayInputStream(oldDefaults.getBytes(StandardCharsets.UTF_8))));
        }

        assertEquals(before, Files.readString(file));
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertTrue(records.get(0).getMessage().startsWith("Not renaming 'log.felling-already-running' to"
                + " 'felling_already_running' in lang/en_US.yml"), records.get(0).getMessage());
        assertTrue(Files.readString(tempDir.resolve(ConfigDefaultsInserter.SEEN_FILE))
                .contains("log.felling-already-running"));
    }

    @Test
    void rewriteTreatsEachListItemAsItsOwnSection() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "rules:",
                "  - name: a",
                "    max-radius: 1",
                "  - name: b",
                "    max-radius: 2",
                ""));

        ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                Map.of("rules.max-radius", "rules.max_radius"));

        assertEquals(String.join("\n",
                "rules:",
                "  - name: a",
                "    max_radius: 1",
                "  - name: b",
                "    max_radius: 2",
                ""), Files.readString(file));
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
