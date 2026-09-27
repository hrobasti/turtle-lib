package net.kroet.turtlelib.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Remembers the state of a plugin's data files and reports what changed since
 * the last snapshot: setting by setting for YAML files, file by file for
 * folders like {@code lang/}. Wording and logging stay with the plugin.
 */
public final class FileChangeTracker {
    private final File dataFolder;
    private final Logger logger;
    private final List<String> yamlFiles;
    private final List<Folder> folders;
    private Map<String, Map<String, String>> yamlSnapshot;
    private Map<String, Long> folderSnapshot;

    /** One changed setting; {@code newValue} is {@code null} if it was removed. */
    public record KeyChange(String key, String newValue) {
        /** {@code key=value}, or {@code key=<removed>}. */
        public String describe() {
            return key + "=" + (newValue == null ? "<removed>" : newValue);
        }
    }

    /** Changed settings of one YAML file, sorted by key. */
    public record FileKeyChanges(String file, List<KeyChange> changes) {
        /** The changes joined with {@code ", "}, e.g. {@code a=1, b=<removed>}. */
        public String describe() {
            return String.join(", ", changes.stream().map(KeyChange::describe).toList());
        }
    }

    /**
     * Result of {@link #compareAndUpdate()}; paths are relative to the data folder
     * with {@code /} separators. Unreadable files keep their previous snapshot and
     * are listed in {@code unreadableFiles}.
     */
    public record Changes(boolean firstSnapshot, List<FileKeyChanges> keyChanges, List<String> changedFiles,
            List<String> addedFiles, List<String> removedFiles, List<String> unreadableFiles) {

        /**
         * {@code true} if nothing changed and every file could be read, so "no changes"
         * is safe to report.
         */
        public boolean isEmpty() {
            return keyChanges.isEmpty() && changedFiles.isEmpty() && addedFiles.isEmpty() && removedFiles.isEmpty()
                    && unreadableFiles.isEmpty();
        }

        /** {@code true} if there are changed settings or folder files to list. */
        public boolean hasListedChanges() {
            return !keyChanges.isEmpty() || !changedFiles.isEmpty() || !addedFiles.isEmpty()
                    || !removedFiles.isEmpty();
        }

        /** Changed, added and removed folder files together, sorted. */
        public List<String> allChangedFiles() {
            TreeSet<String> all = new TreeSet<>(changedFiles);
            all.addAll(addedFiles);
            all.addAll(removedFiles);
            return List.copyOf(all);
        }
    }

    private record Folder(String path, String suffix) {
    }

    private FileChangeTracker(Builder builder) {
        this.dataFolder = builder.dataFolder;
        this.logger = builder.logger;
        this.yamlFiles = List.copyOf(builder.yamlFiles);
        this.folders = List.copyOf(builder.folders);
    }

    public static Builder builder(File dataFolder, Logger logger) {
        return new Builder(Objects.requireNonNull(dataFolder, "dataFolder"), logger);
    }

    public static final class Builder {
        private final File dataFolder;
        private final Logger logger;
        private final List<String> yamlFiles = new ArrayList<>();
        private final List<Folder> folders = new ArrayList<>();

        private Builder(File dataFolder, Logger logger) {
            this.dataFolder = dataFolder;
            this.logger = logger;
        }

        /** A YAML file compared setting by setting, e.g. {@code config.yml}. */
        public Builder yamlFile(String relativePath) {
            yamlFiles.add(relativePath);
            return this;
        }

        /** A folder whose files ending in {@code suffix} are compared by content. */
        public Builder folder(String relativePath, String suffix) {
            folders.add(new Folder(relativePath, suffix.toLowerCase(Locale.ROOT)));
            return this;
        }

        public FileChangeTracker build() {
            return new FileChangeTracker(this);
        }
    }

    /** {@code true} once a snapshot exists. */
    public boolean hasSnapshot() {
        return yamlSnapshot != null;
    }

