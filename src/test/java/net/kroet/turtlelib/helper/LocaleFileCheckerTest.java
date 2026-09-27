package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kroet.turtlelib.helper.LocaleFileChecker.Finding;
import net.kroet.turtlelib.helper.LocaleFileChecker.Kind;
import net.kroet.turtlelib.helper.LocaleFileChecker.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocaleFileCheckerTest {
    @TempDir
    Path dir;

    private static final String BASE = """
            ui:
              prefix: "<gray>[<prefix_label>]</gray>"
            log:
              loaded: "<prefix> Loaded <count> trees"
              farewell: "<green>Bye</green><newline>"
            """;

    private Path langDir() throws IOException {
        return Files.createDirectories(dir.resolve("lang"));
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(langDir().resolve(name), content, StandardCharsets.UTF_8);
    }

    private Result check() throws IOException {
        return LocaleFileChecker.builder(langDir()).placeholders("count").build().check();
    }

    private static List<String> keysOf(Result result, Kind kind) {
        return result.ofKind(kind).stream().map(Finding::key).toList();
    }

    @Test
    void translatedLocaleHasNoFindingsAndThePrefixMayStayIdentical() throws IOException {
        write("en_US.yml", BASE);
        write("de_DE.yml", BASE.replace("Loaded <count> trees", "<count> Bäume geladen").replace("Bye", "Tschüss"));

        Result result = check();

        assertFalse(result.hasProblems(), result.describeProblems());
        assertTrue(LocaleFileChecker.DEFAULT_IDENTICAL_ALLOWED.contains("ui.prefix"));
    }

    @Test
    void identicalValueIsUntranslatedUnlessAllowlisted() throws IOException {
        write("en_US.yml", BASE);
        write("de_DE.yml", BASE.replace("Loaded <count> trees", "<count> Bäume geladen"));

        Result result = check();
        Result allowed = LocaleFileChecker.builder(langDir()).placeholders("count").identicalAllowed("log.fare*").build().check();

        assertEquals(List.of("log.farewell"), keysOf(result, Kind.UNTRANSLATED));
        assertEquals("de_DE.yml", result.ofKind(Kind.UNTRANSLATED).get(0).file());
        assertFalse(allowed.hasProblems(), allowed.describeProblems());
    }

    @Test
    void missingAndUnknownKeysAreReported() throws IOException {
        write("en_US.yml", BASE);
        write("de_DE.yml", """
                ui:
                  prefix: "<gray>[<prefix_label>]</gray>"
                log:
                  loaded: "<prefix> <count> Bäume geladen"
                  extra: "Nur hier"
                """);

        Result result = check();

        assertEquals(List.of("log.farewell"), keysOf(result, Kind.MISSING_KEY));
        assertEquals(List.of("log.extra"), keysOf(result, Kind.UNKNOWN_KEY));
        assertEquals("de_DE.yml", result.ofKind(Kind.MISSING_KEY).get(0).file());
    }

    @Test
    void wrongPlaceholderIsATagMismatchAndAnUnknownTag() throws IOException {
        write("en_US.yml", BASE);
        write("de_DE.yml", BASE.replace("Loaded <count> trees", "<amount> Bäume geladen"));

        Result result = check();

        assertEquals(List.of("log.loaded"), keysOf(result, Kind.TAG_MISMATCH));
        assertEquals(List.of("log.loaded"), keysOf(result, Kind.UNKNOWN_TAG));
        assertTrue(result.ofKind(Kind.UNKNOWN_TAG).get(0).detail().contains("<amount>"));
    }

    @Test
    void tagNamesWithDashOrDotAreUnknownTags() throws IOException {
        write("en_US.yml", BASE.replace("<green>Bye</green>", "<dark-red>Bye</dark-red>")
                .replace("<prefix> Loaded", "<red.bold><prefix> Loaded"));

        Result result = check();

        assertEquals(List.of("log.loaded", "log.farewell"), keysOf(result, Kind.UNKNOWN_TAG));
        assertTrue(result.describeProblems().contains("uses unknown tag <dark-red>"), result.describeProblems());
    }

    @Test
    void hexColorsAndNegationsAreValidAndMayDifferBetweenLocales() throws IOException {
        write("en_US.yml", BASE.replace("<green>Bye</green>", "<#FF8800>Bye</#FF8800> <!italic>now</!italic>"));
        write("de_DE.yml", BASE.replace("Loaded <count> trees", "<count> Bäume geladen")
                .replace("<green>Bye</green>", "<#00AA00>Tschüss</#00AA00> jetzt"));

        Result result = check();

        assertFalse(result.hasProblems(), result.describeProblems());
    }

    @Test
    void newKeyNamedOnlyInTheRenameMapIsUnused() throws IOException {
        write("en_US.yml", BASE);
        Path src = Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Demo.java"), """
                class Demo {
                    static final Map<String, String> RENAMES = Map.ofEntries(
                            Map.entry("log.old-loaded", "log.loaded"),
                            Map.entry("log.old-farewell", "log.farewell"));

                    void run() {
                        messages.plain("log.loaded");
                    }
                }
                """, StandardCharsets.UTF_8);

        Result result = LocaleFileChecker.builder(langDir())
                .placeholders("count")
                .scanSources(dir.resolve("src/main/java"))
                .renames(Map.of("log.old-loaded", "log.loaded", "log.old-farewell", "log.farewell"))
                .build()
                .check();

        assertEquals(List.of("log.farewell"), keysOf(result, Kind.UNUSED_KEY));
        assertEquals(List.of(), result.ofKind(Kind.KEY_NOT_IN_BASE));
    }

    @Test
    void unknownTagInTheBaseLocaleIsReported() throws IOException {
        write("en_US.yml", BASE.replace("<green>Bye</green>", "<shiny>Bye</shiny>"));

        Result result = check();

        assertEquals(List.of("log.farewell"), keysOf(result, Kind.UNKNOWN_TAG));
        assertEquals(List.of(), result.ofKind(Kind.INVALID_MINIMESSAGE));
    }

    @Test
    void unclosedOrMismatchedTagsFailTheStrictParse() throws IOException {
        write("en_US.yml", BASE.replace("<green>Bye</green>", "<green>Bye")
                .replace("<prefix> Loaded <count> trees", "<red>Loaded <count></green> trees"));

        Result result = check();

        assertEquals(List.of("log.loaded", "log.farewell"), keysOf(result, Kind.INVALID_MINIMESSAGE));
        assertEquals(List.of(), result.ofKind(Kind.UNKNOWN_TAG));
    }

    @Test
    void usageCheckReportsKeysMissingInBaseAndUnusedKeys() throws IOException {
        write("en_US.yml", BASE + """
                  step_one: "One"
                  step_two: "Two"
                toggle:
                  "on": "On"
                  "off": "Off"
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Demo.java"), """
                class Demo {
                    void run() {
                        messages.plain("log.loaded");
                        messages.plain("log.vanished");
                        messages.plain("log.step_" + step);
                        Map.entry("log.old-loaded", "log.loaded");
                        getConfig().getString("update.interval");
                    }
                }
                """, StandardCharsets.UTF_8);

        Result result = LocaleFileChecker.builder(langDir())
                .placeholders("count")
                .scanSources(dir.resolve("src/main/java"))
                .dynamicKeys("toggle.*")
                .ignoreLiterals("log.old-*")
                .build()
                .check();

        List<Finding> missing = result.ofKind(Kind.KEY_NOT_IN_BASE);
        assertEquals(List.of("log.vanished"), missing.stream().map(Finding::key).toList());
        assertTrue(missing.get(0).detail().contains("demo/Demo.java"), missing.get(0).toString());
        assertEquals(List.of("log.farewell"), keysOf(result, Kind.UNUSED_KEY));
    }

    @Test
    void usedKeysCanBePassedDirectlyAndLibraryKeysCountAsUsed() throws IOException {
        write("en_US.yml", BASE);

        Result result = LocaleFileChecker.builder(langDir())
                .placeholders("count")
                .usedKeys(List.of("log.loaded", "log.farewell", "log.gone"))
                .build()
                .check();

        assertEquals(List.of("log.gone"), keysOf(result, Kind.KEY_NOT_IN_BASE));
        assertEquals(List.of(), result.ofKind(Kind.UNUSED_KEY));
        assertTrue(LocaleFileChecker.LIBRARY_KEYS.contains("ui.prefix"));
    }

    @Test
    void withoutUsedKeysTheUsageCheckIsSkipped() throws IOException {
        write("en_US.yml", BASE);

        assertEquals(List.of(), check().ofKind(Kind.UNUSED_KEY));
    }

    @Test
    void brokenFileIsReportedAndTheOthersAreStillChecked() throws IOException {
        write("en_US.yml", BASE);
        write("de_DE.yml", "log:\n  loaded: \"unterminated\n  farewell: [\n");
        write("fr_FR.yml", BASE.replace("<green>Bye</green>", "<green>Au revoir"));

        Result result = check();

        List<Finding> invalid = result.ofKind(Kind.INVALID_FILE);
        assertEquals(1, invalid.size());
        assertEquals("de_DE.yml", invalid.get(0).file());
        assertEquals(List.of("log.farewell"), keysOf(result, Kind.INVALID_MINIMESSAGE));
    }

    @Test
    void missingBaseLocaleIsReported() throws IOException {
        write("de_DE.yml", BASE);

        Result result = LocaleFileChecker.builder(langDir()).baseLocale("en_GB").placeholders("count").build().check();

        assertEquals(List.of("en_GB.yml"), result.findings().stream().map(Finding::file).toList());
    }

    // Like LangLoader, a plugin without en_US compares with its first locale.
    @Test
    void withoutEnUsTheFirstLocaleIsTheBase() throws IOException {
        write("de_DE.yml", BASE.replace("Loaded <count> trees", "<count> Bäume geladen").replace("Bye", "Tschüss"));
        write("fr_FR.yml", "ui:\n  prefix: \"<gray>[<prefix_label>]</gray>\"\nlog:\n  loaded: \"<count> arbres\"\n");
        write("README.yml", "note: x");

        Result result = check();

        List<Finding> missing = result.ofKind(Kind.MISSING_KEY);
        assertEquals(List.of("fr_FR.yml"), missing.stream().map(Finding::file).toList());
        assertEquals("is missing (present in de_DE.yml)", missing.get(0).detail());
        assertEquals(List.of("README.yml"), result.ofKind(Kind.INVALID_FILE).stream().map(Finding::file).toList());
    }

    @Test
    void explicitBaseLocaleStillWins() throws IOException {
        write("en_US.yml", BASE);
        write("de_DE.yml", BASE);

        Result result = LocaleFileChecker.builder(langDir()).baseLocale("de_DE").placeholders("count").build()
                .check();

        assertEquals(List.of("en_US.yml", "en_US.yml"), result.ofKind(Kind.UNTRANSLATED).stream().map(Finding::file)
                .toList());
    }

    // Nothing can be compared with a broken base, but the others' tags still count.
    @Test
    void brokenBaseLocaleStillLetsTheOthersTagsBeChecked() throws IOException {
        write("en_US.yml", "log:\n  loaded: \"unterminated\n");
        write("de_DE.yml", BASE.replace("<green>Bye</green>", "<green>Tschüss"));
        write("fr_FR.yml", BASE.replace("<count>", "<cuont>"));

        Result result = check();

        assertEquals(List.of("en_US.yml"), result.ofKind(Kind.INVALID_FILE).stream().map(Finding::file).toList());
        assertEquals(List.of("de_DE.yml"), result.ofKind(Kind.INVALID_MINIMESSAGE).stream().map(Finding::file).toList());
        assertEquals(List.of("fr_FR.yml"), result.ofKind(Kind.UNKNOWN_TAG).stream().map(Finding::file).toList());
        assertTrue(result.ofKind(Kind.MISSING_KEY).isEmpty());
    }

    // LangLoader only loads files named like en_US.yml.
    @Test
    void fileNotNamedLikeALocaleIsReportedInsteadOfCompared() throws IOException {
        write("en_US.yml", BASE);
        write("de_de.yml", "other: x");
        write("README.yml", "note: x");

        Result result = check();

        assertEquals(List.of("README.yml", "de_de.yml"),
                result.findings().stream().map(Finding::file).sorted().toList());
        assertEquals(2, result.ofKind(Kind.INVALID_FILE).size());
    }

    // MiniMessage reads tag names in any case, and <reset> closes every open tag.
    @Test
    void tagCaseAndResetAreAcceptedLikeAtRuntime() throws IOException {
        write("en_US.yml", BASE + "  error: \"<red>Failed<reset> for <count>\"\n");
        write("de_DE.yml", BASE.replace("Loaded <count> trees", "<COUNT> Bäume geladen")
                .replace("<green>Bye</green>", "<GREEN>Tschüss</Green>")
                + "  error: \"<RED>Fehlgeschlagen<RESET> für <count>\"\n");

        assertFalse(check().hasProblems(), check().describeProblems());
    }

    @Test
    void textAfterTheLastResetIsStillCheckedStrictly() throws IOException {
        write("en_US.yml", BASE + "  error: \"<red>Failed<reset> <green>again\"\n");

        assertEquals(List.of("log.error"), keysOf(check(), Kind.INVALID_MINIMESSAGE));
    }

    @Test
    void topLevelKeyIsFoundInTheSources() throws IOException {
        write("en_US.yml", "greeting: Hello\n" + BASE);
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"),
                "class A { void run() { get(\"greeting\"); get(\"log.loaded\"); get(\"log.farewell\"); } }");

        Result result = LocaleFileChecker.builder(langDir()).placeholders("count").scanSources(src).build().check();

        assertFalse(result.hasProblems(), result.describeProblems());
    }

    @Test
    void collectKeyLiteralsOnlyReturnsLiteralsOfTheGivenSections() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "String a = \"log.one\"; String b = \"config.yml\"; String c = \"log.\";");
        Files.writeString(src.resolve("B.java"), "String a = \"log.one\"; String b = \"ui.two\";");

        Map<String, String> found = LocaleFileChecker.collectKeyLiterals(src, List.of("log", "ui"));

        assertEquals(Map.of("log.one", "A.java", "log.", "A.java", "ui.two", "B.java"), found);
    }
}
