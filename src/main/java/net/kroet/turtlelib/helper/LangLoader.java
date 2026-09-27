package net.kroet.turtlelib.helper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Shared utility for copying and synchronizing bundled locale files across
 * plugins.
 */
public final class LangLoader {

    /**
     * The preferred base locale: missing texts come from the bundled file of this
     * locale, or of the plugin's alphabetically first bundled locale if it ships no
     * {@code en_US}.
     */
    public static final String DEFAULT_LOCALE = "en_US";
    private static final String LANG_RESOURCE_PREFIX = "lang/";
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}_[A-Z]{2}$");

    private LangLoader() {
    }

    /**
     * What LangLoader needs from a plugin; tests pass a temp data folder and
     * in-memory resources instead.
     */
    interface Source {
        File dataFolder();

        InputStream resource(String path);

        /** Copies a bundled resource; IllegalArgumentException if it isn't bundled. */
        void saveResource(String path);

        Logger logger();

        /** Locale codes bundled with the plugin, e.g. for a hint in a warning. */
        Set<String> bundledLocales();

        static Source of(JavaPlugin plugin) {
            return new Source() {
                @Override
                public File dataFolder() {
                    return plugin.getDataFolder();
                }

                @Override
                public InputStream resource(String path) {
                    return plugin.getResource(path);
                }

                @Override
                public void saveResource(String path) {
                    plugin.saveResource(path, false);
                }

                @Override
                public Logger logger() {
                    return plugin.getLogger();
                }

                @Override
                public Set<String> bundledLocales() {
                    return scanBundledLocales(plugin);
                }
            };
        }
    }

    /** Locales bundled in the jar or present in the data folder's lang/, sorted. */
    public static List<String> getBundledLocales(JavaPlugin plugin) {
        return knownLocales(Source.of(plugin));
    }

    static List<String> knownLocales(Source source) {
        Set<String> locales = new TreeSet<>(source.bundledLocales());
        locales.addAll(scanDataLocales(source.dataFolder()));
        return List.copyOf(locales);
    }

    public static void ensureBundledLocales(JavaPlugin plugin) {
        ensureBundledLocales(Source.of(plugin));
    }

    static void ensureBundledLocales(Source source) {
        for (String locale : knownLocales(source)) {
            ensureLocaleFile(source, locale);
        }
    }

    // en_US if the plugin ships it, else its alphabetically first bundled locale;
    // null if it ships none.
    static String baseLocale(Source source) {
        if (hasResource(source, "lang/" + DEFAULT_LOCALE + ".yml")) {
            return DEFAULT_LOCALE;
        }
        for (String locale : new TreeSet<>(source.bundledLocales())) {
            if (isLocaleIdentifier(locale) && hasResource(source, "lang/" + locale + ".yml")) {
                return locale;
            }
        }
        return null;
    }

    private static boolean hasResource(Source source, String path) {
        try (InputStream in = source.resource(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static List<String> syncLocale(JavaPlugin plugin, String locale) {
        return syncLocale(plugin, locale, Collections.emptyMap());
    }

    /**
     * Same as {@link #syncLocale(JavaPlugin, String)}, but first renames keys by
     * name like {@link ConfigKeyMigrator#rewriteLegacyKeysInFile}; a rejected
     * rename list throws {@link IllegalArgumentException} before any file access.
     */
    public static List<String> syncLocale(JavaPlugin plugin, String locale, Map<String, String> legacyKeyMigrations) {
        return syncLocale(plugin, locale, legacyKeyMigrations, null);
    }

    static List<String> syncLocale(JavaPlugin plugin, String locale, Map<String, String> legacyKeyMigrations,
            String legacyDefaultsRoot) {
        checkRenames(legacyKeyMigrations);
        Source source = Source.of(plugin);
        String base = baseLocale(source);
        return sync(source, resolveLocale(source.logger(), locale, base), base, legacyKeyMigrations,
                legacyDefaultsRoot).added();
    }

    // Rejects a rename list with conflicting, chained or depth-changing entries,
    // whether or not the lang file can be read.
    static void checkRenames(Map<String, String> legacyKeyMigrations) {
        if (legacyKeyMigrations != null && !legacyKeyMigrations.isEmpty()) {
            ConfigKeyMigrator.deriveLeafRenames(legacyKeyMigrations);
        }
    }

    // target and defaults are null when neither the locale nor the base is bundled;
    // target is also null when the file is unreadable and was left untouched.
    record SyncResult(List<String> added, YamlConfiguration target, YamlConfiguration defaults, boolean unreadable) {
    }

    private static SyncResult sync(Source source, String normalized, String base,
            Map<String, String> legacyKeyMigrations, String legacyDefaultsRoot) {
        File langFile = ensureLocaleFile(source, normalized);
        String defaultsText = bundledText(source, normalized);
        if (defaultsText == null && base != null && !base.equals(normalized)) {
            defaultsText = bundledText(source, base);
        }
        if (defaultsText == null) {
            return new SyncResult(Collections.emptyList(), null, null, false);
        }
        String legacyText = legacyDefaultsRoot == null
                ? null
                : resourceText(source, legacyDefaultsRoot + "lang/" + normalized + ".yml");
        return syncFile(langFile, "lang/" + normalized + ".yml", defaultsText, legacyKeyMigrations, legacyText,
                source.logger());
    }

    /**
     * Renames legacy keys, repairs color tags and merges missing keys into
     * {@code langFile}; a file that isn't valid YAML stays byte-identical.
     */
    static SyncResult syncFile(File langFile, String fileLabel, String defaultsText,
            Map<String, String> legacyKeyMigrations, String legacyDefaultsText, Logger logger) {
        checkRenames(legacyKeyMigrations);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new StringReader(defaultsText));
        LangFile target = readLangFile(langFile, fileLabel, logger);
        if (target == null) {
            return new SyncResult(Collections.emptyList(), null, defaults, true);
        }
        boolean rewritten = false;
        if (legacyKeyMigrations != null && !legacyKeyMigrations.isEmpty()) {
            rewritten = ConfigKeyMigrator.rewriteLegacyKeysInFile(langFile, logger, fileLabel, legacyKeyMigrations,
                    legacyDefaultsText == null
                            ? null
                            : new ByteArrayInputStream(legacyDefaultsText.getBytes(StandardCharsets.UTF_8)));
        }
        rewritten |= repairInvalidColorTags(langFile, logger, fileLabel);
        if (rewritten) {
            target = readLangFile(langFile, fileLabel, logger);
            if (target == null) {
                return new SyncResult(Collections.emptyList(), null, defaults, true);
            }
        }
        List<String> kept = valuesInPlaceOfSections(target.yaml(), defaults, "");
        if (!kept.isEmpty()) {
            logger.warning(fileLabel + ": kept your text at " + String.join(", ", kept) + ", where the bundled file"
                    + " has a section; the texts in that section use the defaults until you rename or remove it");
        }
        List<String> added = new ArrayList<>();
        if (!target.utf8()) {
            // A save would re-encode the admin's text; add the keys as text instead.
            List<String> inserted = ConfigDefaultsInserter.insertMissingKeys(langFile,
                    new ByteArrayInputStream(defaultsText.getBytes(StandardCharsets.UTF_8)), logger, fileLabel,
                    List.of(), true);
            if (!inserted.isEmpty()) {
                inserted.forEach(path -> addLeafPaths(defaults, path, added));
                target = readLangFile(langFile, fileLabel, logger);
                if (target == null) {
                    return new SyncResult(Collections.emptyList(), null, defaults, true);
                }
            }
        } else if (mergeSections(target.yaml(), defaults, "", added)) {
            try {
                AtomicFiles.write(langFile.toPath(), target.yaml().saveToString().getBytes(StandardCharsets.UTF_8),
                        logger);
            } catch (IOException e) {
                logger.warning("Failed to sync lang file " + langFile.getName() + ": " + e.getMessage());
            }
        }
        return new SyncResult(added, target.yaml(), defaults, false);
    }

    // Reports a whole inserted section by its keys, like mergeSections does.
    private static void addLeafPaths(YamlConfiguration defaults, String path, List<String> added) {
        ConfigurationSection section = defaults.getConfigurationSection(path);
        if (section == null) {
            added.add(path);
            return;
        }
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                added.add(path + "." + key);
            }
        }
    }

    private record LangFile(YamlConfiguration yaml, boolean utf8) {
    }

    // Reads UTF-8, or Windows-1252 if the bytes aren't valid UTF-8, and warns once
    // instead of logging a SEVERE stack trace. Returns null for a broken file and
    // an empty config for a missing one.
    private static LangFile readLangFile(File file, String fileLabel, Logger logger) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.exists()) {
            return new LangFile(yaml, true);
        }
        String problem;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            boolean utf8 = YamlLines.isValidUtf8(bytes);
            yaml.loadFromString(new String(bytes, utf8 ? StandardCharsets.UTF_8 : ConfigDefaultsInserter.ANSI));
            return new LangFile(yaml, utf8);
        } catch (InvalidConfigurationException e) {
            problem = " isn't valid YAML (" + YamlLines.describeYamlError(e) + ")";
        } catch (IOException e) {
            problem = " couldn't be read (" + e.getMessage() + ")";
        }
        logger.warning(fileLabel + problem + "; using the bundled texts and leaving the file unchanged until it is fixed");
        return null;
    }

    // <light_red> is no MiniMessage color and renders as literal text, so it
    // becomes <red> (#FF5555, §c). Works on the raw bytes, as the tags are ASCII
    // and the file may be UTF-8 or Windows-1252.
    static boolean repairInvalidColorTags(File file, Logger logger, String fileLabel) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
            if (!original.contains("light_red>")) {
                return false;
            }
            String repaired = original.replace("<light_red>", "<red>").replace("</light_red>", "</red>");
            if (repaired.equals(original)) {
                return false;
            }
            AtomicFiles.write(file.toPath(), repaired.getBytes(StandardCharsets.ISO_8859_1), logger);
            if (logger != null) {
                logger.info("Replaced invalid <light_red> color tags with <red> in " + fileLabel);
            }
            return true;
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("Could not repair color tags in " + fileLabel + ": " + e.getMessage());
            }
            return false;
        }
    }

    public static YamlConfiguration loadLocale(JavaPlugin plugin, String locale) {
        return loadLocale(plugin, locale, Collections.emptyMap());
    }

    /**
     * Same as {@link #loadLocale(JavaPlugin, String)}, but also migrates renamed
     * message keys - see {@link #syncLocale(JavaPlugin, String, Map)}.
     */
    public static YamlConfiguration loadLocale(JavaPlugin plugin, String locale, Map<String, String> legacyKeyMigrations) {
        checkRenames(legacyKeyMigrations);
        ensureBundledLocales(plugin);
        return loadActiveLocale(plugin, locale, legacyKeyMigrations, null, null);
    }

    /**
     * Syncs and loads only {@code locale}, parsing its file once; keys merged into
     * it are added to {@code addedKeys} if that isn't {@code null}.
     */
    static YamlConfiguration loadActiveLocale(JavaPlugin plugin, String locale,
            Map<String, String> legacyKeyMigrations, String legacyDefaultsRoot, List<String> addedKeys) {
        return loadActiveLocale(Source.of(plugin), locale, legacyKeyMigrations, legacyDefaultsRoot, addedKeys);
    }

    static YamlConfiguration loadActiveLocale(Source source, String locale,
            Map<String, String> legacyKeyMigrations, String legacyDefaultsRoot, List<String> addedKeys) {
        checkRenames(legacyKeyMigrations);
        String base = baseLocale(source);
        String normalized = resolveLocale(source.logger(), locale, base);
        SyncResult sync = sync(source, normalized, base, legacyKeyMigrations, legacyDefaultsRoot);
        if (addedKeys != null) {
            addedKeys.addAll(sync.added());
        }
        YamlConfiguration configuration;
        YamlConfiguration defaults;
        if (sync.unreadable()) {
            // The bundled texts stand in for the broken file; the base locale gets no
            // defaults, as its bundled copy would be its own defaults.
            configuration = sync.defaults();
            defaults = normalized.equals(base) ? null : loadDefaults(source, base);
        } else {
            configuration = sync.target() != null
                    ? sync.target()
                    : YamlConfiguration.loadConfiguration(ensureLocaleFile(source, normalized));
            defaults = normalized.equals(base) && sync.defaults() != null
                    ? sync.defaults()
                    : loadDefaults(source, base);
        }
        if (defaults != null) {
            configuration.setDefaults(defaults);
            configuration.options().copyDefaults(true);
        }
        return configuration;
    }

    public static YamlConfiguration loadLocaleResource(JavaPlugin plugin, String locale) {
        return loadLocaleResource(Source.of(plugin), locale);
    }

    private static YamlConfiguration loadLocaleResource(Source source, String locale) {
        String text = bundledText(source, locale);
        return text == null ? null : YamlConfiguration.loadConfiguration(new StringReader(text));
    }

    // The bundled lang/<locale>.yml as UTF-8 text, or null if it isn't bundled.
    private static String bundledText(Source source, String locale) {
        String normalized = normalize(locale);
        if (normalized == null) {
            normalized = DEFAULT_LOCALE;
        }
        return resourceText(source, "lang/" + normalized + ".yml");
    }

    private static String resourceText(Source source, String path) {
        try (InputStream in = source.resource(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            source.logger().warning("Failed to read lang resource " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static File ensureLocaleFile(Source source, String locale) {
        if (!isLocaleIdentifier(locale)) {
            throw new IllegalArgumentException("Invalid locale identifier: " + locale);
        }
        File langDir = new File(source.dataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            source.logger().warning("Could not create lang directory " + langDir.getAbsolutePath());
        }
        File out = new File(langDir, locale + ".yml");
        if (out.exists()) {
            return out;
        }
        try {
            source.saveResource("lang/" + locale + ".yml");
        } catch (IllegalArgumentException ignored) {
            try {
                if (!out.createNewFile()) {
                    source.logger().warning("Could not create lang file " + out.getName());
                } else {
                    // Once, when the file is created; afterwards it is the admin's to translate.
                    source.logger().warning(missingTranslationWarning(locale, baseLocale(source),
                            source.bundledLocales()));
                }
            } catch (IOException e) {
                source.logger().warning("Failed to create lang file " + out.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static String missingTranslationWarning(String locale, String base, Set<String> bundled) {
        if (base == null) {
            return "No bundled translation for " + locale + " and no bundled lang files; lang/" + locale
                    + ".yml starts empty, and texts it lacks show as their keys";
        }
        return "No bundled translation for " + locale + "; using the " + base + " texts in lang/" + locale
                + ".yml. Available: " + String.join(", ", new TreeSet<>(bundled));
    }

    private static YamlConfiguration loadDefaults(Source source, String base) {
        return base == null ? null : loadLocaleResource(source, base);
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
            plugin.getLogger().warning("Could not inspect bundled locales: " + e.getMessage());
        }
        return locales;
    }

    private static Set<String> scanDataLocales(File dataFolder) {
        File langDir = new File(dataFolder, "lang");
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
            plugin.getLogger().warning("Could not resolve plugin jar location: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Package-private (not {@code private}) so {@code LangLoaderMergeTest} can
     * exercise it directly against plain in-memory sections, without needing a
     * {@link JavaPlugin} instance.
     */
    static boolean mergeSections(ConfigurationSection target,
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
                if (tgtChild == null && target.contains(key)) {
                    continue; // the admin's plain value stays, see valuesInPlaceOfSections
                }
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

    // Paths where the admin's file has a plain value and the defaults a section;
    // the merge keeps those values.
    static List<String> valuesInPlaceOfSections(ConfigurationSection target, ConfigurationSection defaults,
            String pathPrefix) {
        List<String> paths = new ArrayList<>();
        if (defaults == null) {
            return paths;
        }
        for (String key : defaults.getKeys(false)) {
            if (!defaults.isConfigurationSection(key)) {
                continue;
            }
            String fullKey = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            ConfigurationSection child = target.getConfigurationSection(key);
            if (child != null) {
                paths.addAll(valuesInPlaceOfSections(child, defaults.getConfigurationSection(key), fullKey));
            } else if (target.contains(key)) {
                paths.add(fullKey);
            }
        }
        return paths;
    }

    /**
     * Normalizes a configured locale to the canonical form of its file name, or the
     * base locale (see {@link #DEFAULT_LOCALE}) if it isn't one. Warns about
     * invalid values so the admin can find the typo.
     */
    public static String resolveLocale(JavaPlugin plugin, String locale) {
        Source source = Source.of(plugin);
        return resolveLocale(source.logger(), locale, baseLocale(source));
    }

    private static String resolveLocale(Logger logger, String locale, String base) {
        String normalized = normalize(locale);
        if (normalized != null) {
            return normalized;
        }
        String fallback = base == null ? DEFAULT_LOCALE : base;
        if (locale != null && !locale.isBlank()) {
            logger.warning("Invalid language '" + locale.trim() + "' - expected a code like de_DE, using "
                    + fallback + " instead");
        }
        return fallback;
    }

    // Accepts "de_DE", "de-de" or "de_DE.yml". Anything else is null, so a
    // config value can never point outside the lang folder.
    static String normalize(String locale) {
        if (locale == null) {
            return null;
        }
        String trimmed = locale.trim();
        if (trimmed.endsWith(".yml")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        String[] parts = trimmed.split("[_-]", -1);
        if (parts.length != 2) {
            return null;
        }
        String candidate = parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
        return isLocaleIdentifier(candidate) ? candidate : null;
    }
}
