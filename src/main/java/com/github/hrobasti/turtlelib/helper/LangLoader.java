package com.github.hrobasti.turtlelib.helper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Shared utility for copying and synchronizing bundled locale files across plugins.
 */
public final class LangLoader {

    public static final String DEFAULT_LOCALE = "en_US";
    private static final String LANG_RESOURCE_PREFIX = "lang/";
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}_[A-Z]{2}$");

    private LangLoader() {
    }

    public static List<String> getBundledLocales(JavaPlugin plugin) {
        Set<String> locales = new TreeSet<>();
        locales.addAll(scanBundledLocales(plugin));
        locales.addAll(scanDataLocales(plugin));
        if (locales.isEmpty()) {
            locales.add(DEFAULT_LOCALE);
        }
        return List.copyOf(locales);
    }

    public static void ensureBundledLocales(JavaPlugin plugin) {
        for (String locale : getBundledLocales(plugin)) {
            ensureLocaleFile(plugin, locale);
        }
    }

    public static List<String> syncLocale(JavaPlugin plugin, String locale) {
        String normalized = normalize(locale);
        if (normalized == null) {
            return Collections.emptyList();
        }
        File langFile = ensureLocaleFile(plugin, normalized);
        YamlConfiguration defaults = loadDefaults(plugin, normalized);
        if (defaults == null) {
            return Collections.emptyList();
        }
        YamlConfiguration target = YamlConfiguration.loadConfiguration(langFile);
        List<String> added = new ArrayList<>();
        if (mergeSections(target, defaults, "", added)) {
            try {
                target.save(langFile);
            } catch (IOException e) {
                plugin.getLogger().fine("Failed to sync lang file " + langFile.getName() + ": " + e.getMessage());
            }
        }
        return added;
    }

    public static YamlConfiguration loadLocale(JavaPlugin plugin, String locale) {
        ensureBundledLocales(plugin);
        String normalized = normalize(locale);
        if (normalized == null) {
            normalized = DEFAULT_LOCALE;
        }
        syncLocale(plugin, normalized);
        File langFile = ensureLocaleFile(plugin, normalized);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(langFile);
        YamlConfiguration defaults = loadDefaults(plugin, DEFAULT_LOCALE);
        if (defaults != null) {
            configuration.setDefaults(defaults);
            configuration.options().copyDefaults(true);
        }
        return configuration;
    }

    public static YamlConfiguration loadLocaleResource(JavaPlugin plugin, String locale) {
        String normalized = normalize(locale);
        if (normalized == null) {
            normalized = DEFAULT_LOCALE;
        }
        try (InputStream in = plugin.getResource("lang/" + normalized + ".yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().fine("Failed to read lang resource " + normalized + ": " + e.getMessage());
            return null;
        }
    }

    private static File ensureLocaleFile(JavaPlugin plugin, String locale) {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            plugin.getLogger().fine("Could not create lang directory " + langDir.getAbsolutePath());
        }
        File out = new File(langDir, locale + ".yml");
        if (out.exists()) {
            return out;
        }
        try {
            plugin.saveResource("lang/" + locale + ".yml", false);
        } catch (IllegalArgumentException ignored) {
            try {
                if (!out.createNewFile()) {
                    plugin.getLogger().fine("Could not create lang file " + out.getName());
                }
            } catch (IOException e) {
                plugin.getLogger().fine("Failed to create lang file " + out.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin, String locale) {
        YamlConfiguration defaults = loadLocaleResource(plugin, locale);
        if (defaults == null && !DEFAULT_LOCALE.equals(locale)) {
            defaults = loadLocaleResource(plugin, DEFAULT_LOCALE);
        }
        return defaults;
    }

    private static Set<String> scanBundledLocales(JavaPlugin plugin) {
        File jar = resolvePluginJar(plugin);
        if (jar == null || !jar.isFile()) {
            return Collections.emptySet();
        }
        Set<String> locales = new TreeSet<>();
        try (JarFile jarFile = new JarFile(jar)) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith(LANG_RESOURCE_PREFIX) || !name.endsWith(".yml")) {
                    continue;
                }
                String locale = name.substring(LANG_RESOURCE_PREFIX.length(), name.length() - 4);
                if (isLocaleIdentifier(locale)) {
                    locales.add(locale);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().fine("Could not inspect bundled locales: " + e.getMessage());
        }
        if (!locales.contains(DEFAULT_LOCALE)) {
            locales.add(DEFAULT_LOCALE);
        }
        return locales;
    }

    private static Set<String> scanDataLocales(JavaPlugin plugin) {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            return Collections.emptySet();
        }
        File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return Collections.emptySet();
        }
        Set<String> locales = new TreeSet<>();
        for (File file : files) {
            String name = file.getName();
            String locale = name.substring(0, name.length() - 4);
            if (isLocaleIdentifier(locale)) {
                locales.add(locale);
            }
        }
        return locales;
    }

    private static boolean isLocaleIdentifier(String candidate) {
        return candidate != null && LOCALE_PATTERN.matcher(candidate).matches();
    }

    private static File resolvePluginJar(JavaPlugin plugin) {
        try {
            URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            File file = new File(location.toURI());
            return file.exists() ? file : null;
        } catch (URISyntaxException ex) {
            plugin.getLogger().fine("Could not resolve plugin jar location: " + ex.getMessage());
            return null;
        }
    }

    private static boolean mergeSections(ConfigurationSection target,
                                         ConfigurationSection defaults,
                                         String pathPrefix,
                                         List<String> addedKeys) {
        boolean changed = false;
        if (defaults == null) {
            return false;
        }
        for (String key : defaults.getKeys(false)) {
            String fullKey = pathPrefix == null || pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defChild = defaults.getConfigurationSection(key);
                ConfigurationSection tgtChild = target.getConfigurationSection(key);
                if (tgtChild == null) {
                    tgtChild = target.createSection(key);
                    changed = true;
                }
                if (defChild != null) {
                    changed |= mergeSections(tgtChild, defChild, fullKey, addedKeys);
                }
                continue;
            }
            if (!target.contains(key)) {
                target.set(key, defaults.get(key));
                if (addedKeys != null) {
                    addedKeys.add(fullKey);
                }
                changed = true;
            }
        }
        return changed;
    }

    private static String normalize(String locale) {
        if (locale == null) {
            return null;
        }
        String trimmed = locale.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.endsWith(".yml")) {
            return trimmed.substring(0, trimmed.length() - 4);
        }
        return trimmed;
    }
}
