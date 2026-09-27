package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kroet.turtlelib.helper.LegacyDataUpgrade.CarriedValue;
import net.kroet.turtlelib.helper.LegacyDataUpgrade.DroppedValue;
import net.kroet.turtlelib.helper.LegacyDataUpgrade.MissingListEntries;
import net.kroet.turtlelib.helper.LegacyDataUpgrade.RemovedEntry;
import net.kroet.turtlelib.helper.LegacyDataUpgrade.Report;
import net.kroet.turtlelib.helper.LegacyDataUpgrade.SkipReason;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyDataUpgradeTest {
    private static final String OLD_DEFAULT = """
            chat-prefix-label: "Tree"
            update-check:
              enabled: true
              interval-hours: 24
            species-limits:
              oak:
                max-horizontal-radius: 8
              birch:
                max-horizontal-radius: 6
            replant:
              saplings:
                - OAK_SAPLING
                - BIRCH_SAPLING
            soils:
              DIRT: true
              SAND: false
            old-only: 5
            """;

    private static final String NEW_DEFAULT = """
            # Text between the brackets.
            chat_prefix_label: "Tree"

            update_check:
                # Master switch.
                enabled: true
                # Hours between checks.
                interval_hours: 24 # minimum 1

            species_limits:
                oak:
                    max_horizontal_radius: 8
                birch:
                    max_horizontal_radius: 6

            replant:
                # Saplings replanted after felling.
                saplings:
                    - OAK_SAPLING
                    - BIRCH_SAPLING
                    - POPLAR_SAPLING

            # Allowed soils. Add more keys as needed.
            soils:
                DIRT: true
                SAND: false
                MUD: true # new in 2.0
            """;

    private static final Map<String, String> MIGRATIONS = Map.of(
            "chat-prefix-label", "chat_prefix_label",
            "update-check.interval-hours", "update_check.interval_hours",
            "species-limits.oak.max-horizontal-radius", "species_limits.oak.max_horizontal_radius");

    @TempDir
    Path dataDir;

    private final Map<String, String> jar = new HashMap<>(Map.of(
            "config.yml", NEW_DEFAULT,
            "legacy/1.x/config.yml", OLD_DEFAULT,
            "lang/en_US.yml", "greeting: \"Hello\"\n",
            "legacy/1.x/lang/en_US.yml", "greeting: \"Hello\"\n",
            "legacy/1.x/lang/de_DE.yml", "greeting: \"Hallo\"\n"));

    @Test
    void currentDataIsLeftAlone() throws IOException {
        write("config.yml", NEW_DEFAULT);

        Report report = upgrade().run();

        assertTrue(report.notNeeded());
        assertEquals(NEW_DEFAULT, read("config.yml"));
        assertFalse(Files.exists(dataDir.resolve("backup-1.x")));
    }

    @Test
    void unchangedOldFileGetsFreshDefaultsIncludingNewListEntries() throws IOException {
        write("config.yml", OLD_DEFAULT);

        Report report = upgrade().run();

        assertTrue(report.performed());
        assertEquals(NEW_DEFAULT, read("config.yml"));
        assertEquals(OLD_DEFAULT, Files.readString(dataDir.resolve("backup-1.x/config.yml")));
        assertTrue(report.carried().isEmpty());
        assertTrue(report.missingListEntries().isEmpty());
    }

    @Test
    void changedScalarsKeepTheFreshFilesCommentsAndQuoting() throws IOException {
        write("config.yml", OLD_DEFAULT.replace("\"Tree\"", "My Server").replace("24", "12"));

        Report report = upgrade().run();

        String config = read("config.yml");
        assertTrue(config.contains("chat_prefix_label: \"My Server\"\n"), config);
        assertTrue(config.contains("    # Hours between checks.\n    interval_hours: 12 # minimum 1\n"), config);
        assertTrue(report.carried().contains(
                new CarriedValue("config.yml", "update-check.interval-hours", "update_check.interval_hours", "12")));
    }

    @Test
    void segmentRenamesApplyToEverySection() throws IOException {
        write("config.yml", OLD_DEFAULT.replace("max-horizontal-radius: 6", "max-horizontal-radius: 10"));

        Report report = upgrade().run();

        assertEquals(10, yaml("config.yml").getInt("species_limits.birch.max_horizontal_radius"));
        assertEquals("species_limits.birch.max_horizontal_radius", report.carried().get(0).newPath());
    }

    @Test
    void changedListReplacesTheBlockAndReportsNewDefaultEntries() throws IOException {
        write("config.yml", OLD_DEFAULT.replace("    - BIRCH_SAPLING\n", "    - BIRCH_SAPLING\n    - MY_SAPLING\n"));

        Report report = upgrade().run();

        String config = read("config.yml");
        assertTrue(config.contains("    # Saplings replanted after felling.\n    saplings:\n"
                + "        - OAK_SAPLING\n        - BIRCH_SAPLING\n        - MY_SAPLING\n"), config);
        assertEquals(List.of(new MissingListEntries("config.yml", "replant.saplings", List.of("POPLAR_SAPLING"))),
                report.missingListEntries());
    }

    @Test
    void openSectionsTakeAddedAndDropRemovedKeys() throws IOException {
        write("config.yml", OLD_DEFAULT.replace("  SAND: false\n", "  CLAY: true\n"));

        Report report = upgrade().run();

        YamlConfiguration config = yaml("config.yml");
        assertTrue(config.getBoolean("soils.CLAY"));
        assertFalse(config.isSet("soils.SAND"));
        assertTrue(config.getBoolean("soils.MUD"));
        assertTrue(read("config.yml").contains("    MUD: true # new in 2.0\n"));
        assertEquals(List.of(new RemovedEntry("config.yml", "soils.SAND", "soils.SAND")), report.removed());

        List<String> added = ConfigDefaultsInserter.insertMissingKeys(dataDir.resolve("config.yml").toFile(),
                new ByteArrayInputStream(NEW_DEFAULT.getBytes(StandardCharsets.UTF_8)), null, "config.yml",
                List.of("soils"));
        assertEquals(List.of(), added);
        assertFalse(yaml("config.yml").isSet("soils.SAND"));
    }

    @Test
    void valuesWithoutNewKeyAreDroppedAndReported() throws IOException {
        write("config.yml", OLD_DEFAULT.replace("old-only: 5", "old-only: 7\ncustom-key: x"));

        Report report = upgrade().run();

        assertTrue(report.dropped().contains(new DroppedValue("config.yml", "old-only", "7", SkipReason.NO_NEW_KEY)));
        assertTrue(report.dropped()
                .contains(new DroppedValue("config.yml", "custom-key", "x", SkipReason.UNKNOWN_OLD_KEY)));
        assertFalse(yaml("config.yml").isSet("old-only"));
    }

    // chat_prefix_label is a leftover that already has the new name, so without
    // the ignore it would override the unchanged value of chat-prefix-label.
    @Test
    void ignoredOldKeysAreNeitherCarriedNorReported() throws IOException {
        write("config.yml", OLD_DEFAULT.replace("24", "12") + """
                chat_prefix_label: "Leftover"
                leftovers:
                  a: 1
                  b: [x]
                """);

        Report report = upgrade().ignoreOldKeys("config.yml", "chat_prefix_label", "leftovers.*", "update-check")
                .run();

        YamlConfiguration config = yaml("config.yml");
        assertEquals("Tree", config.getString("chat_prefix_label"));
        assertEquals(12, config.getInt("update_check.interval_hours"));
        assertEquals(List.of(new CarriedValue("config.yml", "update-check.interval-hours",
                "update_check.interval_hours", "12")), report.carried());
        assertEquals(List.of(), report.dropped());
    }

    @Test
    void crlfFreshFileKeepsItsLineEndings() throws IOException {
        jar.put("config.yml", NEW_DEFAULT.replace("\n", "\r\n"));
        write("config.yml", OLD_DEFAULT.replace("24", "12").replace("  SAND: false\n", "  CLAY: true\n")
                .replace("    - BIRCH_SAPLING\n", ""));

        upgrade().run();

        String config = read("config.yml");
        assertFalse(config.replace("\r\n", "").contains("\n"), config);
        assertEquals(12, yaml("config.yml").getInt("update_check.interval_hours"));
        assertEquals(List.of("OAK_SAPLING"), yaml("config.yml").getStringList("replant.saplings"));
        assertTrue(yaml("config.yml").getBoolean("soils.CLAY"));
    }

    @Test
    void ansiEncodedOldFileIsCarriedOverAsUtf8() throws IOException {
        Charset ansi = Charset.forName("windows-1252");
        Files.write(dataDir.resolve("config.yml"), OLD_DEFAULT.replace("\"Tree\"", "\"Bäume\"").getBytes(ansi));

        upgrade().run();

        assertEquals("Bäume", yaml("config.yml").getString("chat_prefix_label"));
    }

    @Test
    void keptFilesReturnAndOtherFilesAreCompared() throws IOException {
        write("config.yml", OLD_DEFAULT);
        write("toggles.yml", "players: [a]\n");
        write("lang/en_US.yml", "greeting: 'Hello'\r\n");
        write("lang/de_DE.yml", "greeting: \"Servus\"\n");
        write("lang/xx_XX.yml", "greeting: \"?\"\n");

        Report report = upgrade().keepFiles("toggles.yml").run();

        assertEquals("players: [a]\n", read("toggles.yml"));
        assertEquals("greeting: \"Hello\"\n", read("lang/en_US.yml"));
        assertFalse(Files.exists(dataDir.resolve("lang/de_DE.yml")));
        assertEquals(List.of("lang/de_DE.yml"), report.customizedFiles());
        assertEquals(List.of("lang/xx_XX.yml"), report.filesWithoutBaseline());
    }

    @Test
    void untouchedFilesOfOlderReleasesAreNotReported() throws IOException {
        jar.put("legacy/1.x/lang/de_DE.yml", "greeting: \"Hallo\"\nfarewell: \"Tschüss\"\n");
        jar.put("legacy/1.x/leaf_mappings.yml", "OAK_LOG:\n  - OAK_LEAVES\n  - VINE\n");
        // An older release only needs the keys whose defaults differed.
        jar.put("legacy/1.0/lang/de_DE.yml", "greeting: \"Hallo alt\"\n");
        jar.put("legacy/1.0/lang/fr_FR.yml", "greeting: \"Salut\"\n");
        jar.put("legacy/1.0/leaf_mappings.yml", "OAK_LOG:\n  - OAK_LEAVES\n");
        write("config.yml", OLD_DEFAULT);
        // A 1.0 file that a later 1.x release completed with its new key.
        write("lang/de_DE.yml", "greeting: \"Hallo alt\"\nfarewell: \"Tschüss\"\n");
        write("lang/fr_FR.yml", "greeting: 'Salut'\n");
        write("leaf_mappings.yml", "OAK_LOG:\n  - OAK_LEAVES\n");

        Report report = upgrade().olderDefaultsRoot("legacy/1.0").run();

        assertEquals(List.of(), report.customizedFiles());
        assertEquals(List.of(), report.filesWithoutBaseline());
    }

    @Test
    void realEditsAreStillReportedWithOlderReleases() throws IOException {
        jar.put("legacy/1.x/leaf_mappings.yml", "OAK_LOG:\n  - OAK_LEAVES\n  - VINE\n");
        jar.put("legacy/1.0/leaf_mappings.yml", "OAK_LOG:\n  - OAK_LEAVES\n");
        jar.put("legacy/1.0/lang/de_DE.yml", "greeting: \"Hallo alt\"\n");
        write("config.yml", OLD_DEFAULT);
        write("lang/de_DE.yml", "greeting: \"Servus\"\n");
        write("lang/en_US.yml", "greeting: \"Hello\"\nextra: \"mine\"\n");
        write("leaf_mappings.yml", "OAK_LOG:\n  - VINE\n");

        Report report = upgrade().olderDefaultsRoot("legacy/1.0").run();

        assertEquals(Set.of("lang/de_DE.yml", "lang/en_US.yml", "leaf_mappings.yml"),
                Set.copyOf(report.customizedFiles()));
    }

    @Test
    void fileADowngradeMixedWithTheCurrentVersionIsNotReported() throws IOException {
        jar.put("lang/de_DE.yml", "greeting_text: \"Hallo\"\n");
        write("config.yml", OLD_DEFAULT);
        write("lang/de_DE.yml", "greeting_text: \"Hallo\"\ngreeting: \"Hallo\"\n");

        Report report = upgrade().olderDefaultsRoot("legacy/1.0").run();

        assertEquals(List.of(), report.customizedFiles());
    }

    @Test
    void turtleLibStateFileIsNotReportedAsAFileWithoutBaseline() throws IOException {
        write("config.yml", OLD_DEFAULT);
        write(ConfigDefaultsInserter.SEEN_FILE, "config.yml:\n- soils.DIRT\n");
        write("lang/" + ConfigDefaultsInserter.SEEN_FILE, "reported_old_duplicates: {}\n");

        Report report = upgrade().run();

        assertEquals(List.of(), report.filesWithoutBaseline());
        assertEquals(List.of(), report.customizedFiles());
    }

    @Test
    void withoutOlderReleasesAMissingKeyStillCountsAsAChange() throws IOException {
        jar.put("legacy/1.x/lang/de_DE.yml", "greeting: \"Hallo\"\nfarewell: \"Tschüss\"\n");
        write("config.yml", OLD_DEFAULT);
        write("lang/de_DE.yml", "greeting: \"Hallo\"\n");

        Report report = upgrade().run();

        assertEquals(List.of("lang/de_DE.yml"), report.customizedFiles());
    }

    @Test
    void upgradeWithoutLegacyRootComparesAgainstOlderReleases() throws IOException {
        jar.put("legacy/1.0/config.yml", "language: en_US\nlanguage_set: true\n");
        jar.put("legacy/1.0/lang/de_DE.yml", "greeting: \"Hallo alt\"\n");
        write("config.yml", "language: en_US\nlanguage_set: true\n");
        write("lang/de_DE.yml", "greeting: \"Hallo alt\"\n");
        write("lang/xx_XX.yml", "greeting: \"?\"\n");

        Report report = LegacyDataUpgrade.builder(dataDir.toFile(), this::resource, null)
                .markerKeys("language_set")
                .backupDirName("backup-1.x")
                .freshResources("config.yml", "lang/en_US.yml")
                .olderDefaultsRoot("legacy/1.0")
                .run();

        assertTrue(report.performed());
        assertEquals(List.of(), report.customizedFiles());
        assertEquals(List.of("lang/xx_XX.yml"), report.filesWithoutBaseline());
    }

    // Values active under the newest old release are kept, even if an older release
    // shipped them as its default.
    @Test
    void olderDefaultsDontChangeWhatIsCarriedOver() throws IOException {
        jar.put("legacy/1.0/config.yml", "update-check:\n  interval-hours: 12\n");
        write("config.yml", OLD_DEFAULT.replace("24", "12"));

        Report report = upgrade().olderDefaultsRoot("legacy/1.0").run();

        assertEquals(List.of(
                new CarriedValue("config.yml", "update-check.interval-hours", "update_check.interval_hours", "12")),
                report.carried());
    }

    @Test
    void renamesThatConflictChainOrMoveAreRejectedWhenConfigured() {
        LegacyDataUpgrade.Builder builder = LegacyDataUpgrade.builder(dataDir.toFile(), this::resource, null);

        assertThrows(IllegalArgumentException.class, () -> builder.carryOverValues("config.yml",
                Map.of("a.max-radius", "a.radius", "b.max-radius", "b.range")));
        assertThrows(IllegalArgumentException.class, () -> builder.carryOverValues("config.yml",
                Map.of("a.max-radius", "a.radius", "b.radius", "b.range")));
        assertThrows(IllegalArgumentException.class, () -> builder.carryOverValues("config.yml",
                Map.of("general.prefix", "chat.options.prefix")));
        assertThrows(IllegalArgumentException.class, () -> builder.carryOverValuesByPath("config.yml",
                Map.of("general", "chat", "general.prefix", "prefix")));
    }

    @Test
    void pathRenamesCarryValuesIntoOtherSectionsAndDepths() throws IOException {
        jar.put("legacy/1.x/config.yml", "general:\n  prefix: \"Tree\"\n  radius: 5\nlimits:\n  max: 1\n  min: 0\n");
        jar.put("config.yml", "chat:\n  options:\n    prefix: \"Tree\"\nradius: 5\nbounds:\n  max: 1\n  min: 0\n");
        write("config.yml", "general:\n  prefix: \"My Server\"\n  radius: 8\nlimits:\n  max: 3\n  min: 0\n");

        Report report = LegacyDataUpgrade.builder(dataDir.toFile(), this::resource, null)
                .markerKeys("general")
                .backupDirName("backup-1.x")
                .legacyDefaultsRoot("legacy/1.x")
                .carryOverValuesByPath("config.yml", Map.of("general.prefix", "chat.options.prefix",
                        "general.radius", "radius", "limits", "bounds"))
                .run();

        assertEquals("chat:\n  options:\n    prefix: \"My Server\"\nradius: 8\nbounds:\n  max: 3\n  min: 0\n",
                read("config.yml"));
        assertEquals(List.of(
                new CarriedValue("config.yml", "general.prefix", "chat.options.prefix", "My Server"),
                new CarriedValue("config.yml", "general.radius", "radius", "8"),
                new CarriedValue("config.yml", "limits.max", "bounds.max", "3")), report.carried());
    }

    @Test
    void markerKeyInTheNewBundledFileStopsBeforeAnyFileIsTouched() throws IOException {
        jar.put("config.yml", NEW_DEFAULT + "chat-prefix-label: \"Tree\"\n");
        write("config.yml", OLD_DEFAULT);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> upgrade().run());

        assertTrue(error.getMessage().contains("chat-prefix-label"), error.getMessage());
        assertEquals(OLD_DEFAULT, read("config.yml"));
        assertFalse(Files.exists(dataDir.resolve("backup-1.x")));
    }

    @Test
    void markerTextInsideValuesOfTheNewFileDoesntCount() throws IOException {
        jar.put("config.yml", NEW_DEFAULT + "notes: |\n  chat-prefix-label: is the old name\n");
        write("config.yml", NEW_DEFAULT);

        assertTrue(upgrade().run().notNeeded());
    }

    @Test
    void aDowngradeThatAddsTheOldKeysBackUpgradesAgain() throws IOException {
        write("config.yml", OLD_DEFAULT);
        upgrade().run();
        write("config.yml", read("config.yml") + "chat-prefix-label: \"Tree\"\n");

        Report again = upgrade().run();

        assertTrue(again.performed());
        assertEquals(dataDir.resolve("backup-1.x-2"), again.backupDir());
        assertEquals(NEW_DEFAULT, read("config.yml"));
    }

    @Test
    void openSectionWrittenInlineInTheNewFileKeepsItsShippedEntries() throws IOException {
        jar.put("config.yml", NEW_DEFAULT.replace("soils:\n    DIRT: true\n    SAND: false\n    MUD: true # new in 2.0\n",
                "soils: {DIRT: true, SAND: false, MUD: true} # new in 2.0\n"));
        write("config.yml", OLD_DEFAULT.replace("  SAND: false\n", "  CLAY: true\n"));

        Report report = upgrade().run();

        assertTrue(read("config.yml").endsWith("soils: # new in 2.0\n    DIRT: true\n    MUD: true\n    CLAY: true\n"),
                read("config.yml"));
        assertEquals(List.of(new CarriedValue("config.yml", "soils.CLAY", "soils.CLAY", "true")), report.carried());
        assertEquals(List.of(new RemovedEntry("config.yml", "soils.SAND", "soils.SAND")), report.removed());
    }

    @Test
    void datesAnchorsAndTagsAreCarriedOver() throws IOException {
        jar.put("legacy/1.x/config.yml", "since: 2020-01-01\nradius: 5\nname: \"5\"\nold-only: 1\n");
        jar.put("config.yml", "since: 2020-01-01 # first day\nradius: &r 5\ncopy: *r\nname: !!str 5\n");
        write("config.yml", "since: 2024-06-30\nradius: 8\nname: \"7\"\nold-only: 1\n");

        Report report = LegacyDataUpgrade.builder(dataDir.toFile(), this::resource, null)
                .markerKeys("old-only")
                .backupDirName("backup-1.x")
                .legacyDefaultsRoot("legacy/1.x")
                .carryOverValues("config.yml", Map.of())
                .run();

        assertEquals("since: 2024-06-30 # first day\nradius: &r 8\ncopy: *r\nname: !!str \"7\"\n", read("config.yml"));
        assertEquals(List.of("2024-06-30", "8", "7"), report.carried().stream().map(CarriedValue::value).toList());
        assertEquals(List.of(), report.dropped());
    }

    // After running the old version again, the file holds some values under both
    // names; the one under the new name is what the new version used.
    @Test
    void aValueUnderItsOldAndItsNewNameComesFromTheNewName() throws IOException {
        write("config.yml", OLD_DEFAULT.replace("\"Tree\"", "\"Old\"").replace("interval-hours: 24",
                "interval-hours: 6") + "chat_prefix_label: \"Holz\"\nupdate_check:\n  interval_hours: 24\n");

        Report report = upgrade().run();

        YamlConfiguration config = yaml("config.yml");
        assertEquals("Holz", config.getString("chat_prefix_label"));
        assertEquals(24, config.getInt("update_check.interval_hours"));
        assertEquals(List.of(new CarriedValue("config.yml", "chat_prefix_label", "chat_prefix_label", "Holz")),
                report.carried());
        assertEquals(List.of(), report.dropped());
    }

    @Test
    void hasAnyKeyReadsKeyLinesLikeTheMarkerCheck() throws IOException {
        write("config.yml", "# require_natural_leaves: true\nupdate_check:\n  log_stats: true\n"
                + "notes: |\n  max_blocks: text\nbroken: [\n");
        File file = dataDir.resolve("config.yml").toFile();

        assertTrue(LegacyDataUpgrade.hasAnyKey(file, "update_check.log_stats"));
        assertTrue(LegacyDataUpgrade.hasAnyKey(file, List.of("missing", "update_check")));
        assertFalse(LegacyDataUpgrade.hasAnyKey(file, "require_natural_leaves", "max_blocks", "log_stats"));
        assertFalse(LegacyDataUpgrade.hasAnyKey(dataDir.resolve("missing.yml").toFile(), "update_check"));
        assertFalse(LegacyDataUpgrade.hasAnyKey(file, List.of()));
    }

    @Test
    void existingBackupIsNeverOverwrittenOrMoved() throws IOException {
        write("config.yml", OLD_DEFAULT);
        write("backup-1.x/config.yml", "older: true\n");

        Report report = upgrade().run();

        assertEquals(dataDir.resolve("backup-1.x-2"), report.backupDir());
        assertEquals("older: true\n", read("backup-1.x/config.yml"));
        assertEquals(OLD_DEFAULT, read("backup-1.x-2/config.yml"));
    }

    @Test
    void missingBundledFileAbortsBeforeTouchingAnything() throws IOException {
        write("config.yml", OLD_DEFAULT);
        jar.remove("legacy/1.x/config.yml");

        Report report = upgrade().run();

        assertFalse(report.performed());
        assertFalse(report.errors().isEmpty());
        assertEquals(OLD_DEFAULT, read("config.yml"));
        assertFalse(Files.exists(dataDir.resolve("backup-1.x")));
    }

    @Test
    void failedMoveRestoresTheMovedFiles() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "relies on Windows file locking");
        write("a.yml", "a: 1\n");
        write("config.yml", OLD_DEFAULT);
        write("z.yml", "z: 1\n");

        Report report;
        // java.io streams open files without delete sharing, which blocks a move.
        try (InputStream locked = new FileInputStream(dataDir.resolve("z.yml").toFile())) {
            report = upgrade().run();
        }

        assertFalse(report.performed());
        assertEquals(OLD_DEFAULT, read("config.yml"));
        assertEquals("a: 1\n", read("a.yml"));
        assertFalse(Files.exists(dataDir.resolve("backup-1.x")));
    }

    private LegacyDataUpgrade.Builder upgrade() {
        return LegacyDataUpgrade
                .builder(dataDir.toFile(), this::resource, null)
                .markerKeys("update-check", "chat-prefix-label")
                .backupDirName("backup-1.x")
                .legacyDefaultsRoot("legacy/1.x")
                .freshResources("lang/en_US.yml")
                .carryOverValues("config.yml", MIGRATIONS, "soils");
    }

    private InputStream resource(String path) {
        return jar.containsKey(path) ? new ByteArrayInputStream(jar.get(path).getBytes(StandardCharsets.UTF_8)) : null;
    }

    private void write(String path, String content) throws IOException {
        Path target = dataDir.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private String read(String path) throws IOException {
        return Files.readString(dataDir.resolve(path));
    }

    private YamlConfiguration yaml(String path) throws IOException {
        return YamlLines.parseYaml(read(path));
    }
}
