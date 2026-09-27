package net.kroet.turtlelib.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * General MiniMessage-based localization helper shared across plugins.
 */
public class MessageService {

    /**
     * Lang key {@link #load} reads the message prefix from, in every plugin's lang
     * file.
     */
    public static final String PREFIX_KEY = "ui.prefix";
    /**
     * Placeholder for the parsed {@link #PREFIX_KEY} value, resolved in every
     * message.
     */
    public static final String PREFIX_PLACEHOLDER = "prefix";
    /** Placeholder for the plain prefix label, resolved in every message. */
    public static final String PREFIX_LABEL_PLACEHOLDER = "prefix_label";

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final char LEGACY_CHAR = (char) 0xA7; // the section sign
    private static final String LEGACY_COLORS = "0123456789abcdef";
    private static final String[] COLOR_TAGS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow",
            "white"};
    private static final String LEGACY_FORMATS = "klmnor";
    private static final String[] FORMAT_TAGS = {"obfuscated", "bold", "strikethrough", "underlined", "italic",
            "reset"};

    private final JavaPlugin plugin;
    // Replaced as a whole by useMessages, so a reader on another thread sees the
    // old or the new texts, never a half-filled map.
    private volatile Map<String, String> messages = Map.of();
    private final String defaultPrefixRaw;
    private final String defaultPrefixLabel;

    private volatile String prefixRaw;
    private volatile String prefixLabel;
    private volatile String currentLocale = LangLoader.DEFAULT_LOCALE;
    private Map<String, String> legacyKeyMigrations = Map.of();
    private String legacyDefaultsRoot;
    private boolean bundledLocalesEnsured;
    private List<String> lastAddedKeys = List.of();

    public MessageService(JavaPlugin plugin, String defaultPrefixRaw, String defaultPrefixLabel) {
        this.plugin = plugin;
        this.defaultPrefixRaw = defaultPrefixRaw != null && !defaultPrefixRaw.isBlank()
                ? defaultPrefixRaw
                : "<gray>[<prefix_label>]</gray>";
        this.defaultPrefixLabel = defaultPrefixLabel != null && !defaultPrefixLabel.isBlank()
                ? defaultPrefixLabel
                : plugin.getName();
        this.prefixRaw = this.defaultPrefixRaw;
        this.prefixLabel = this.defaultPrefixLabel;
    }

    public static List<String> getBundledLocales(JavaPlugin plugin) {
        return LangLoader.getBundledLocales(plugin);
    }

    /**
     * Registers key renames (by key name, see {@link LangLoader#syncLocale}) for
     * {@link #load} and {@link #syncLocaleFile}; call it before the first load. A
     * rejected rename list throws {@link IllegalArgumentException} right here.
     */
    public void setLegacyKeyMigrations(Map<String, String> legacyKeyMigrations) {
        LangLoader.checkRenames(legacyKeyMigrations);
        this.legacyKeyMigrations = legacyKeyMigrations == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(legacyKeyMigrations));
    }

    /**
     * Jar folder with the old version's lang files as {@code lang/<locale>.yml}; an
     * old key left next to its renamed key is removed while it has the old text.
     */
    public void setLegacyDefaultsRoot(String jarFolder) {
        this.legacyDefaultsRoot = jarFolder == null || jarFolder.endsWith("/") ? jarFolder : jarFolder + "/";
    }

    public void load(String locale) {
        String normalized = LangLoader.resolveLocale(plugin, locale);
        currentLocale = normalized;
        // The other bundled locales only need to exist once per plugin run.
        if (!bundledLocalesEnsured) {
            LangLoader.ensureBundledLocales(plugin);
            bundledLocalesEnsured = true;
        }
        List<String> added = new ArrayList<>();
        var cfg = LangLoader.loadActiveLocale(plugin, normalized, legacyKeyMigrations, legacyDefaultsRoot, added);
        lastAddedKeys = List.copyOf(added);
        useMessages(cfg);
    }

    // Replaces the cached messages and prefix with those of cfg; package-private
    // so tests can render messages without a plugin.
    void useMessages(ConfigurationSection cfg) {
        Map<String, String> collected = new HashMap<>();
        List<String> legacyKeys = new ArrayList<>();
        collectMessages(cfg, "", collected, legacyKeys);
        this.prefixRaw = collected.getOrDefault(PREFIX_KEY, defaultPrefixRaw);
        this.messages = Collections.unmodifiableMap(collected);
        if (!legacyKeys.isEmpty() && plugin != null) {
            plugin.getLogger().warning("Language " + currentLocale + ": " + legacyKeys.size()
                    + " text(s) use legacy section-sign color codes, which MiniMessage doesn't support; they are"
                    + " shown converted. Replace them with MiniMessage tags like <red>: "
                    + String.join(", ", legacyKeys));
        }
    }

    private static void collectMessages(ConfigurationSection section, String pathPrefix,
            Map<String, String> messages, List<String> legacyKeys) {
        for (String key : section.getKeys(false)) {
            String fullKey = pathPrefix.isBlank() ? key : pathPrefix + "." + key;
            if (section.isConfigurationSection(key)) {
                collectMessages(section.getConfigurationSection(key), fullKey, messages, legacyKeys);
                continue;
            }
            // A plain value kept where the defaults have a section: the texts inside
            // that section still come from the defaults.
            ConfigurationSection defaults = section.getDefaultSection();
            if (defaults != null && defaults.isConfigurationSection(key)) {
                collectMessages(defaults.getConfigurationSection(key), fullKey, messages, legacyKeys);
            }
            String value = section.getString(key, fullKey);
            String converted = legacyToMiniMessage(value);
            if (!converted.equals(value)) {
                legacyKeys.add(fullKey);
            }
            messages.put(fullKey, converted);
        }
    }

    /**
     * Turns legacy {@code §} color and format codes into MiniMessage tags, which
     * MiniMessage would otherwise reject with an exception; a color resets the
     * formats before it, as in legacy text. Other text stays as it is.
     */
    static String legacyToMiniMessage(String text) {
        if (text == null || text.indexOf(LEGACY_CHAR) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != LEGACY_CHAR || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char code = Character.toLowerCase(text.charAt(i + 1));
            String hex = legacyHex(text, i);
            int colorIndex = LEGACY_COLORS.indexOf(code);
            int formatIndex = LEGACY_FORMATS.indexOf(code);
            if (hex != null) {
                out.append("<reset><#").append(hex).append('>');
                i += 13;
            } else if (colorIndex >= 0) {
                out.append("<reset><").append(COLOR_TAGS[colorIndex]).append('>');
                i++;
            } else if (formatIndex >= 0) {
                out.append('<').append(FORMAT_TAGS[formatIndex]).append('>');
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // Reads the six digits of a §x§R§R§G§G§B§B hex color starting at index start.
    private static String legacyHex(String text, int start) {
        if (start + 13 >= text.length() || Character.toLowerCase(text.charAt(start + 1)) != 'x') {
            return null;
        }
        StringBuilder hex = new StringBuilder(6);
        for (int i = start + 2; i < start + 14; i += 2) {
            char digit = Character.toLowerCase(text.charAt(i + 1));
            if (text.charAt(i) != LEGACY_CHAR || Character.digit(digit, 16) < 0) {
                return null;
            }
            hex.append(digit);
        }
        return hex.toString();
    }

    /**
     * Keys the last {@link #load} added to the active lang file from the bundled
     * defaults, so they can be reported without syncing the file again.
     */
    public List<String> getLastAddedKeys() {
        return lastAddedKeys;
    }

    public List<String> syncLocaleFile(String locale) {
        return LangLoader.syncLocale(plugin, locale, legacyKeyMigrations, legacyDefaultsRoot);
    }

    public String getLanguage() {
        return currentLocale;
    }

    public void setPrefixLabel(String label) {
        if (label == null || label.isBlank()) {
            this.prefixLabel = this.defaultPrefixLabel;
        } else {
            this.prefixLabel = label;
        }
    }

    private TagResolver prefixResolvers(TagResolver... extra) {
        TagResolver.Builder builder = TagResolver.builder();
        builder.resolver(Placeholder.parsed(PREFIX_PLACEHOLDER, prefixRaw));
        builder.resolver(Placeholder.unparsed(PREFIX_LABEL_PLACEHOLDER, prefixLabel));
        if (extra != null) {
            for (TagResolver resolver : extra) {
                if (resolver != null) {
                    builder.resolver(resolver);
                }
            }
        }
        return builder.build();
    }

    public Component component(String key) {
        String raw = messages.getOrDefault(key, key);
        return MINI.deserialize(raw, prefixResolvers());
    }

    public Component format(String key, Map<String, String> replacements) {
        String raw = messages.getOrDefault(key, key);
        TagResolver.Builder builder = TagResolver.builder();
        builder.resolver(prefixResolvers());
        if (replacements != null) {
            for (Map.Entry<String, String> e : replacements.entrySet()) {
                builder.resolver(Placeholder.unparsed(e.getKey(), e.getValue()));
            }
        }
        return MINI.deserialize(raw, builder.build());
    }

    public String plain(String key) {
        return PLAIN.serialize(component(key));
    }

    public String plain(String key, Map<String, String> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            return plain(key);
        }
        return PLAIN.serialize(format(key, replacements));
    }

    public void reload(FileConfiguration config) {
        String lang = config.getString("language", currentLocale);
        load(lang);
    }

    /**
     * Resolves {@code key} through this locale and writes it to {@code logger} at
     * {@code level}, so plugins share one localized logging path instead of each
     * hand-rolling its own.
     */
    public void log(Logger logger, Level level, String key) {
        log(logger, level, key, Map.of());
    }

    public void log(Logger logger, Level level, String key, Map<String, String> replacements) {
        logger.log(level, plain(key, replacements));
    }
}
