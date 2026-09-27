package net.kroet.turtlelib.helper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kroet.turtlelib.helper.YamlLines.Kind;
import net.kroet.turtlelib.helper.YamlLines.Line;
import net.kroet.turtlelib.helper.YamlLines.Parsed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * One-time clean upgrade of a plugin data folder from an older major version:
 * moves the old files into a backup folder, writes fresh bundled files and
 * carries over only the values the admin had changed from the old defaults.
 */
public final class LegacyDataUpgrade {
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]*");
    private static final Pattern BACKUP_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-/]*");
    private static final Set<String> YAML_WORDS = Set.of("true", "false", "yes", "no", "on", "off", "null", "y",
            "n", "~");

    /** Why an admin-changed value was not carried over. */
    public enum SkipReason {
        /** The old defaults don't have the key and the new file doesn't either. */
        UNKNOWN_OLD_KEY,
        /**
         * The key existed in the old defaults but has no counterpart in the new file.
         */
        NO_NEW_KEY,
        /**
         * Old and new value have different shapes (list vs. single value vs. section).
         */
        TYPE_MISMATCH,
        /** Values like lists of sections that can't be written back as text. */
        UNSUPPORTED_VALUE,
        /** The value couldn't be written into the new file without breaking it. */
        WRITE_FAILED
    }

    /**
     * An admin-changed value that now sits in the fresh file under {@code newPath}.
     */
    public record CarriedValue(String file, String oldPath, String newPath, String value) {
    }

    /**
     * An admin-changed value that was left out; the old file in the backup still
     * has it.
     */
    public record DroppedValue(String file, String oldPath, String value, SkipReason reason) {
    }

    /**
     * An entry the admin had removed from an open section, removed from the fresh
     * file too.
     */
    public record RemovedEntry(String file, String oldPath, String newPath) {
    }

    /** Entries new in the shipped default list that the carried-over list lacks. */
    public record MissingListEntries(String file, String path, List<String> entries) {
    }

    /**
     * Outcome of {@link Builder#run()}. {@code performed} is {@code false} when no
     * legacy data was found, or when a problem stopped the upgrade before any file
     * was moved ({@code errors} then says why). Paths use {@code /} separators.
     */
    public record Report(boolean performed, Path backupDir, List<CarriedValue> carried, List<DroppedValue> dropped,
            List<RemovedEntry> removed, List<MissingListEntries> missingListEntries, List<String> customizedFiles,
            List<String> filesWithoutBaseline, List<String> errors) {

        /** {@code true} if nothing needed upgrading and nothing went wrong. */
        public boolean notNeeded() {
            return !performed && errors.isEmpty();
        }
    }

    private LegacyDataUpgrade() {
    }

    public static Builder builder(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return new Builder(plugin.getDataFolder(), plugin::getResource, plugin.getLogger());
    }

    /**
     * @param resources
     *            opens a bundled resource by its jar path, {@code null} if missing
     */
    public static Builder builder(File dataFolder, Function<String, InputStream> resources, Logger logger) {
        return new Builder(Objects.requireNonNull(dataFolder, "dataFolder"),
                Objects.requireNonNull(resources, "resources"), logger);
    }

    public static final class Builder {
        private final File dataFolder;
        private final Function<String, InputStream> resources;
        private final Logger logger;
        private String markerFile = "config.yml";
        private final List<String> markerKeys = new ArrayList<>();
        private String backupDirName;
        private String legacyDefaultsRoot;
        private final List<String> olderDefaultsRoots = new ArrayList<>();
        private final Set<String> freshResources = new LinkedHashSet<>();
        private final Set<String> keepFiles = new LinkedHashSet<>();
        private final Map<String, CarryOver> carryOvers = new LinkedHashMap<>();
        private final Map<String, Set<String>> ignoredOldKeys = new LinkedHashMap<>();

        private Builder(File dataFolder, Function<String, InputStream> resources, Logger logger) {
            this.dataFolder = dataFolder;
            this.resources = resources;
            this.logger = logger;
        }

        /** File checked for the marker keys; defaults to {@code config.yml}. */
        public Builder markerFile(String relativePath) {
            this.markerFile = relativePath;
            return this;
        }

        /**
         * Dotted paths that only the old version's file has; any match starts the
         * upgrade.
         */
        public Builder markerKeys(String... paths) {
            markerKeys.addAll(Arrays.asList(paths));
            return this;
        }

        /**
         * Folder inside the data folder that receives the old files, e.g.
         * {@code backup-1.x}.
         */
        public Builder backupDirName(String name) {
            this.backupDirName = name;
            return this;
        }

        /**
         * Jar folder holding the old version's default files under their data-folder
         * paths.
         */
        public Builder legacyDefaultsRoot(String jarFolder) {
            this.legacyDefaultsRoot = jarFolder.endsWith("/") ? jarFolder : jarFolder + "/";
            return this;
        }

        /**
         * Jar folder with an older release's defaults (whole files or just the keys
         * that differed); files matching any known release then aren't reported as
         * customized.
         */
        public Builder olderDefaultsRoot(String jarFolder) {
            olderDefaultsRoots.add(jarFolder.endsWith("/") ? jarFolder : jarFolder + "/");
            return this;
        }

        /** Bundled files written fresh; the data-folder path equals the jar path. */
        public Builder freshResources(String... relativePaths) {
            return freshResources(Arrays.asList(relativePaths));
        }

        public Builder freshResources(Collection<String> relativePaths) {
            freshResources.addAll(relativePaths);
            return this;
        }

        /** Files or folders copied back unchanged from the backup, e.g. player data. */
        public Builder keepFiles(String... relativePaths) {
            keepFiles.addAll(Arrays.asList(relativePaths));
            return this;
        }

        /**
         * Carries admin-changed values into the fresh file, renamed by key name like
         * {@link ConfigKeyMigrator#rewriteLegacyKeysInFile}. In {@code openSections}
         * (new paths) the admin's own keys are added and deleted keys removed.
         */
        public Builder carryOverValues(String relativePath, Map<String, String> keyMigrations,
                String... openSections) {
            Map<String, String> renames = ConfigKeyMigrator.deriveLeafRenames(keyMigrations);
            return carryOver(relativePath, new CarryOver(renames, false, Set.of(openSections)));
        }

        /**
         * Like {@link #carryOverValues}, but renamed by exact path like
         * {@link ConfigKeyMigrator#rewriteKeyPathsInFile}; a renamed section takes
         * everything below it along.
         */
        public Builder carryOverValuesByPath(String relativePath, Map<String, String> pathMigrations,
                String... openSections) {
            Map<String, String> renames = ConfigKeyMigrator.checkPathRenames(pathMigrations);
            return carryOver(relativePath, new CarryOver(renames, true, Set.of(openSections)));
        }

        private Builder carryOver(String relativePath, CarryOver rules) {
            freshResources.add(relativePath);
            carryOvers.put(relativePath, rules);
            return this;
        }

        /**
         * Old keys of {@code relativePath} that are neither carried over nor reported,
         * e.g. leftovers of an even older version. A path matches that one value;
         * {@code section.*} matches everything below the section.
         */
        public Builder ignoreOldKeys(String relativePath, String... oldPaths) {
            ignoredOldKeys.computeIfAbsent(relativePath, path -> new LinkedHashSet<>()).addAll(Arrays.asList(oldPaths));
            return this;
        }

        /**
         * Runs the upgrade if the marker file holds a marker key; call before loading
         * any config.
         */
        public Report run() {
            if (backupDirName == null || !BACKUP_NAME.matcher(backupDirName).matches() || backupDirName.contains("..")) {
                throw new IllegalStateException("backupDirName must be a plain folder name");
            }
            if (markerKeys.isEmpty()) {
                throw new IllegalStateException("markerKeys must not be empty");
            }
            List<String> shipped = shippedMarkerKeys();
            if (!shipped.isEmpty()) {
                throw new IllegalStateException("The bundled " + markerFile + " contains the marker key(s) "
                        + String.join(", ", shipped) + ", so the upgrade would run again on every start; use keys"
                        + " only the old version's file has");
            }
            return new Upgrade(this).run();
        }

        // Marker keys found in the new version's bundled marker file, read the same
        // way as the marker file in the data folder.
        private List<String> shippedMarkerKeys() {
            byte[] bytes;
            try (InputStream in = resources.apply(markerFile)) {
                if (in == null) {
                    return List.of();
                }
                bytes = in.readAllBytes();
            } catch (IOException e) {
                return List.of(); // reported when the upgrade reads it again
            }
            Set<String> paths;
            try {
                paths = keyPaths(bytes);
            } catch (StackOverflowError e) {
                return List.of(); // the data folder's marker file can't be read either
            }
            return markerKeys.stream().filter(paths::contains).toList();
        }
    }

    // renames: old key name to new name, or with byPath old path to new path.
    private record CarryOver(Map<String, String> renames, boolean byPath, Set<String> openSections) {
        CarryOver {
            renames = Collections.unmodifiableMap(new LinkedHashMap<>(renames));
        }

        String newPath(String oldPath) {
            if (byPath) {
                for (Map.Entry<String, String> rename : renames.entrySet()) {
                    String old = rename.getKey();
                    if (oldPath.equals(old) || oldPath.startsWith(old + ".")) {
                        return rename.getValue() + oldPath.substring(old.length());
                    }
                }
                return oldPath;
            }
            String[] parts = oldPath.split("\\.");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = renames.getOrDefault(parts[i], parts[i]);
            }
            return String.join(".", parts);
        }
    }

    private static final class Upgrade {
        private final Builder spec;
        private final Path dataDir;
        private final List<CarriedValue> carried = new ArrayList<>();
        private final List<DroppedValue> dropped = new ArrayList<>();
        private final List<RemovedEntry> removed = new ArrayList<>();
        private final List<MissingListEntries> missing = new ArrayList<>();
        private final List<String> customized = new ArrayList<>();
        private final List<String> withoutBaseline = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        Upgrade(Builder spec) {
            this.spec = spec;
            this.dataDir = spec.dataFolder.toPath();
        }

        Report run() {
            if (!isLegacy()) {
                return report(false, null);
            }

            // Everything that can fail without touching the data folder comes first.
            Map<String, byte[]> fresh = new LinkedHashMap<>();
            Map<String, byte[]> legacyDefaults = new LinkedHashMap<>();
            for (String path : spec.freshResources) {
                byte[] bytes = readResource(path);
                if (bytes == null) {
                    return abort("Bundled resource " + path + " is missing");
                }
                fresh.put(path, bytes);
            }
            for (String path : spec.carryOvers.keySet()) {
                byte[] bytes = spec.legacyDefaultsRoot == null ? null : readResource(spec.legacyDefaultsRoot + path);
                if (bytes == null) {
                    return abort("Old default file for " + path + " is missing from the jar");
                }
                legacyDefaults.put(path, bytes);
            }

            Path backup;
            try {
                backup = moveToBackup();
            } catch (IOException e) {
                return abort("Could not move the old files into a backup folder: " + e);
            }

            for (Map.Entry<String, byte[]> entry : fresh.entrySet()) {
                try {
                    write(dataDir.resolve(entry.getKey()), entry.getValue());
                } catch (IOException e) {
                    error("Could not write fresh " + entry.getKey() + ": " + e);
                }
            }
            for (String path : spec.keepFiles) {
                try {
                    copyBack(backup.resolve(path), dataDir.resolve(path));
                } catch (IOException e) {
                    error("Could not copy " + path + " back from the backup: " + e);
                }
            }
            for (Map.Entry<String, CarryOver> entry : spec.carryOvers.entrySet()) {
                String path = entry.getKey();
                Path old = backup.resolve(path);
                if (Files.isRegularFile(old)) {
                    try {
                        carryOver(path, entry.getValue(), old, fresh.get(path), legacyDefaults.get(path));
                    } catch (RuntimeException | StackOverflowError e) {
                        error("No values carried over into " + path + ": " + e);
                    }
                }
            }
            compareRemainingFiles(backup);
            return report(true, backup);
        }

        private boolean isLegacy() {
            Path marker = dataDir.resolve(spec.markerFile);
            if (!Files.isRegularFile(marker)) {
                return false;
            }
            try {
                Set<String> paths = keyPaths(Files.readAllBytes(marker));
                return spec.markerKeys.stream().anyMatch(paths::contains);
            } catch (IOException | StackOverflowError e) {
                error("Could not read " + spec.markerFile + " to check for old data: " + e);
                return false;
            }
        }

        // Moves every entry except earlier backups; on failure the moved ones go back.
        private Path moveToBackup() throws IOException {
            Pattern earlierBackup = Pattern.compile(Pattern.quote(spec.backupDirName) + "(-\\d+)?");
            Path backup = dataDir.resolve(spec.backupDirName);
            for (int suffix = 2; Files.exists(backup); suffix++) {
                backup = dataDir.resolve(spec.backupDirName + "-" + suffix);
            }
            List<Path> entries;
            try (Stream<Path> list = Files.list(dataDir)) {
                entries = list.filter(p -> !earlierBackup.matcher(p.getFileName().toString()).matches()).toList();
            }
            Files.createDirectory(backup);
            List<Path> moved = new ArrayList<>();
            try {
                for (Path entry : entries) {
                    Files.move(entry, backup.resolve(entry.getFileName()));
                    moved.add(entry);
                }
            } catch (IOException e) {
                for (Path entry : moved.reversed()) {
                    try {
                        Files.move(backup.resolve(entry.getFileName()), entry);
                    } catch (IOException restore) {
                        e.addSuppressed(restore);
                    }
                }
                try {
                    Files.deleteIfExists(backup);
                } catch (IOException ignored) {
                    // not empty because a restore failed; the files stay reachable there
                }
                throw e;
            }
            return backup;
        }

        private void carryOver(String file, CarryOver rules, Path oldFile, byte[] freshBytes, byte[] defaultBytes) {
            YamlConfiguration user;
            try {
                user = YamlLines.parseYaml(decode(Files.readAllBytes(oldFile)));
            } catch (IOException e) {
                error("Could not read " + file + " from the backup: " + e);
                return;
            }
            YamlConfiguration oldDefaults = YamlLines.parseYaml(new String(defaultBytes, StandardCharsets.UTF_8));
            String text = new String(freshBytes, StandardCharsets.UTF_8);
            if (user == null || oldDefaults == null || YamlLines.parseYaml(text) == null) {
                error("No values carried over into " + file + ": the old file or a default file isn't valid YAML");
                return;
            }
            Set<String> ignored = spec.ignoredOldKeys.getOrDefault(file, Set.of());
            String original = text;

            // A value the old file also has under its new name (e.g. after running the
            // old version again) comes from there; the old name is left out.
            Set<String> underNewName = new HashSet<>();
            for (String oldPath : user.getKeys(true)) {
                if (isCarriable(user, oldPath, ignored) && rules.newPath(oldPath).equals(oldPath)) {
                    underNewName.add(oldPath);
                }
            }
            for (String oldPath : user.getKeys(true)) {
                String newPath = rules.newPath(oldPath);
                if (!isCarriable(user, oldPath, ignored) || !newPath.equals(oldPath) && underNewName.contains(newPath)) {
                    continue;
                }
                Object value = user.get(oldPath);
                Object oldDefault = leafOrNull(oldDefaults, oldPath);
                if (oldDefault != null && sameValue(value, oldDefault)) {
                    continue;
                }
                text = carryValue(file, rules, text, oldPath, newPath, value, oldDefault);
            }
            for (String oldPath : oldDefaults.getKeys(true)) {
                String newPath = rules.newPath(oldPath);
                boolean inOpenSection = rules.openSections().contains(YamlLines.parentOf(newPath));
                boolean sectionThere = user.isConfigurationSection(YamlLines.parentOf(oldPath))
                        || user.isConfigurationSection(YamlLines.parentOf(newPath));
                if (inOpenSection && !user.isSet(oldPath) && !user.isSet(newPath) && sectionThere) {
                    text = removeEntry(file, text, oldPath, newPath);
                }
            }

            if (!text.equals(original)) {
                try {
                    write(dataDir.resolve(file), text.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    error("Could not write carried-over values into " + file + ": " + e);
                }
            }
        }

        private static boolean isCarriable(YamlConfiguration user, String oldPath, Set<String> ignored) {
            Object value = user.get(oldPath);
            return value != null && !(value instanceof ConfigurationSection) && !isIgnored(oldPath, ignored);
        }

        private String carryValue(String file, CarryOver rules, String text, String oldPath, String newPath,
                Object value, Object oldDefault) {
            YamlConfiguration current = YamlLines.parseYaml(text);
            String shown = display(value);
            if (!isWritable(value)) {
                dropped.add(new DroppedValue(file, oldPath, shown, SkipReason.UNSUPPORTED_VALUE));
                return text;
            }
            if (!current.isSet(newPath)) {
                String parent = YamlLines.parentOf(newPath);
                if (rules.openSections().contains(parent) && current.isConfigurationSection(parent)) {
                    return apply(file, text, oldPath, newPath, value, insertChild(text, parent, newPath, value));
                }
                SkipReason reason = oldDefault == null ? SkipReason.UNKNOWN_OLD_KEY : SkipReason.NO_NEW_KEY;
                dropped.add(new DroppedValue(file, oldPath, shown, reason));
                return text;
            }
            Object target = current.get(newPath);
            if (target instanceof ConfigurationSection || (target instanceof List) != (value instanceof List)) {
                dropped.add(new DroppedValue(file, oldPath, shown, SkipReason.TYPE_MISMATCH));
                return text;
            }
            if (value instanceof List<?> list) {
                List<String> newEntries = difference((List<?>) target,
                        oldDefault instanceof List<?> oldList ? oldList : List.of());
                List<String> lacking = difference(newEntries, list);
                if (!lacking.isEmpty()) {
                    missing.add(new MissingListEntries(file, newPath, lacking));
                }
            }
            if (sameValue(value, target)) {
                return text;
            }
            String replaced = value instanceof List<?> list
                    ? replaceList(text, newPath, list)
                    : replaceScalar(text, newPath, value);
            return apply(file, text, oldPath, newPath, value, replaced);
        }

        // Keeps an edit only if the result is valid YAML holding the admin's value.
        private String apply(String file, String before, String oldPath, String newPath, Object value,
                String after) {
            YamlConfiguration check = after == null ? null : YamlLines.parseYaml(after);
            if (check == null || !check.isSet(newPath) || !sameValue(value, check.get(newPath))) {
                dropped.add(new DroppedValue(file, oldPath, display(value), SkipReason.WRITE_FAILED));
                return before;
            }
            carried.add(new CarriedValue(file, oldPath, newPath, display(value)));
            return after;
        }

        private String removeEntry(String file, String text, String oldPath, String newPath) {
            Parsed parsed = YamlLines.parse(text);
            int index = YamlLines.indexOfPath(parsed.lines(), newPath);
            if (index < 0 && YamlLines.parseYaml(text).isSet(newPath)) {
                // An entry of a section the fresh file writes inline.
                String blockStyle = blockForm(text, YamlLines.parentOf(newPath));
                if (blockStyle == null) {
                    return text;
                }
                text = blockStyle;
                parsed = YamlLines.parse(text);
                index = YamlLines.indexOfPath(parsed.lines(), newPath);
            }
            if (index < 0) {
                return text;
            }
            List<Line> lines = parsed.lines();
            String after = text.substring(0, lines.get(index).start())
                    + text.substring(lines.get(YamlLines.blockEnd(lines, index)).end());
            YamlConfiguration check = YamlLines.parseYaml(after);
            if (check == null || check.isSet(newPath)) {
                return text;
            }
            removed.add(new RemovedEntry(file, oldPath, newPath));
            return after;
        }

        private void compareRemainingFiles(Path backup) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(backup)) {
                files = walk.filter(Files::isRegularFile).toList();
            } catch (IOException e) {
                error("Could not list the backup folder: " + e);
                return;
            }
            for (Path path : files) {
                String relative = backup.relativize(path).toString().replace(File.separatorChar, '/');
                boolean stateFile = path.getFileName().toString().equals(ConfigDefaultsInserter.SEEN_FILE);
                if (spec.carryOvers.containsKey(relative) || isKept(relative) || stateFile) {
                    continue;
                }
                List<String> baselines = knownDefaults(relative);
                if (baselines.isEmpty()) {
                    withoutBaseline.add(relative);
                    continue;
                }
                try {
                    String content = decode(Files.readAllBytes(path));
                    boolean unchanged = spec.olderDefaultsRoots.isEmpty()
                            ? sameContent(content, baselines.get(0))
                            : matchesKnownDefaults(content, withCurrentDefaults(relative, baselines));
                    if (!unchanged) {
                        customized.add(relative);
                    }
                } catch (IOException e) {
                    error("Could not read " + relative + " from the backup: " + e);
                }
            }
        }

        // Default texts of the file from the legacy root and each older root.
        private List<String> knownDefaults(String relative) {
            List<String> roots = new ArrayList<>();
            if (spec.legacyDefaultsRoot != null) {
                roots.add(spec.legacyDefaultsRoot);
            }
            roots.addAll(spec.olderDefaultsRoots);
            List<String> texts = new ArrayList<>();
            for (String root : roots) {
                byte[] bytes = readResource(root + relative);
                if (bytes != null) {
                    texts.add(new String(bytes, StandardCharsets.UTF_8));
                }
            }
            return texts;
        }

        // The current bundled file counts too, for a folder that a downgrade to the old
        // version had mixed with it.
        private List<String> withCurrentDefaults(String relative, List<String> baselines) {
            byte[] current = readResource(relative);
            if (current == null) {
                return baselines;
            }
            List<String> texts = new ArrayList<>(baselines);
            texts.add(new String(current, StandardCharsets.UTF_8));
            return texts;
        }

        private boolean isKept(String relative) {
            return spec.keepFiles.stream().anyMatch(kept -> relative.equals(kept) || relative.startsWith(kept + "/"));
        }

        private byte[] readResource(String path) {
            try (InputStream in = spec.resources.apply(path)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                error("Could not read bundled resource " + path + ": " + e);
                return null;
            }
        }

        private Report abort(String message) {
            error(message + "; the old files were left untouched");
            return report(false, null);
        }

        private void error(String message) {
            errors.add(message);
            if (spec.logger != null) {
                spec.logger.warning(message);
            }
        }

        private Report report(boolean performed, Path backup) {
            return new Report(performed, backup, List.copyOf(carried), List.copyOf(dropped), List.copyOf(removed),
                    List.copyOf(missing), List.copyOf(customized), List.copyOf(withoutBaseline), List.copyOf(errors));
        }
    }

    /**
     * {@code true} if the file has one of the dotted key paths, found on its key
     * lines like the marker keys; {@code false} for a missing or unreadable file.
     */
    public static boolean hasAnyKey(File yamlFile, Collection<String> paths) {
        if (yamlFile == null || !yamlFile.isFile() || paths == null || paths.isEmpty()) {
            return false;
        }
        try {
            Set<String> keys = keyPaths(Files.readAllBytes(yamlFile.toPath()));
            return paths.stream().anyMatch(keys::contains);
        } catch (IOException | StackOverflowError e) {
            return false;
        }
    }

    public static boolean hasAnyKey(File yamlFile, String... paths) {
        return hasAnyKey(yamlFile, paths == null ? List.of() : Arrays.asList(paths));
    }

    // Dotted paths of the key lines; checked on the raw text, so it works for files
    // that aren't valid YAML too.
    private static Set<String> keyPaths(byte[] bytes) {
        Set<String> paths = new HashSet<>();
        for (Line line : YamlLines.parse(decode(bytes)).lines()) {
            if (line.path() != null) {
                paths.add(line.path());
            }
        }
        return paths;
    }

    // --- text edits on the fresh file ---

    private static String replaceScalar(String text, String path, Object value) {
        Parsed parsed = YamlLines.parse(text);
        int index = YamlLines.indexOfPath(parsed.lines(), path);
        if (index < 0) {
            return null;
        }
        List<Line> lines = parsed.lines();
        Line key = lines.get(index);
        String raw = lineText(text, key);
        int colon = YamlLines.afterColon(raw);
        if (colon < 0) {
            return null;
        }
        ValueSpan old = ValueSpan.of(raw, colon);
        String newLine = raw.substring(0, colon) + " " + old.properties() + formatScalar(value, old.style())
                + old.comment();
        int end = lines.get(YamlLines.blockEnd(lines, index)).end();
        return text.substring(0, key.start()) + newLine + YamlLines.lineEndingOf(text) + text.substring(end);
    }

    // Swaps the list items; comments inside the old block stay around the new
    // items.
    private static String replaceList(String text, String path, List<?> items) {
        Parsed parsed = YamlLines.parse(text);
        int index = YamlLines.indexOfPath(parsed.lines(), path);
        if (index < 0) {
            return null;
        }
        List<Line> lines = parsed.lines();
        Line key = lines.get(index);
        int blockEnd = YamlLines.blockEnd(lines, index);
        String raw = lineText(text, key);
        int colon = YamlLines.afterColon(raw);
        if (colon < 0) {
            return null;
        }
        String eol = YamlLines.lineEndingOf(text);
        int step = parsed.indentStep() > 0 ? parsed.indentStep() : 2;
        int itemIndent = key.indent() + step;
        Style style = Style.PLAIN;
        StringBuilder above = new StringBuilder();
        StringBuilder below = new StringBuilder();
        boolean seenItem = false;
        for (int i = index + 1; i <= blockEnd; i++) {
            Line line = lines.get(i);
            if (line.kind() == Kind.LIST_ITEM) {
                if (!seenItem) {
                    itemIndent = line.indent();
                    style = Style.of(line.content().substring(1).strip());
                }
                seenItem = true;
            } else if (line.kind() == Kind.COMMENT) {
                (seenItem ? below : above).append(lineText(text, line)).append(eol);
            }
        }
        ValueSpan old = ValueSpan.of(raw, colon);
        StringBuilder block = new StringBuilder(raw.substring(0, colon));
        String properties = old.properties().strip();
        block.append(properties.isEmpty() ? "" : " " + properties).append(items.isEmpty() ? " []" : "")
                .append(old.comment()).append(eol).append(above);
        for (Object item : items) {
            block.append(" ".repeat(itemIndent)).append("- ").append(formatScalar(item, style)).append(eol);
        }
        block.append(below);
        return text.substring(0, key.start()) + block + text.substring(lines.get(blockEnd).end());
    }

    private static String insertChild(String text, String section, String path, Object value) {
        Parsed parsed = YamlLines.parse(text);
        List<Line> lines = parsed.lines();
        int index = YamlLines.indexOfPath(lines, section);
        if (index < 0) {
            return null;
        }
        Line sectionLine = lines.get(index);
        String flow = YamlLines.valueOf(sectionLine.content());
        if (!flow.isEmpty() && !flow.equals("{}")) {
            String blockStyle = blockForm(text, section);
            return blockStyle == null ? null : insertChild(blockStyle, section, path, value);
        }
        int blockEnd = YamlLines.blockEnd(lines, index);
        int step = parsed.indentStep() > 0 ? parsed.indentStep() : 2;
        int indent = sectionLine.indent() + step;
        for (int i = index + 1; i <= blockEnd; i++) {
            if (lines.get(i).kind() == Kind.KEY) {
                indent = lines.get(i).indent();
                break;
            }
        }
        String eol = YamlLines.lineEndingOf(text);
        String name = path.substring(section.length() + 1);
        String keyToken = PLAIN_KEY.matcher(name).matches() ? name : quote(name);
        StringBuilder block = new StringBuilder(" ".repeat(indent)).append(keyToken).append(":");
        if (value instanceof List<?> list) {
            block.append(list.isEmpty() ? " []" : "").append(eol);
            for (Object item : list) {
                block.append(" ".repeat(indent + step)).append("- ").append(formatScalar(item, Style.PLAIN))
                        .append(eol);
            }
        } else {
            block.append(" ").append(formatScalar(value, value instanceof Date ? Style.PLAIN : Style.DOUBLE))
                    .append(eol);
        }

        // An empty "section: {}" line must lose its flow value to take children.
        String head = text.substring(0, sectionLine.start());
        String sectionRaw = lineText(text, sectionLine);
        int colon = YamlLines.afterColon(sectionRaw);
        if (blockEnd == index && colon > 0) {
            ValueSpan old = ValueSpan.of(sectionRaw, colon);
            String properties = old.properties().strip();
            String line = sectionRaw.substring(0, colon) + (properties.isEmpty() ? "" : " " + properties)
                    + old.comment() + eol;
            return head + line + block + text.substring(sectionLine.end());
        }
        int offset = lines.get(blockEnd).end();
        String before = text.substring(0, offset);
        String separator = before.endsWith("\n") ? "" : eol;
        return before + separator + block + text.substring(offset);
    }

    // The section rewritten from one inline line like "soils: {DIRT: true}" to one
    // key per line; null if it spans lines or holds values that can't be written.
    private static String blockForm(String text, String section) {
        Parsed parsed = YamlLines.parse(text);
        List<Line> lines = parsed.lines();
        int index = YamlLines.indexOfPath(lines, section);
        YamlConfiguration before = YamlLines.parseYaml(text);
        if (index < 0 || YamlLines.blockEnd(lines, index) != index || before == null
                || !before.isConfigurationSection(section)) {
            return null;
        }
        Line line = lines.get(index);
        String raw = lineText(text, line);
        int colon = YamlLines.afterColon(raw);
        String rest = raw.substring(colon).stripLeading();
        int propertiesEnd = YamlLines.propertiesEnd(rest);
        String properties = rest.substring(0, propertiesEnd).strip();
        String flow = rest.substring(propertiesEnd);
        int end = YamlLines.flowEnd(flow);
        String tail = end < 0 ? "" : flow.substring(end);
        if (!flow.startsWith("{") || end < 0 || !(tail.isBlank() || tail.stripLeading().startsWith("#"))) {
            return null;
        }
        String eol = YamlLines.lineEndingOf(text);
        int step = parsed.indentStep() > 0 ? parsed.indentStep() : 2;
        int indent = line.indent() + step;
        StringBuilder block = new StringBuilder(raw.substring(0, colon));
        block.append(properties.isEmpty() ? "" : " " + properties).append(tail.isBlank() ? "" : tail).append(eol);
        for (Map.Entry<String, Object> entry : before.getConfigurationSection(section).getValues(false).entrySet()) {
            Object value = entry.getValue();
            if (!isWritable(value)) {
                return null;
            }
            block.append(" ".repeat(indent)).append(YamlLines.keyToken(entry.getKey())).append(':');
            if (value instanceof List<?> list) {
                block.append(list.isEmpty() ? " []" : "").append(eol);
                for (Object item : list) {
                    block.append(" ".repeat(indent + step)).append("- ").append(formatScalar(item, Style.PLAIN))
                            .append(eol);
                }
            } else {
                block.append(' ').append(formatScalar(value, Style.PLAIN)).append(eol);
            }
        }
        String after = text.substring(0, line.start()) + block + text.substring(line.end());
        YamlConfiguration check = YamlLines.parseYaml(after);
        return check != null && check.isConfigurationSection(section)
                && sameValue(before.getConfigurationSection(section), check.getConfigurationSection(section))
                        ? after
                        : null;
    }

    private static String lineText(String text, Line line) {
        String raw = text.substring(line.start(), line.end());
        if (raw.endsWith("\n")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private enum Style {
        PLAIN, DOUBLE, SINGLE;

        static Style of(String value) {
            if (value.startsWith("\"")) {
                return DOUBLE;
            }
            return value.startsWith("'") ? SINGLE : PLAIN;
        }
    }

    // The value part of a "key: &anchor value # comment" line; properties (anchors,
    // tags) keep a trailing space, the comment its leading spaces.
    private record ValueSpan(String properties, Style style, String comment) {
        static ValueSpan of(String raw, int afterColon) {
            String rest = raw.substring(afterColon);
            String value = rest.stripLeading();
            int propertiesEnd = YamlLines.propertiesEnd(value);
            String properties = value.substring(0, propertiesEnd);
            if (!properties.isEmpty() && !properties.endsWith(" ")) {
                properties = properties.stripTrailing() + " ";
            }
            value = value.substring(propertiesEnd);
            int offset = rest.length() - value.length();
            int end;
            if (value.startsWith("\"")) {
                end = closingQuote(value, '"');
            } else if (value.startsWith("'")) {
                end = closingQuote(value, '\'');
            } else {
                int hash = value.startsWith("#") ? 0 : value.indexOf(" #");
                end = hash < 0 ? value.length() : hash;
            }
            String tail = rest.substring(Math.min(rest.length(), offset + end));
            int hash = tail.indexOf('#');
            String comment = hash < 0 ? "" : tail.substring(0, hash).isBlank() ? tail : "";
            if (!comment.isEmpty() && !comment.startsWith(" ")) {
                comment = " " + comment;
            }
            return new ValueSpan(properties, Style.of(value), comment);
        }

        private static int closingQuote(String value, char quote) {
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    if (quote == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        return i + 1;
                    }
                }
            }
            return value.length();
        }
    }

    private static String formatScalar(Object value, Style style) {
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Date date) {
            return style == Style.PLAIN ? dateText(date) : formatScalar(dateText(date), style);
        }
        String text = String.valueOf(value);
        return switch (style) {
            case SINGLE -> "'" + text.replace("'", "''") + "'";
            case PLAIN -> isPlainSafe(text) ? text : quote(text);
            case DOUBLE -> quote(text);
        };
    }

    private static boolean isPlainSafe(String text) {
        return PLAIN_SCALAR.matcher(text).matches() && !YAML_WORDS.contains(text.toLowerCase(Locale.ROOT));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // --- value helpers ---

    private static boolean isIgnored(String path, Set<String> ignored) {
        for (String entry : ignored) {
            boolean matches = entry.endsWith(".*")
                    ? path.startsWith(entry.substring(0, entry.length() - 1))
                    : path.equals(entry);
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static Object leafOrNull(YamlConfiguration yaml, String path) {
        Object value = yaml.isSet(path) ? yaml.get(path) : null;
        return value instanceof ConfigurationSection ? null : value;
    }

    private static boolean isWritable(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().allMatch(LegacyDataUpgrade::isScalar);
        }
        return isScalar(value);
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Date;
    }

    // YAML timestamps as written: a date alone, else the UTC instant.
    private static String dateText(Date date) {
        Instant instant = date.toInstant();
        return instant.equals(instant.truncatedTo(ChronoUnit.DAYS))
                ? instant.atOffset(ZoneOffset.UTC).toLocalDate().toString()
                : instant.toString();
    }

    static boolean sameValue(Object a, Object b) {
        if (a instanceof Date date) {
            a = dateText(date);
        }
        if (b instanceof Date date) {
            b = dateText(date);
        }
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!sameValue(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof ConfigurationSection x && b instanceof ConfigurationSection y) {
            return sameContent(x, y);
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static boolean sameContent(ConfigurationSection a, ConfigurationSection b) {
        Set<String> keys = a.getKeys(true);
        if (!keys.equals(b.getKeys(true))) {
            return false;
        }
        for (String key : keys) {
            Object x = a.get(key);
            Object y = b.get(key);
            if (!(x instanceof ConfigurationSection) && !sameValue(x, y)) {
                return false;
            }
        }
        return true;
    }

    // Every value the file sets must be that key's default in one known release;
    // keys it lacks don't count, as an older release never added them.
    static boolean matchesKnownDefaults(String content, List<String> releases) {
        YamlConfiguration file = YamlLines.parseYaml(content);
        List<YamlConfiguration> known = releases.stream().map(YamlLines::parseYaml).filter(Objects::nonNull).toList();
        if (file == null || known.isEmpty()) {
            return releases.stream().anyMatch(release -> normalize(release).equals(normalize(content)));
        }
        for (String key : file.getKeys(true)) {
            Object value = file.get(key);
            if (value instanceof ConfigurationSection) {
                continue;
            }
            if (!isDefaultIn(known, key, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultIn(List<YamlConfiguration> releases, String path, Object value) {
        return releases.stream().anyMatch(release -> {
            Object releaseDefault = leafOrNull(release, path);
            return releaseDefault != null && sameValue(value, releaseDefault);
        });
    }

    // Compares parsed values; line endings, indentation and quoting don't count.
    static boolean sameContent(String a, String b) {
        YamlConfiguration x = YamlLines.parseYaml(a);
        YamlConfiguration y = YamlLines.parseYaml(b);
        if (x != null && y != null) {
            return sameContent(x, y);
        }
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String text) {
        return YamlLines.stripBom(text).replace("\r\n", "\n").lines().map(String::stripTrailing)
                .reduce("", (x, y) -> x + y + "\n").strip();
    }

    private static List<String> difference(List<?> from, List<?> without) {
        List<String> result = new ArrayList<>();
        for (Object item : from) {
            if (without.stream().noneMatch(other -> sameValue(item, other))) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static String display(Object value) {
        if (value instanceof List<?> list) {
            return "[" + String.join(", ", list.stream().map(LegacyDataUpgrade::display).toList()) + "]";
        }
        return value instanceof Date date ? dateText(date) : String.valueOf(value);
    }

    private static String decode(byte[] bytes) {
        return new String(bytes, YamlLines.isValidUtf8(bytes) ? StandardCharsets.UTF_8 : ConfigDefaultsInserter.ANSI);
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        AtomicFiles.write(target, bytes);
    }

    private static void copyBack(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        if (!Files.isDirectory(source)) {
            write(target, Files.readAllBytes(source));
            return;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                write(target.resolve(source.relativize(path).toString()), Files.readAllBytes(path));
            }
        }
    }
}
