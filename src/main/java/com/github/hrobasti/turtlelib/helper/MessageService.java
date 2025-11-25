package com.github.hrobasti.turtlelib.helper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * General MiniMessage-based localization helper shared across plugins.
 */
public class MessageService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final JavaPlugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private final String defaultPrefixRaw;
    private final String defaultPrefixLabel;

    private String prefixRaw;
    private String prefixLabel;
    private String currentLocale = LangLoader.DEFAULT_LOCALE;

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

    public void load(String locale) {
        messages.clear();
        String normalized = normalize(locale);
        currentLocale = normalized;
        var cfg = LangLoader.loadLocale(plugin, normalized);
        collectMessages(cfg, "");
        this.prefixRaw = messages.getOrDefault("ui.prefix", defaultPrefixRaw);
    }

    private String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return LangLoader.DEFAULT_LOCALE;
        }
        String trimmed = locale.trim();
        if (trimmed.endsWith(".yml")) {
            return trimmed.substring(0, trimmed.length() - 4);
        }
        return trimmed;
    }

    private void collectMessages(ConfigurationSection section, String pathPrefix) {
        for (String key : section.getKeys(false)) {
            String fullKey = pathPrefix.isBlank() ? key : pathPrefix + "." + key;
            if (section.isConfigurationSection(key)) {
                collectMessages(section.getConfigurationSection(key), fullKey);
                continue;
            }
            messages.put(fullKey, section.getString(key, fullKey));
        }
    }

    public List<String> syncLocaleFile(String locale) {
        return LangLoader.syncLocale(plugin, locale);
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
        builder.resolver(Placeholder.parsed("prefix", prefixRaw));
        builder.resolver(Placeholder.unparsed("prefix_label", prefixLabel));
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
}