    /**
     * Compares the files with the last snapshot and stores their current state as
     * the new snapshot. Without an earlier snapshot it only stores one and returns
     * a result with {@code firstSnapshot} set.
     */
    public Changes compareAndUpdate() {
        List<String> unreadable = new ArrayList<>();
        Map<String, Map<String, String>> yaml = new LinkedHashMap<>();
        for (String file : yamlFiles) {
            Map<String, String> values = readYaml(file, unreadable);
            if (values != null) {
                yaml.put(file, values);
            } else if (yamlSnapshot != null && yamlSnapshot.containsKey(file)) {
                yaml.put(file, yamlSnapshot.get(file));
            }
        }
        Map<String, Long> hashes = new LinkedHashMap<>();
        for (Folder folder : folders) {
            readFolder(folder, hashes, unreadable);
        }

        boolean first = yamlSnapshot == null;
        List<FileKeyChanges> keyChanges = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        if (!first) {
            for (String file : yamlFiles) {
                List<KeyChange> changes = diff(yamlSnapshot.getOrDefault(file, Map.of()), yaml.getOrDefault(file,
                        Map.of()));
                if (!changes.isEmpty()) {
                    keyChanges.add(new FileKeyChanges(file, changes));
                }
            }
            TreeSet<String> names = new TreeSet<>(folderSnapshot.keySet());
            names.addAll(hashes.keySet());
            for (String name : names) {
                Long before = folderSnapshot.get(name);
                Long now = hashes.get(name);
                if (before == null) {
                    added.add(name);
                } else if (now == null) {
                    removed.add(name);
                } else if (!before.equals(now)) {
                    changed.add(name);
                }
            }
        }
        yamlSnapshot = yaml;
        folderSnapshot = hashes;
        return new Changes(first, List.copyOf(keyChanges), List.copyOf(changed), List.copyOf(added),
                List.copyOf(removed), List.copyOf(unreadable));
    }

    private Map<String, String> readYaml(String relativePath, List<String> unreadable) {
        File file = new File(dataFolder, relativePath);
        if (!file.isFile()) {
            return Map.of();
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String text = new String(bytes, YamlLines.isValidUtf8(bytes)
                    ? StandardCharsets.UTF_8
                    : ConfigDefaultsInserter.ANSI);
            YamlConfiguration yaml = YamlLines.parseYaml(text);
            if (yaml == null) {
                warn(relativePath + " isn't valid YAML; its changes can't be listed");
                unreadable.add(relativePath);
                return null;
            }
            Map<String, String> values = new LinkedHashMap<>();
            flatten(yaml, values);
            return values;
        } catch (IOException e) {
            warn("Could not read " + relativePath + " to list its changes: " + e.getMessage());
            unreadable.add(relativePath);
            return null;
        }
    }

    // A file that can't be read keeps its previous hash, so it counts as unchanged.
    private void readFolder(Folder folder, Map<String, Long> hashes, List<String> unreadable) {
        File dir = new File(dataFolder, folder.path());
        // Hidden files such as TurtleLib's .seen-defaults.yml aren't the admin's
        // content.
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(folder.suffix())
                && !name.startsWith("."));
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = folder.path().isEmpty() ? file.getName() : folder.path() + "/" + file.getName();
            try {
                CRC32 crc = new CRC32();
                crc.update(Files.readAllBytes(file.toPath()));
                hashes.put(name, crc.getValue() ^ (file.length() << 32));
            } catch (IOException e) {
                warn("Could not read " + name + " to list its changes: " + e.getMessage());
                unreadable.add(name);
                Long previous = folderSnapshot == null ? null : folderSnapshot.get(name);
                if (previous != null) {
                    hashes.put(name, previous);
                }
            }
        }
    }

    private static void flatten(ConfigurationSection section, Map<String, String> out) {
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            Object value = section.get(key);
            out.put(key, value instanceof List<?> list
                    ? String.join(", ", list.stream().map(String::valueOf).toList())
                    : String.valueOf(value));
        }
    }

    private static List<KeyChange> diff(Map<String, String> before, Map<String, String> now) {
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(now.keySet());
        List<KeyChange> changes = new ArrayList<>();
        for (String key : keys) {
            String newValue = now.get(key);
            if (!Objects.equals(before.get(key), newValue)) {
                changes.add(new KeyChange(key, newValue));
            }
        }
        return List.copyOf(changes);
    }

    private void warn(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
