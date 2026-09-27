package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@link LangLoader#loadActiveLocale} against a real data folder, with the
 * bundled lang files served from memory instead of a plugin jar.
 */
class LangLoaderActiveLocaleTest {

    private static final String BUNDLED_EN = String.join("\n",
            "ui:",
            "  prefix: '<gray>[<prefix_label>]</gray>'",
            "command:",
            "  reload: Reloaded",
            "  help: Help",
            "  version: 'Version <version>'",
            "");
    // Lacks command.version, like a translation that isn't done yet.
    private static final String BUNDLED_DE = String.join("\n",
            "ui:",
            "  prefix: '<gray>[<prefix_label>]</gray>'",
            "command:",
            "  reload: Neu geladen",
            "  help: Hilfe",
            "");
    private static final Map<String, String> BUNDLED = Map.of("lang/en_US.yml", BUNDLED_EN, "lang/de_DE.yml",
            BUNDLED_DE, "legacy/1.x/lang/de_DE.yml", "command:\n  reload-done: Konfiguration neu geladen\n");

    @TempDir
    Path dataFolder;

    private final List<LogRecord> records = new ArrayList<>();
    private final List<String> added = new ArrayList<>();

    private YamlConfiguration load(String locale) {
        return LangLoader.loadActiveLocale(new TestSource(BUNDLED), locale, Map.of(), null, added);
    }

    private Path langFile(String locale) {
        return dataFolder.resolve("lang").resolve(locale + ".yml");
    }

    private byte[] writeLangFile(String locale, String text) throws IOException {
        Files.createDirectories(dataFolder.resolve("lang"));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Files.write(langFile(locale), bytes);
        return bytes;
    }

    @Test
    void missingFileIsCopiedFromTheBundledResource() throws IOException {
        YamlConfiguration cfg = load("de_DE");

        assertEquals(BUNDLED_DE, Files.readString(langFile("de_DE")));
        assertTrue(added.isEmpty());
        assertEquals("Neu geladen", cfg.getString("command.reload"));
        assertEquals("Version <version>", cfg.getString("command.version"));
        assertTrue(records.isEmpty());
    }

    @Test
    void validFileGetsMissingKeysAddedAndKeepsTheAdminsTexts() throws IOException {
        writeLangFile("de_DE", "command:\n  reload: Frisch geladen\n");

        YamlConfiguration cfg = load("de_DE");

        assertEquals(List.of("ui.prefix", "command.help"), added);
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(langFile("de_DE").toFile());
        assertEquals("Frisch geladen", saved.getString("command.reload"));
        assertEquals("Hilfe", saved.getString("command.help"));
        assertEquals("Frisch geladen", cfg.getString("command.reload"));
        assertEquals("Version <version>", cfg.getString("command.version"));
    }

    @Test
    void invalidFileStaysUntouchedAndTheBundledTextsAreUsed() throws IOException {
        byte[] original = writeLangFile("de_DE", "command:\n  reload: Frisch geladen\n    help: Hilfe\n");

        YamlConfiguration cfg = load("de_DE");

        assertArrayEquals(original, Files.readAllBytes(langFile("de_DE")));
        assertTrue(added.isEmpty());
        assertEquals("Neu geladen", cfg.getString("command.reload"));
        assertEquals("Version <version>", cfg.getString("command.version"));
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertTrue(records.get(0).getMessage().startsWith("lang/de_DE.yml isn't valid YAML (line 3, column "),
                records.get(0).getMessage());
    }

    @Test
    void ansiFileIsReadAsWindows1252AndStaysAnsi() throws IOException {
        Files.createDirectories(dataFolder.resolve("lang"));
        Files.write(langFile("de_DE"), "command:\n  reload: Grüße aus Köln\n".getBytes(Charset.forName("windows-1252")));

        YamlConfiguration cfg = load("de_DE");

        assertEquals("Grüße aus Köln", cfg.getString("command.reload"));
        assertEquals("Hilfe", cfg.getString("command.help"));
        assertEquals(List.of("ui.prefix", "command.help"), added);
        assertFalse(YamlLines.isValidUtf8(Files.readAllBytes(langFile("de_DE"))));
        assertTrue(records.isEmpty(), () -> records.get(0).getMessage());
    }

