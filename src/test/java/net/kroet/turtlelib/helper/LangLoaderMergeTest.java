package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link LangLoader#mergeSections} directly against plain in-memory
 * configuration sections - no {@code JavaPlugin}/data folder needed, since the
 * merge logic itself only ever touches {@code ConfigurationSection}.
 */
class LangLoaderMergeTest {

    @Test
    void copiesMissingTopLevelKeyFromDefaults() {
        YamlConfiguration target = new YamlConfiguration();
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("prefix", "[Plugin]");

        List<String> added = new ArrayList<>();
        boolean changed = LangLoader.mergeSections(target, defaults, "", added);

        assertTrue(changed);
        assertEquals("[Plugin]", target.getString("prefix"));
        assertEquals(List.of("prefix"), added);
    }

    @Test
    void doesNotOverwriteExistingUserEditedValue() {
        YamlConfiguration target = new YamlConfiguration();
        target.set("prefix", "[Custom]");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("prefix", "[Plugin]");

        List<String> added = new ArrayList<>();
        boolean changed = LangLoader.mergeSections(target, defaults, "", added);

        assertFalse(changed);
        assertEquals("[Custom]", target.getString("prefix"));
        assertTrue(added.isEmpty());
    }

    @Test
    void recursesIntoNestedSectionsAndReportsDottedPaths() {
        YamlConfiguration target = new YamlConfiguration();
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("messages.update-found", "Update found!");
        defaults.set("messages.update-none", "No update.");
        target.set("messages.update-found", "Custom text");

        List<String> added = new ArrayList<>();
        boolean changed = LangLoader.mergeSections(target, defaults, "", added);

        assertTrue(changed);
        assertEquals("Custom text", target.getString("messages.update-found"));
        assertEquals("No update.", target.getString("messages.update-none"));
        assertEquals(List.of("messages.update-none"), added);
    }

    @Test
    void createsMissingSectionEvenIfItEndsUpEmpty() {
        YamlConfiguration target = new YamlConfiguration();
        MemoryConfiguration defaults = new MemoryConfiguration();
        defaults.createSection("commands");

        List<String> added = new ArrayList<>();
        boolean changed = LangLoader.mergeSections(target, defaults, "", added);

        assertTrue(changed);
        assertTrue(target.isConfigurationSection("commands"));
    }

    @Test
    void plainValueWhereTheDefaultsHaveASectionIsKept() {
        YamlConfiguration target = new YamlConfiguration();
        target.set("command", "My text");
        target.set("ui.prefix", "[Mine]");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("command.reload", "Reloaded");
        defaults.set("ui.prefix.label", "Demo");
        defaults.set("greet", "Hi");

        List<String> added = new ArrayList<>();
        boolean changed = LangLoader.mergeSections(target, defaults, "", added);

        assertTrue(changed);
        assertEquals("My text", target.getString("command"));
        assertEquals("[Mine]", target.getString("ui.prefix"));
        assertEquals(List.of("greet"), added);
        assertEquals(List.of("command", "ui.prefix"), LangLoader.valuesInPlaceOfSections(target, defaults, ""));
    }

    @Test
    void keyWithoutAValueStillGetsTheSection() {
        YamlConfiguration target = new YamlConfiguration();
        target.set("command", null);
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("command.reload", "Reloaded");

        List<String> added = new ArrayList<>();
        LangLoader.mergeSections(target, defaults, "", added);

        assertEquals(List.of("command.reload"), added);
        assertTrue(LangLoader.valuesInPlaceOfSections(new YamlConfiguration(), defaults, "").isEmpty());
    }

    @Test
    void nullDefaultsSectionIsANoOp() {
        YamlConfiguration target = new YamlConfiguration();
        target.set("prefix", "[Custom]");

        boolean changed = LangLoader.mergeSections(target, null, "", new ArrayList<>());

        assertFalse(changed);
        assertEquals("[Custom]", target.getString("prefix"));
    }
}
