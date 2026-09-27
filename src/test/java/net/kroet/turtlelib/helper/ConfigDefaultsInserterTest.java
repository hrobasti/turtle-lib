package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigDefaultsInserterTest {

    private static final String DEFAULTS = String.join("\n",
            "# Header",
            "",
            "# First setting",
            "first: 1",
            "",
            "# Second setting",
            "second: true",
            "",
            "section:",
            "    # Nested one",
            "    one: a",
            "    # Nested two",
            "    two: b",
            "    items:",
            "        - X",
            "        - Y",
            "",
            "# Last setting",
            "last: \"end\"",
            "");

    @TempDir
    Path tempDir;

    private List<String> insert(Path file, List<String> warnings) {
        return insert(file, DEFAULTS, warnings);
    }

    private List<String> insert(Path file, String defaults, List<String> warnings) {
        return insert(file, new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8)), warnings);
    }

    private List<String> insert(Path file, InputStream defaults, List<String> warnings) {
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
        return ConfigDefaultsInserter.insertMissingKeys(file.toFile(), defaults, logger, "config.yml");
    }

    @Test
    void addsMissingTopLevelKeyAfterItsPreviousSiblingWithComments() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "# Header",
                "",
                "# First setting",
                "first: 5",
                "",
                "section:",
                "    one: custom",
                "    two: b",
                "    items:",
                "        - Z",
                "",
                "last: 'mine'",
                ""));

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("second"), added);
        assertEquals(String.join("\n",
                "# Header",
                "",
                "# First setting",
                "first: 5",
                "",
                "# Second setting",
                "second: true",
                "",
                "section:",
                "    one: custom",
                "    two: b",
                "    items:",
                "        - Z",
                "",
                "last: 'mine'",
                ""), Files.readString(file));
    }

    @Test
    void nestedKeysFollowTheTargetsIndentation() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "first: 1",
                "second: false",
                "section:",
                "  one: a",
                "last: x",
                ""));

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("section.two", "section.items"), added);
        assertEquals(String.join("\n",
                "first: 1",
                "second: false",
                "section:",
                "  one: a",
                "  # Nested two",
                "  two: b",
                "  items:",
                "    - X",
                "    - Y",
                "last: x",
                ""), Files.readString(file));
    }

    @Test
    void missingSectionIsInsertedWholeBeforeItsNextSibling() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "# Last setting",
                "last: x",
                ""));

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("first", "second", "section"), added);
        String result = Files.readString(file);
        assertTrue(result.startsWith("# First setting\nfirst: 1\n"), result);
        assertTrue(result.contains("section:\n    # Nested one\n    one: a\n"), result);
        assertTrue(result.endsWith("# Last setting\nlast: x\n"), result);
    }

    @Test
    void keepsCrlfAndAnsiBytesAndEncodesTheBlockLikeTheFile() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        String original = String.join("\r\n",
                "# Bäume",
                "first: 1",
                "",
                "section:",
                "    one: a",
                "    two: b",
                "    items:",
                "        - X",
                "last: x",
                "");
        Path file = tempDir.resolve("config.yml");
        Files.write(file, original.getBytes(ansi));

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("second"), added);
        byte[] expected = String.join("\r\n",
                "# Bäume",
                "first: 1",
                "",
                "# Second setting",
                "second: true",
                "",
                "section:",
                "    one: a",
                "    two: b",
                "    items:",
                "        - X",
                "last: x",
                "").getBytes(ansi);
        assertArrayEquals(expected, Files.readAllBytes(file));
    }

    @Test
    void textValueAnAnsiFileCantStoreIsWrittenWithEscapes() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        Path file = tempDir.resolve("config.yml");
        Files.write(file, "# Bäume\nfirst: 1\n".getBytes(ansi));
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, "first: 1\n# Ein Wert\ngreeting: Cześć, Grüße\n", warnings);

        assertEquals(List.of("greeting"), added);
        assertArrayEquals("# Bäume\nfirst: 1\n# Ein Wert\ngreeting: \"Cze\\u015B\\u0107, Grüße\"\n".getBytes(ansi),
                Files.readAllBytes(file));
        assertEquals(List.of(), warnings);
    }

    @Test
    void blockScalarAnAnsiFileCantStoreBecomesOneEscapedLine() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        Path file = tempDir.resolve("config.yml");
        Files.write(file, "# Bäume\nfirst: 1\n".getBytes(ansi));
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, "first: 1\nmotd: |\n  Cześć\n  Grüße\nlast: x\n", warnings);

        assertEquals(List.of("motd", "last"), added);
        assertArrayEquals("# Bäume\nfirst: 1\nmotd: \"Cze\\u015B\\u0107\\nGrüße\\n\"\nlast: x\n".getBytes(ansi),
                Files.readAllBytes(file));
        assertEquals(List.of(), warnings);
    }

    @Test
    void commentAnAnsiFileCantStoreStillFallsBackToQuestionMarks() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        Path file = tempDir.resolve("config.yml");
        Files.write(file, "# Bäume\nfirst: 1\n".getBytes(ansi));
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, "first: 1\n# Cześć\nsecond: 2\n", warnings);

        assertEquals(List.of("second"), added);
        assertArrayEquals("# Bäume\nfirst: 1\n# Cze??\nsecond: 2\n".getBytes(ansi), Files.readAllBytes(file));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void leavesACompleteFileUntouched() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, DEFAULTS.replace("first: 1", "first: 9"));
        byte[] before = Files.readAllBytes(file);

        assertEquals(List.of(), insert(file, new ArrayList<>()));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void skipsKeysWhoseParentIsAPlainValue() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "first: 1",
                "second: true",
                "section: off",
                "last: x",
                ""));
        byte[] before = Files.readAllBytes(file);

        assertEquals(List.of(), insert(file, new ArrayList<>()));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void invalidYamlIsLeftAloneWithAWarning() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: [unclosed\n");
        byte[] before = Files.readAllBytes(file);
        List<String> warnings = new ArrayList<>();

        assertEquals(List.of(), insert(file, warnings));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void keyPresentWithoutValueIsNotDuplicated() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "first: 1",
                "second:",
                "section:",
                "    one: a",
                "    two: b",
                "    items: []",
                "last: x",
                ""));
        byte[] before = Files.readAllBytes(file);

        assertEquals(List.of(), insert(file, new ArrayList<>()));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void quotedKeysCountAsPresentAndMissingKeysFollowThem() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, String.join("\n",
                "\"first\": 1",
                "'second': false",
                "\"section\":",
                "  'one': a",
                "'last': x",
                ""));

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("section.two", "section.items"), added);
        assertEquals(String.join("\n",
                "\"first\": 1",
                "'second': false",
                "\"section\":",
                "  'one': a",
                "  # Nested two",
                "  two: b",
                "  items:",
                "    - X",
                "    - Y",
                "'last': x",
                ""), Files.readString(file));
    }

    @Test
    void byteOrderMarkStaysInFrontWhenKeysAreAddedFurtherDown() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "\uFEFFfirst: 1\nsecond: false\nsection:\n  one: a\nlast: x\n");

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("section.two", "section.items"), added);
        assertEquals("\uFEFFfirst: 1\nsecond: false\nsection:\n  one: a\n  # Nested two\n  two: b\n  items:\n"
                + "    - X\n    - Y\nlast: x\n", Files.readString(file));
    }

    @Test
    void byteOrderMarkStaysInFrontWhenAKeyIsAddedAtTheTop() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "\uFEFFsecond: false\nsection:\n  one: a\n  two: b\n  items:\n    - X\nlast: x\n");

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("first"), added);
        String result = Files.readString(file);
        assertTrue(result.startsWith("\uFEFF# First setting\nfirst: 1\n"), result);
        assertTrue(result.contains("\nsecond: false\n"), result);
    }

    @Test
    void defaultsWithoutAValueAreAddedOnceAndDontBlockOtherKeys() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\n");
        String defaults = "first: 1\nempty:\ntilde: ~\nsecond: 2\n";
        List<String> warnings = new ArrayList<>();

        assertEquals(List.of("empty", "tilde", "second"), insert(file, defaults, warnings));
        assertEquals(List.of(), insert(file, defaults, warnings));

        assertEquals(defaults, Files.readString(file));
        assertEquals(List.of(), warnings);
    }

    @Test
    void keysWithUmlautsDotsOrSpacesArriveWhereTheyBelong() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\ngröße:\n  x: 1\n");

        List<String> added = insert(file, "first: 1\ngröße:\n  x: 1\n  z: 2\nmy section:\n  y: 2\na.b: 3\n",
                new ArrayList<>());

        assertEquals(List.of("größe.z", "my section", "a.b"), added);
        assertEquals("first: 1\ngröße:\n  x: 1\n  z: 2\nmy section:\n  y: 2\na.b: 3\n", Files.readString(file));
    }

    @Test
    void blockScalarTextKeepsItsIndentationWhenKeysAreReindented() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\nsection:\n  other: x\n");
        String defaults = "first: 1\nsection:\n    other: x\n    motd: |\n        Hello\n          World\n";

        assertEquals(List.of("section.motd"), insert(file, defaults, new ArrayList<>()));

        assertEquals("first: 1\nsection:\n  other: x\n  motd: |\n      Hello\n        World\n", Files.readString(file));
        assertEquals("Hello\n  World\n", YamlLines.parseYaml(Files.readString(file)).getString("section.motd"));
    }

    @Test
    void sectionWithoutAValueTakesItsMissingKeys() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\nsecond: true\nsection:\nlast: x\n");

        List<String> added = insert(file, new ArrayList<>());

        assertEquals(List.of("section.one", "section.two", "section.items"), added);
        assertEquals("first: 1\nsecond: true\nsection:\n    # Nested one\n    one: a\n    # Nested two\n    two: b\n"
                + "    items:\n        - X\n        - Y\nlast: x\n", Files.readString(file));
    }

    @Test
    void aliasSectionIsNamedAsSuchInTheWarning() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "base: &base\n  enabled: false\nupdate_check: *base\n");
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, "base:\n  enabled: true\nupdate_check:\n  enabled: true\n  interval_hours: 12\n",
                warnings);

        assertEquals(List.of(), added);
        assertEquals(List.of("config.yml: can't add the new default key update_check.interval_hours because"
                + " update_check is an alias of another section (like *name); write that section out with one key"
                + " per line to get them"), warnings);
    }

    @Test
    void brokenBundledFileIsNamedInTheWarning() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\n");
        List<String> warnings = new ArrayList<>();

        assertEquals(List.of(), insert(file, "first: 1\nsecond: [\n", warnings));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("Skipped adding new default keys to config.yml: the bundled default"
                + " file isn't valid YAML (line"), warnings.get(0));
    }

    @Test
    void veryLongLinesInTheAdminsFileDontStopTheInsert() throws IOException {
        String words = "word ".repeat(20_000).strip();
        Path file = tempDir.resolve("config.yml");
        String rest = "lore:\n  - " + words + "\nhelp:\n  \"" + words + "\"\n";
        Files.writeString(file, "first: 1\n" + rest);
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, "first: 1\nsecond: 2\n", warnings);

        assertEquals(List.of("second"), added);
        assertEquals("first: 1\nsecond: 2\n" + rest, Files.readString(file));
        assertEquals(List.of(), warnings);
    }

    @Test
    void manyMissingKeysAreAddedInOnePass() throws IOException {
        StringBuilder defaults = new StringBuilder("first: 1\nsection:\n  kept: 1\n");
        for (int i = 0; i < 3000; i++) {
            defaults.append("  key").append(i).append(": ").append(i).append('\n');
        }
        for (int i = 0; i < 3000; i++) {
            defaults.append("top").append(i).append(": ").append(i).append('\n');
        }
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\nsection:\n  kept: 1\n");

        List<String> added = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> insert(file, defaults.toString(), new ArrayList<>()));

        assertEquals(6000, added.size());
        assertEquals(defaults.toString(), Files.readString(file));
    }

    private static final String LIMIT_DEFAULTS = String.join("\n",
            "species_limits:",
            "  oak:",
            "    max_blocks: 300",
            "    max_horizontal_radius: 8",
            "  birch:",
            "    max_blocks: 200",
            "other: 1",
            "");

    @Test
    void sectionWrittenInlineTwoLevelsDeepIsSkippedWithOneWarning() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "species_limits: {oak: {max_blocks: 100}}\n");
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, LIMIT_DEFAULTS, warnings);

        assertEquals(List.of("other"), added);
        assertEquals("species_limits: {oak: {max_blocks: 100}}\nother: 1\n", Files.readString(file));
        assertEquals(List.of("config.yml: can't add the new default keys species_limits.oak.max_horizontal_radius,"
                + " species_limits.birch because species_limits is written inline like {key: value}; write that"
                + " section with one key per line to get them"), warnings);
    }

    @Test
    void sectionWrittenInlineOneLevelDeepIsSkippedWithTheSameWarning() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "update_check: {enabled: false}\n");
        byte[] before = Files.readAllBytes(file);
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, "update_check:\n  enabled: true\n  interval_hours: 12\n", warnings);

        assertEquals(List.of(), added);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(List.of("config.yml: can't add the new default key update_check.interval_hours because"
                + " update_check is written inline like {key: value}; write that section with one key per line to"
                + " get them"), warnings);
    }

    @Test
    void inlineSectionInsideABlockSectionOnlyBlocksItsOwnKeys() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "species_limits:\n  oak: {max_blocks: 100}\n");
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, LIMIT_DEFAULTS, warnings);

        assertEquals(List.of("species_limits.birch", "other"), added);
        assertEquals(String.join("\n",
                "species_limits:",
                "  oak: {max_blocks: 100}",
                "  birch:",
                "    max_blocks: 200",
                "other: 1",
                ""), Files.readString(file));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(" species_limits.oak.max_horizontal_radius because species_limits.oak is"),
                warnings.get(0));
    }

    @Test
    void anchorOrTagAloneStillOpensABlockSection() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "update_check: &check\n  enabled: false\n");
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, "update_check:\n  enabled: true\n  interval_hours: 12\n", warnings);

        assertEquals(List.of("update_check.interval_hours"), added);
        assertEquals("update_check: &check\n  enabled: false\n  interval_hours: 12\n", Files.readString(file));
        assertEquals(List.of(), warnings);
    }

    @Test
    void unexpectedErrorLeavesTheFileUnchangedWithAWarning() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\n");
        byte[] before = Files.readAllBytes(file);
        InputStream broken = new InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("broken stream");
            }
        };
        List<String> warnings = new ArrayList<>();

        List<String> added = insert(file, broken, warnings);

        assertEquals(List.of(), added);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(List.of("Could not add new default keys to config.yml (java.lang.IllegalStateException: broken"
                + " stream); the file was left unchanged"), warnings);
    }

    @Test
    void byteOrderMarkStaysInFrontOfALeadingComment() throws IOException {
        String bom = String.valueOf((char) 0xFEFF);
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, bom + "# Mine\nsecond: false\n");

        List<String> added = insert(file, "first: 1\nsecond: true\n", new ArrayList<>());

        assertEquals(List.of("first"), added);
        assertEquals(bom + "first: 1\n# Mine\nsecond: false\n", Files.readString(file));
    }

    private static final String OPEN_DEFAULTS = String.join("\n",
            "other: 1",
            "soils:",
            "    DIRT: true",
            "    SAND: false",
            "");

    private List<String> insertOpen(Path file, String defaults) {
        return ConfigDefaultsInserter.insertMissingKeys(file.toFile(),
                new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8)), null, "config.yml",
                List.of("soils"));
    }

    @Test
    void firstRunKeepsDeletedOpenSectionEntriesDeleted() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "soils:\n    DIRT: true\n");

        assertEquals(List.of("other"), insertOpen(file, OPEN_DEFAULTS));
        assertFalse(Files.readString(file).contains("SAND"));
        String seen = Files.readString(tempDir.resolve(ConfigDefaultsInserter.SEEN_FILE));
        assertTrue(seen.contains("soils.SAND"), seen);
        assertTrue(seen.contains("\nconfig.yml:\n"), seen);
    }

    @Test
    void newOpenSectionDefaultIsOfferedOnce() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "other: 1\nsoils:\n    DIRT: true\n");
        insertOpen(file, OPEN_DEFAULTS);
        String withMud = OPEN_DEFAULTS + "    MUD: true\n";

        assertEquals(List.of("soils.MUD"), insertOpen(file, withMud));
        Files.writeString(file, "other: 1\nsoils:\n    DIRT: true\n");
        assertEquals(List.of(), insertOpen(file, withMud));
    }

    @Test
    void openSectionDefaultSkippedForAnInlineSectionIsOfferedOnceItIsBlockStyle() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "other: 1\nsoils:\n    DIRT: true\n");
        insertOpen(file, OPEN_DEFAULTS);
        String withMud = OPEN_DEFAULTS + "    MUD: true\n";

        Files.writeString(file, "other: 1\nsoils: {DIRT: true}\n");
        assertEquals(List.of(), insertOpen(file, withMud));
        Files.writeString(file, "other: 1\nsoils:\n    DIRT: true\n");

        assertEquals(List.of("soils.MUD"), insertOpen(file, withMud));
    }

    @Test
    void openSectionDefaultsUnderAPlainValueAreOfferedOnceItIsASection() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "other: 1\nsoils: off\n");

        assertEquals(List.of(), insertOpen(file, OPEN_DEFAULTS));
        Files.writeString(file, "other: 1\nsoils:\n    DIRT: true\n");

        assertEquals(List.of("soils.SAND"), insertOpen(file, OPEN_DEFAULTS));
    }

    @Test
    void missingOpenSectionIsStillInsertedWhole() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "other: 1\n");

        assertEquals(List.of("soils"), insertOpen(file, OPEN_DEFAULTS));
        assertTrue(Files.readString(file).contains("    SAND: false\n"));
    }

    @Test
    void withoutOpenSectionsNoStateFileIsWritten() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "first: 1\n");

        insert(file, new ArrayList<>());

        assertFalse(Files.exists(tempDir.resolve(ConfigDefaultsInserter.SEEN_FILE)));
    }
}