    @Test
    void aRejectedRenameListThrowsBeforeAnyFileIsWritten() {
        Map<String, String> chain = Map.of("command.reload-done", "command.reload", "ui.reload", "ui.done");

        assertThrows(IllegalArgumentException.class,
                () -> LangLoader.loadActiveLocale(new TestSource(BUNDLED), "de_DE", chain, null, added));

        assertFalse(Files.exists(dataFolder.resolve("lang")));
    }

    @Test
    void oldKeyWithItsOldDefaultIsRemovedUsingTheLegacyRoot() throws IOException {
        writeLangFile("de_DE", BUNDLED_DE + "  reload-done: Konfiguration neu geladen\n");

        LangLoader.loadActiveLocale(new TestSource(BUNDLED), "de_DE", Map.of("command.reload-done", "command.reload"),
                "legacy/1.x/", added);

        assertEquals(BUNDLED_DE, Files.readString(langFile("de_DE")));
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
    }

    @Test
    void invalidEnUsFileUsesTheBundledEnUsWithoutMakingItItsOwnDefaults() throws IOException {
        byte[] original = writeLangFile("en_US", "command: [unclosed\n");

        YamlConfiguration cfg = load("en_US");

        assertArrayEquals(original, Files.readAllBytes(langFile("en_US")));
        assertEquals("Reloaded", cfg.getString("command.reload"));
        assertNull(cfg.getDefaults());
    }

    @Test
    void localeWithoutBundledFileIsFilledFromEnUs() throws IOException {
        YamlConfiguration cfg = load("fr_FR");

        assertEquals(List.of("ui.prefix", "command.reload", "command.help", "command.version"), added);
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(langFile("fr_FR").toFile());
        assertEquals("Reloaded", saved.getString("command.reload"));
        assertEquals("Reloaded", cfg.getString("command.reload"));
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals("No bundled translation for fr_FR; using the en_US texts in lang/fr_FR.yml. Available: de_DE, en_US",
                records.get(0).getMessage());
    }

    @Test
    void theHintAboutAMissingTranslationComesOnceAndTheAdminsFileStays() throws IOException {
        load("fr_FR");
        Files.writeString(langFile("fr_FR"), "command:\n  reload: Rechargé\n");

        YamlConfiguration cfg = load("fr_FR");

        assertEquals("Rechargé", cfg.getString("command.reload"));
        assertEquals(1, records.size());
    }

    @Test
    void invalidFileOfALocaleWithoutBundledFileFallsBackToEnUs() throws IOException {
        byte[] original = writeLangFile("fr_FR", "command: [unclosed\n");

        YamlConfiguration cfg = load("fr_FR");

        assertArrayEquals(original, Files.readAllBytes(langFile("fr_FR")));
        assertEquals("Reloaded", cfg.getString("command.reload"));
        assertTrue(added.isEmpty());
    }

    @Test
    void localeSpellingsAreNormalizedAndUnusableValuesFallBackToEnUs() {
        assertEquals("Neu geladen", load("de-de").getString("command.reload"));
        assertTrue(Files.isRegularFile(langFile("de_DE")));

        assertEquals("Reloaded", load("../config").getString("command.reload"));
        assertTrue(Files.isRegularFile(langFile("en_US")));
        assertFalse(Files.exists(dataFolder.resolve("config.yml")));
        assertEquals(1, records.size());
        assertTrue(records.get(0).getMessage().startsWith("Invalid language '../config'"),
                records.get(0).getMessage());
    }

