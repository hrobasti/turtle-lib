package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Renders messages from in-memory lang files; the plugin is only needed for
 * loading files, so these tests pass none.
 */
class MessageServiceTest {

    private static final String DEFAULT_PREFIX = "<gray>[<prefix_label>]</gray>";
    private static final String S = String.valueOf((char) 0xA7); // the section sign

    private static MessageService service(String defaultPrefix, String yaml) throws InvalidConfigurationException {
        MessageService service = new MessageService(null, defaultPrefix, "Demo");
        service.useMessages(yaml(yaml));
        return service;
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void renameListsAreCheckedWhenTheyAreSet() {
        MessageService service = new MessageService(null, DEFAULT_PREFIX, "Demo");

        assertThrows(IllegalArgumentException.class, () -> service.setLegacyKeyMigrations(
                Map.of("a.max-radius", "a.radius", "b.max-radius", "b.range")));
        assertThrows(IllegalArgumentException.class, () -> service.setLegacyKeyMigrations(
                Map.of("a.max-radius", "a.radius", "b.radius", "b.range")));
        assertThrows(IllegalArgumentException.class, () -> service.setLegacyKeyMigrations(
                Map.of("log.old-key", "log.section.new_key")));
        service.setLegacyKeyMigrations(Map.of("command.no-permission", "command.no_permission"));
        service.setLegacyKeyMigrations(null);
    }

    @Test
    void constructorPrefixIsUsedWhenTheLangFileHasNone() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "greet: '<prefix> Hello'");

        assertEquals("[Demo] Hello", service.plain("greet"));
    }

    @Test
    void blankConstructorPrefixFallsBackToTheLabelInBrackets() throws InvalidConfigurationException {
        MessageService service = service(" ", "greet: '<prefix> Hello'");

        assertEquals("[Demo] Hello", service.plain("greet"));
    }

    @Test
    void uiPrefixOfTheLangFileWinsUntilAFileWithoutOneIsLoaded() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX,
                "ui:\n  prefix: '<red>{<prefix_label>}</red>'\ngreet: '<prefix> Hello'");
        assertEquals("{Demo} Hello", service.plain("greet"));

        service.useMessages(yaml("greet: '<prefix> Hello'"));

        assertEquals("[Demo] Hello", service.plain("greet"));
    }

    @Test
    void prefixLabelCanBeChangedAndBlankRestoresTheFallback() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "greet: '<prefix> Hello'");

        service.setPrefixLabel("Custom");
        assertEquals("[Custom] Hello", service.plain("greet"));
        service.setPrefixLabel(" ");
        assertEquals("[Demo] Hello", service.plain("greet"));
        service.setPrefixLabel(null);
        assertEquals("[Demo] Hello", service.plain("greet"));
    }

    @Test
    void unknownKeyRendersTheKeyItself() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "greet: Hello");

        assertEquals("command.missing", service.plain("command.missing"));
        assertEquals("command.missing", service.plain("command.missing", Map.of("player", "Bob")));
    }

    @Test
    void nestedKeysAreAddressedByTheirDottedPath() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "command:\n  reload:\n    done: '<green>Reloaded</green>'");

        assertEquals("Reloaded", service.plain("command.reload.done"));
    }

    @Test
    void textsInsideASectionReplacedByAPlainValueComeFromTheDefaults() throws InvalidConfigurationException {
        YamlConfiguration lang = yaml("command: Mein Text\n");
        lang.setDefaults(yaml("command:\n  reload: Reloaded\n  help: Help\n"));
        lang.options().copyDefaults(true);
        MessageService service = new MessageService(null, DEFAULT_PREFIX, "Demo");

        service.useMessages(lang);

        assertEquals("Mein Text", service.plain("command"));
        assertEquals("Reloaded", service.plain("command.reload"));
        assertEquals("Help", service.plain("command.help"));
    }

    @Test
    void replacementsAreInsertedAsPlainText() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "greet: 'Hi <player>!'");

        var component = service.format("greet", Map.of("player", "<bold>Bob</bold> <prefix>"));

        assertEquals("Hi <bold>Bob</bold> <prefix>!", PlainTextComponentSerializer.plainText().serialize(component));
        assertEquals("Hi Bob!", service.plain("greet", Map.of("player", "Bob")));
    }

    @Test
    void placeholderWithoutAValueStaysLiteral() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "greet: 'Hi <player>!'");

        assertEquals("Hi <player>!", service.plain("greet"));
        assertEquals("Hi <player>!", service.plain("greet", Map.of()));
        assertEquals("Hi <player>!", PlainTextComponentSerializer.plainText().serialize(service.format("greet", null)));
    }

    @Test
    void logWritesThePlainTextAtTheGivenLevel() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "log:\n  found: '<yellow>Update <version> found</yellow>'");
        List<LogRecord> records = new ArrayList<>();

        service.log(capturingLogger(records), Level.WARNING, "log.found", Map.of("version", "2.0.0"));

        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals("Update 2.0.0 found", records.get(0).getMessage());
    }

    @Test
    void legacySectionSignCodesAreConvertedInsteadOfBreakingTheMessage() throws InvalidConfigurationException {
        MessageService service = service(DEFAULT_PREFIX, "ui:\n  prefix: '" + S + "6[<prefix_label>]'\n"
                + "error: '<prefix> " + S + "cNo " + S + "lpermission, <player>'");

        assertEquals("[Demo] No permission, Bob", service.plain("error", Map.of("player", "Bob")));
        assertEquals("[Demo] No permission, <player>", service.plain("error"));
    }

    @Test
    void legacyCodesBecomeTheMatchingMiniMessageTags() {
        assertEquals("<reset><red>No <bold>way<reset>!", MessageService.legacyToMiniMessage(S + "cNo " + S + "lway"
                + S + "r!"));
        assertEquals("<reset><dark_green>A<reset><gold>B", MessageService.legacyToMiniMessage(S + "2A" + S + "6B"));
        assertEquals("<reset><red>upper", MessageService.legacyToMiniMessage(S + "Cupper"));
        assertEquals("<reset><#a1b2c3>hex", MessageService.legacyToMiniMessage(S + "x" + S + "a" + S + "1" + S + "b"
                + S + "2" + S + "c" + S + "3hex"));
    }

    @Test
    void textWithoutLegacyCodesStaysUnchanged() {
        String plain = "<red>Hi</red> &cfriend";

        assertSame(plain, MessageService.legacyToMiniMessage(plain));
        assertEquals("5" + S + " and " + S + "z", MessageService.legacyToMiniMessage("5" + S + " and " + S + "z"));
    }

    @Test
    void incompleteHexCodeKeepsItsSectionSignAndStillRenders() throws InvalidConfigurationException {
        assertEquals(S + "x<reset><green>1", MessageService.legacyToMiniMessage(S + "x" + S + "a1"));
        MessageService service = service(DEFAULT_PREFIX, "bad: '" + S + "x" + S + "a1'");

        assertEquals(S + "x1", service.plain("bad"));
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