    @Test
    void withoutEnUsTheFirstBundledLocaleIsTheBase() throws IOException {
        TestSource source = new TestSource(Map.of("lang/fr_FR.yml", "command:\n  reload: Rechargé\n",
                "lang/de_DE.yml", BUNDLED_DE));

        YamlConfiguration cfg = LangLoader.loadActiveLocale(source, "es_ES", Map.of(), null, added);

        assertEquals("Neu geladen", cfg.getString("command.reload"));
        assertEquals("Neu geladen", YamlConfiguration.loadConfiguration(langFile("es_ES").toFile()).getString(
                "command.reload"));
        assertEquals("No bundled translation for es_ES; using the de_DE texts in lang/es_ES.yml. Available: de_DE,"
                + " fr_FR", records.get(0).getMessage());
        assertFalse(Files.exists(langFile("en_US")));
    }

    @Test
    void withoutEnUsMissingTextsComeFromTheBaseAndNoEnUsFileIsCreated() {
        TestSource source = new TestSource(Map.of("lang/fr_FR.yml", "command:\n  reload: Rechargé\n",
                "lang/de_DE.yml", BUNDLED_DE));

        LangLoader.ensureBundledLocales(source);
        YamlConfiguration cfg = LangLoader.loadActiveLocale(source, "fr_FR", Map.of(), null, added);

        assertEquals("Rechargé", cfg.getString("command.reload"));
        assertEquals("Hilfe", cfg.getString("command.help"));
        assertTrue(Files.isRegularFile(langFile("de_DE")));
        assertFalse(Files.exists(langFile("en_US")));
        assertEquals(List.of("de_DE", "fr_FR"), LangLoader.knownLocales(source));
        assertTrue(records.isEmpty(), () -> records.get(0).getMessage());
    }

    @Test
    void withoutEnUsAnInvalidLanguageFallsBackToTheBase() {
        TestSource source = new TestSource(Map.of("lang/de_DE.yml", BUNDLED_DE));

        YamlConfiguration cfg = LangLoader.loadActiveLocale(source, "german", Map.of(), null, added);

        assertEquals("Neu geladen", cfg.getString("command.reload"));
        assertFalse(Files.exists(langFile("en_US")));
        assertEquals("Invalid language 'german' - expected a code like de_DE, using de_DE instead",
                records.get(0).getMessage());
    }

    @Test
    void withoutAnyBundledFileTheWarningSaysTheTextsAreMissing() {
        TestSource source = new TestSource(Map.of());

        YamlConfiguration cfg = LangLoader.loadActiveLocale(source, "de_DE", Map.of(), null, added);

        assertTrue(cfg.getKeys(true).isEmpty());
        assertNull(LangLoader.baseLocale(source));
        assertEquals("No bundled translation for de_DE and no bundled lang files; lang/de_DE.yml starts empty, and"
                + " texts it lacks show as their keys", records.get(0).getMessage());
    }

    private final class TestSource implements LangLoader.Source {
        private final Logger logger = capturingLogger(records);
        private final Map<String, String> bundled;

        TestSource(Map<String, String> bundled) {
            this.bundled = bundled;
        }

        @Override
        public File dataFolder() {
            return dataFolder.toFile();
        }

        @Override
        public InputStream resource(String path) {
            String text = bundled.get(path);
            return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }

        // Mirrors JavaPlugin#saveResource(path, false) for a file that doesn't exist
        // yet.
        @Override
        public void saveResource(String path) {
            String text = bundled.get(path);
            if (text == null) {
                throw new IllegalArgumentException("The embedded resource '" + path + "' cannot be found");
            }
            try {
                Path out = dataFolder.resolve(path);
                Files.createDirectories(out.getParent());
                Files.writeString(out, text);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public Set<String> bundledLocales() {
            Set<String> locales = new TreeSet<>();
            for (String path : bundled.keySet()) {
                if (path.startsWith("lang/")) {
                    locales.add(path.substring(5, path.length() - 4));
                }
            }
            return locales;
        }
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
