package net.kroet.turtlelib.helper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.YamlLines.Key;
import net.kroet.turtlelib.helper.YamlLines.Kind;
import net.kroet.turtlelib.helper.YamlLines.Line;
import net.kroet.turtlelib.helper.YamlLines.Parsed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Renames config/lang keys directly in the file's raw text, so quoting,
 * indentation, comments and line breaks stay exactly as written - unlike a
 * round-trip via {@code YamlConfiguration#save}, which normalizes them.
 */
public final class ConfigKeyMigrator {
    // Section of the folder's state file listing, per file, old keys already
    // reported.
    static final String REPORTED_DUPLICATES = "reported_old_duplicates";

    private ConfigKeyMigrator() {
    }

    /**
     * Style rename: each changed segment of the dotted paths is renamed as a key
     * name at any depth. Returns true if the file itself was rewritten.
     */
    public static boolean rewriteLegacyKeysInFile(File file, Logger logger, String fileLabel,
            Map<String, String> oldToNew) {
        return rewriteLegacyKeysInFile(file, logger, fileLabel, oldToNew, null);
    }

    /**
     * Like {@link #rewriteLegacyKeysInFile(File, Logger, String, Map)}; an old key
     * whose new name exists too is also removed if its value is still the default
     * in {@code legacyDefaults}, the old version's file (UTF-8, may be null).
     */
    public static boolean rewriteLegacyKeysInFile(File file, Logger logger, String fileLabel,
            Map<String, String> oldToNew, InputStream legacyDefaults) {
        Map<String, String> leafRenames = deriveLeafRenames(oldToNew == null ? Map.of() : oldToNew);
        if (leafRenames.isEmpty()) {
            return false;
        }
        return guarded(logger, fileLabel, () -> {
            Source source = Source.read(file, logger, fileLabel);
            return source != null && new StyleRename(source, legacyDefaults, leafRenames).run();
        });
    }

    /**
     * Renames exactly the given dotted paths; a new parent moves the key with its
     * block and comments. Returns true if the file itself was rewritten.
     */
    public static boolean rewriteKeyPathsInFile(File file, Logger logger, String fileLabel,
            Map<String, String> oldToNew) {
        return rewriteKeyPathsInFile(file, logger, fileLabel, oldToNew, null);
    }

    /**
     * Like {@link #rewriteKeyPathsInFile(File, Logger, String, Map)}, with the old
     * version's defaults as in
     * {@link #rewriteLegacyKeysInFile(File, Logger, String, Map, InputStream)}.
     */
    public static boolean rewriteKeyPathsInFile(File file, Logger logger, String fileLabel,
            Map<String, String> oldToNew, InputStream legacyDefaults) {
        Map<String, String> renames = checkPathRenames(oldToNew == null ? Map.of() : oldToNew);
        if (renames.isEmpty()) {
            return false;
        }
        return guarded(logger, fileLabel, () -> {
            Source source = Source.read(file, logger, fileLabel);
            return source != null && new PathRename(source, legacyDefaults, renames).run();
        });
    }

    // Any failure while renaming, even a stack overflow, leaves the file unchanged.
    private static boolean guarded(Logger logger, String fileLabel, BooleanSupplier rename) {
        try {
            return rename.getAsBoolean();
        } catch (RuntimeException | StackOverflowError e) {
            warn(logger, "Could not rename keys in " + fileLabel + " (" + e + "); the file was left unchanged");
            return false;
        }
    }

    /**
     * Old segment name to new segment name, applied to keys at any depth. Throws
     * {@link IllegalArgumentException} if the renames conflict, chain or change the
     * nesting depth.
     */
    static Map<String, String> deriveLeafRenames(Map<String, String> oldToNew) {
        Map<String, String> leafRenames = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : oldToNew.entrySet()) {
            String[] oldParts = segments(entry.getKey());
            String[] newParts = segments(entry.getValue());
            if (oldParts.length != newParts.length) {
                throw new IllegalArgumentException("'" + entry.getKey() + "' -> '" + entry.getValue()
                        + "' changes the nesting depth, which a rename by key name can't do; use"
                        + " ConfigKeyMigrator.rewriteKeyPathsInFile (or carryOverValuesByPath) for moves");
            }
            for (int i = 0; i < oldParts.length; i++) {
                if (oldParts[i].equals(newParts[i])) {
                    continue;
                }
                String earlier = leafRenames.putIfAbsent(oldParts[i], newParts[i]);
                if (earlier != null && !earlier.equals(newParts[i])) {
                    throw new IllegalArgumentException("Conflicting renames of the key name '" + oldParts[i] + "': '"
                            + earlier + "' and '" + newParts[i] + "'");
                }
            }
        }
        for (Map.Entry<String, String> rename : leafRenames.entrySet()) {
            String again = leafRenames.get(rename.getValue());
            if (again != null) {
                throw new IllegalArgumentException("Chained renames: '" + rename.getKey() + "' -> '"
                        + rename.getValue() + "' and '" + rename.getValue() + "' -> '" + again + "'");
            }
        }
        return leafRenames;
    }

    // Validated copy of exact path renames, in the caller's order. Throws
    // IllegalArgumentException for renames whose result depends on their order.
    static Map<String, String> checkPathRenames(Map<String, String> oldToNew) {
        Map<String, String> renames = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : oldToNew.entrySet()) {
            String oldPath = entry.getKey();
            String newPath = entry.getValue();
            segments(oldPath);
            segments(newPath);
            if (oldPath.equals(newPath)) {
                continue;
            }
            if (inside(newPath, oldPath) || inside(oldPath, newPath)) {
                throw new IllegalArgumentException("'" + oldPath + "' can't be renamed to '" + newPath
                        + "': one path contains the other");
            }
            renames.put(oldPath, newPath);
        }
        List<Map.Entry<String, String>> entries = List.copyOf(renames.entrySet());
        for (Map.Entry<String, String> a : entries) {
            for (Map.Entry<String, String> b : entries) {
                if (a == b) {
                    continue;
                }
                if (overlap(a.getKey(), b.getKey())) {
                    throw new IllegalArgumentException("'" + a.getKey() + "' and '" + b.getKey()
                            + "' overlap; rename a section or the keys inside it, not both");
                }
                if (overlap(a.getKey(), b.getValue())) {
                    throw new IllegalArgumentException("Chained renames: '" + b.getKey() + "' -> '" + b.getValue()
                            + "' overlaps '" + a.getKey() + "', which is renamed to '" + a.getValue() + "'");
                }
                if (inside(a.getValue(), b.getValue())) {
                    throw new IllegalArgumentException("'" + a.getKey() + "' -> '" + a.getValue()
                            + "' goes inside the renamed '" + b.getKey() + "' -> '" + b.getValue() + "'");
                }
            }
        }
        return renames;
    }

    private static String[] segments(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Empty key path in the renames");
        }
        String[] parts = path.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("'" + path + "' isn't a valid dotted key path");
            }
        }
        return parts;
    }

    private static boolean inside(String path, String section) {
        return path.startsWith(section + ".");
    }

    private static boolean overlap(String a, String b) {
        return a.equals(b) || inside(a, b) || inside(b, a);
    }

    // The file as ISO-8859-1 chars (every byte survives), its charset and parsed
    // values.
    private record Source(File file, Logger logger, String label, String text, Charset charset,
            YamlConfiguration yaml) {

        static Source read(File file, Logger logger, String label) {
            if (file == null || !file.isFile()) {
                return null;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file.toPath());
            } catch (IOException e) {
                warn(logger, "Could not read " + label + " to rename keys: " + e.getMessage());
                return null;
            }
            Charset charset = YamlLines.isValidUtf8(bytes) ? StandardCharsets.UTF_8 : ConfigDefaultsInserter.ANSI;
            String decoded = new String(bytes, charset);
            YamlConfiguration yaml = YamlLines.parseYaml(decoded);
            if (yaml == null) {
                warn(logger, "Skipped renaming keys in " + label + ": it isn't valid YAML ("
                        + YamlLines.yamlError(decoded) + ")");
                return null;
            }
            return new Source(file, logger, label, new String(bytes, StandardCharsets.ISO_8859_1), charset, yaml);
        }

        Parsed parse(String text) {
            return YamlLines.parse(text, charset);
        }

        YamlConfiguration yamlOf(String text) {
            return YamlLines.parseYaml(new String(text.getBytes(StandardCharsets.ISO_8859_1), charset));
        }

        // A key token as the file's bytes; characters the charset can't store are
        // escaped in double quotes.
        String token(String name, String oldToken) {
            CharsetEncoder encoder = charset.newEncoder();
            String token;
            if (oldToken.startsWith("'")) {
                token = "'" + name.replace("'", "''") + "'";
            } else if (oldToken.startsWith("\"")) {
                token = YamlLines.quotedKey(name, encoder);
            } else {
                token = YamlLines.keyToken(name);
            }
            if (!encoder.canEncode(token)) {
                token = YamlLines.quotedKey(name, encoder);
            }
            return new String(token.getBytes(charset), StandardCharsets.ISO_8859_1);
        }
    }

    private abstract static class Rename {
        final Source source;
        final YamlConfiguration legacy;
        final List<String> renamed = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        final Set<String> removedPaths = new HashSet<>();
        private final Map<String, String> kept = new LinkedHashMap<>();

        Rename(Source source, InputStream legacyDefaults) {
            this.source = source;
            this.legacy = readLegacy(legacyDefaults);
        }

        private static YamlConfiguration readLegacy(InputStream legacyDefaults) {
            if (legacyDefaults == null) {
                return null;
            }
            try {
                return YamlLines.parseYaml(new String(legacyDefaults.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return null; // without the old defaults only equal values count
            }
        }

        abstract boolean run();

        // An old key next to its new name can go if its value equals the new key's
        // value or the old version's default.
        boolean unchanged(String oldPath, String holderPath, String legacyPath) {
            YamlConfiguration yaml = source.yaml();
            if (oldPath == null || !yaml.isSet(oldPath)) {
                return false;
            }
            Object value = yaml.get(oldPath);
            return (holderPath != null && yaml.isSet(holderPath)
                    && LegacyDataUpgrade.sameValue(value, yaml.get(holderPath)))
                    || (legacy != null && legacyPath != null && legacy.isSet(legacyPath)
                            && LegacyDataUpgrade.sameValue(value, legacy.get(legacyPath)));
        }

        void keep(String name, String warning) {
            kept.putIfAbsent(name, warning);
        }

        // Character range of a key line and its block; the BOM stays.
        static int[] blockRange(String text, List<Line> lines, int index) {
            int start = lines.get(index).start();
            return new int[]{start == 0 ? YamlLines.bomLength(text) : start,
                    lines.get(YamlLines.blockEnd(lines, index)).end()};
        }

        // Writes the result if Bukkit reads every value back where it belongs.
        boolean finish(String result, Function<String, String> newPathOf, Map<String, String> listKeyRenames,
                String what) {
            reportKeptOnce();
            if (renamed.isEmpty() && removed.isEmpty()) {
                return false;
            }
            YamlConfiguration after = source.yamlOf(result);
            if (after == null || !keepsValues(source.yaml(), after, newPathOf, listKeyRenames)) {
                warn(source.logger(), "Could not rename keys in " + source.label()
                        + " safely; the file was left unchanged");
                return false;
            }
            try {
                AtomicFiles.write(source.file().toPath(), result.getBytes(StandardCharsets.ISO_8859_1),
                        source.logger());
            } catch (IOException e) {
                warn(source.logger(), "Failed to write renamed keys back to " + source.label() + ": " + e.getMessage());
                return false;
            }
            Logger logger = source.logger();
            if (logger != null && !renamed.isEmpty()) {
                logger.info("Renamed " + renamed.size() + " " + what + " directly in " + source.label()
                        + " (comments and formatting preserved): " + String.join(", ", renamed));
            }
            if (logger != null && !removed.isEmpty()) {
                logger.info("Removed " + removed.size() + " old key(s) from " + source.label() + " that duplicated"
                        + " their renamed version with an unchanged value: " + String.join(", ", removed));
            }
            return true;
        }

        // Kept old keys are reported once per file, tracked in the folder's state
        // file, so the warning doesn't repeat on every start.
        private void reportKeptOnce() {
            if (kept.isEmpty()) {
                return;
            }
            File stateFile = new File(source.file().getAbsoluteFile().getParentFile(), ConfigDefaultsInserter.SEEN_FILE);
            YamlConfiguration state = ConfigDefaultsInserter.loadState(stateFile, source.logger());
            String listPath = REPORTED_DUPLICATES + '\0' + source.file().getName();
            Set<String> reported = new LinkedHashSet<>(state.getStringList(listPath));
            boolean changed = false;
            for (Map.Entry<String, String> entry : kept.entrySet()) {
                if (reported.add(entry.getKey())) {
                    changed = true;
                    warn(source.logger(), entry.getValue());
                }
            }
            if (changed) {
                state.set(listPath, new ArrayList<>(reported));
                ConfigDefaultsInserter.saveState(stateFile, state, source.logger());
            }
        }
    }

    // Renames key names at any depth, in one pass over the original text.
    private static final class StyleRename extends Rename {
        private final Map<String, String> leafRenames;
        private final Map<String, String> newToOld = new HashMap<>();
        private final Set<String> keptPaths = new HashSet<>();

        StyleRename(Source source, InputStream legacyDefaults, Map<String, String> leafRenames) {
            super(source, legacyDefaults);
            this.leafRenames = leafRenames;
            leafRenames.forEach((oldName, newName) -> newToOld.putIfAbsent(newName, oldName));
        }

        @Override
        boolean run() {
            String text = source.text();
            List<Line> lines = source.parse(text).lines();
            Map<Integer, Set<String>> names = new HashMap<>();
            for (Line line : lines) {
                if (line.key() != null) {
                    names.computeIfAbsent(line.key().owner(), owner -> new HashSet<>()).add(line.key().name());
                }
            }
            Map<Integer, Map<String, String>> holders = new HashMap<>();
            StringBuilder result = new StringBuilder(text.length());
            int copied = 0;
            int skipUntil = 0; // end of an old block that is removed or kept as it is
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                Key key = line.key();
                String newName = key == null ? null : leafRenames.get(key.name());
                if (newName == null || line.start() < skipUntil) {
                    continue;
                }
                if (names.get(key.owner()).add(newName)) {
                    result.append(text, copied, key.start())
                            .append(source.token(newName, text.substring(key.start(), key.end())));
                    copied = key.end();
                    holders.computeIfAbsent(key.owner(), owner -> new HashMap<>()).put(newName, line.path());
                    renamed.add(key.name() + " -> " + newName);
                    continue;
                }
                String name = line.path() != null ? line.path() : key.name();
                String holder = holders.getOrDefault(key.owner(), Map.of()).get(newName);
                if (holder == null && key.parent() != null) {
                    holder = key.parent().isEmpty() ? newName : key.parent() + "." + newName;
                }
                skipUntil = lines.get(YamlLines.blockEnd(lines, i, key.column())).end();
                if (line.kind() == Kind.KEY && unchanged(line.path(), holder, legacyPath(line.path()))) {
                    int[] block = blockRange(text, lines, i);
                    result.append(text, copied, block[0]);
                    copied = block[1];
                    removed.add(name);
                    removedPaths.add(line.path());
                } else {
                    keep(name, "Not renaming '" + name + "' to '" + newName + "' in " + source.label() + ": '" + newName
                            + "' already exists in the same section and the old entry was changed; keep one of them"
                            + " and delete the other (reported once)");
                    if (line.path() != null) {
                        keptPaths.add(line.path());
                    }
                }
            }
            result.append(text, copied, text.length());
            return finish(result.toString(), this::newPathOf, leafRenames, "legacy key(s)");
        }

        // The path in the old version's defaults, which use the old names.
        private String legacyPath(String path) {
            if (path == null) {
                return null;
            }
            String[] parts = path.split("\\.");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = newToOld.getOrDefault(parts[i], parts[i]);
            }
            return String.join(".", parts);
        }

        private String newPathOf(String path) {
            String[] parts = path.split("\\.");
            StringBuilder oldPrefix = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                oldPrefix.append(i == 0 ? "" : ".").append(parts[i]);
                String prefix = oldPrefix.toString();
                if (removedPaths.contains(prefix)) {
                    return null;
                }
                if (keptPaths.contains(prefix)) {
                    break;
                }
                parts[i] = leafRenames.getOrDefault(parts[i], parts[i]);
            }
            return String.join(".", parts);
        }
    }

    // Renames or moves exact paths, one after another in the order the old keys
    // appear in the file.
    private static final class PathRename extends Rename {
        private final Map<String, String> renames;
        private final Map<String, String> applied = new LinkedHashMap<>();
        private final Map<String, String> holders = new HashMap<>();

        PathRename(Source source, InputStream legacyDefaults, Map<String, String> renames) {
            super(source, legacyDefaults);
            this.renames = renames;
        }

        @Override
        boolean run() {
            String text = source.text();
            List<Line> original = source.parse(text).lines();
            Map<String, Integer> lineOf = new HashMap<>();
            renames.keySet().forEach(oldPath -> lineOf.put(oldPath, YamlLines.indexOfPath(original, oldPath)));
            List<String> todo = renames.keySet().stream().filter(oldPath -> lineOf.get(oldPath) >= 0)
                    .sorted(Comparator.comparingInt(lineOf::get)).toList();
            for (String oldPath : todo) {
                String next = renameOne(text, oldPath, renames.get(oldPath));
                if (next != null) {
                    text = next;
                }
            }
            return finish(text, this::newPathOf, Map.of(), "key(s)");
        }

        // The text with one rename applied, or null if it stays as it is.
        private String renameOne(String text, String oldPath, String newPath) {
            Parsed parsed = source.parse(text);
            List<Line> lines = parsed.lines();
            int index = YamlLines.indexOfPath(lines, oldPath);
            Line line = lines.get(index);
            Key key = line.key();
            String newParent = YamlLines.parentOf(newPath);
            String newName = newPath.substring(newPath.lastIndexOf('.') + 1);

            String holder = holders.get(newPath);
            if (holder == null && (YamlLines.indexOfPath(lines, newPath) >= 0 || source.yaml().contains(newPath))) {
                holder = newPath;
            }
            if (holder != null) {
                if (unchanged(oldPath, holder, oldPath)) {
                    int[] block = blockRange(text, lines, index);
                    removed.add(oldPath);
                    removedPaths.add(oldPath);
                    return text.substring(0, block[0]) + text.substring(block[1]);
                }
                keep(oldPath, "Not renaming '" + oldPath + "' to '" + newPath + "' in " + source.label() + ": '"
                        + newPath + "' already exists and the old entry was changed; keep one of them and delete the"
                        + " other (reported once)");
                return null;
            }
            String token = source.token(newName, text.substring(key.start(), key.end()));
            if (newParent.equals(key.parent())) {
                done(oldPath, newPath);
                return text.substring(0, key.start()) + token + text.substring(key.end());
            }

            // The deepest existing section on the new path takes the moved block.
            int section = -1;
            String sectionPath = "";
            for (String path = newParent; !path.isEmpty(); path = YamlLines.parentOf(path)) {
                section = YamlLines.indexOfPath(lines, path);
                if (section >= 0 || source.yaml().contains(path)) {
                    sectionPath = path;
                    break;
                }
            }
            if (!sectionPath.isEmpty() && (section < 0 || !YamlLines.valueOf(lines.get(section).content()).isEmpty())) {
                keep(oldPath, "Not moving '" + oldPath + "' to '" + newPath + "' in " + source.label() + ": '"
                        + sectionPath + "' isn't a section written one key per line; move the entry by hand"
                        + " (reported once)");
                return null;
            }
            String eol = YamlLines.lineEndingOf(text);
            int step = parsed.indentStep() > 0 ? parsed.indentStep() : 2;
            int indent = 0;
            int offset;
            int from = YamlLines.commentsStart(lines, index);
            int[] block = {from == 0 ? YamlLines.bomLength(text) : lines.get(from).start(),
                    lines.get(YamlLines.blockEnd(lines, index)).end()};
            if (section >= 0) {
                int end = YamlLines.blockEnd(lines, section);
                indent = lines.get(section).indent() + step;
                for (int i = section + 1; i <= end; i++) {
                    if (lines.get(i).kind() == Kind.KEY) {
                        indent = lines.get(i).indent();
                        break;
                    }
                }
                offset = lines.get(end).end();
            } else {
                int top = index;
                while (!lines.get(top).key().parent().isEmpty()) {
                    top--;
                    while (lines.get(top).kind() != Kind.KEY || lines.get(top).path() == null) {
                        top--;
                    }
                }
                offset = top == index ? block[0] : lines.get(YamlLines.blockEnd(lines, top)).end();
            }

            StringBuilder insertion = new StringBuilder();
            String missing = sectionPath.isEmpty()
                    ? newParent
                    : newParent.substring(Math.min(newParent.length(),
                            sectionPath.length() + 1));
            int depth = 0;
            for (String segment : missing.isEmpty() ? new String[0] : missing.split("\\.")) {
                insertion.append(" ".repeat(indent + depth++ * step)).append(source.token(segment, ""))
                        .append(':').append(eol);
            }
            int shift = indent + depth * step - line.indent();
            for (int i = from; i <= YamlLines.blockEnd(lines, index); i++) {
                Line moved = lines.get(i);
                int rawStart = i == 0 ? YamlLines.bomLength(text) : moved.start();
                String raw = text.substring(rawStart, moved.end()).replaceFirst("\r?\n$", "");
                if (i == index) {
                    raw = raw.substring(0, key.start() - rawStart) + token + raw.substring(key.end() - rawStart);
                }
                int spaces = raw.length() - raw.stripLeading().length();
                if (spaces > 0 || !raw.isEmpty()) {
                    raw = " ".repeat(Math.max(0, spaces + shift)) + raw.substring(spaces);
                }
                insertion.append(raw).append(eol);
            }
            done(oldPath, newPath);

            StringBuilder result = new StringBuilder(text.length() + insertion.length());
            if (offset <= block[0]) {
                result.append(text, 0, offset);
                appendInsertion(result, insertion, eol);
                result.append(text, offset, block[0]).append(text, block[1], text.length());
            } else {
                result.append(text, 0, block[0]).append(text, block[1], offset);
                appendInsertion(result, insertion, eol);
                result.append(text, offset, text.length());
            }
            return result.toString();
        }

        private static void appendInsertion(StringBuilder result, CharSequence insertion, String eol) {
            int length = result.length();
            if (length > 0 && result.charAt(length - 1) != '\n' && YamlLines.bomLength(result.toString()) != length) {
                result.append(eol);
            }
            result.append(insertion);
        }

        private void done(String oldPath, String newPath) {
            applied.put(oldPath, newPath);
            holders.put(newPath, oldPath);
            renamed.add(oldPath + " -> " + newPath);
        }

        private String newPathOf(String path) {
            for (String gone : removedPaths) {
                if (overlap(path, gone) && !inside(gone, path)) {
                    return null;
                }
            }
            for (Map.Entry<String, String> rename : applied.entrySet()) {
                if (path.equals(rename.getKey()) || inside(path, rename.getKey())) {
                    return rename.getValue() + path.substring(rename.getKey().length());
                }
            }
            return path;
        }
    }

    // Every leaf value of the file is read back at its expected path (null: its
    // block was removed), and no other value appears.
    static boolean keepsValues(ConfigurationSection before, ConfigurationSection after,
            Function<String, String> newPathOf, Map<String, String> listKeyRenames) {
        int expected = 0;
        for (String path : before.getKeys(true)) {
            Object value = before.get(path);
            String newPath = value instanceof ConfigurationSection ? null : newPathOf.apply(path);
            if (newPath == null) {
                continue;
            }
            expected++;
            if (!sameData(value, after.get(newPath), listKeyRenames)) {
                return false;
            }
        }
        int actual = 0;
        for (String path : after.getKeys(true)) {
            if (!(after.get(path) instanceof ConfigurationSection)) {
                actual++;
            }
        }
        return actual == expected;
    }

    // Maps inside lists may have renamed keys; everything else must be equal.
    private static boolean sameData(Object a, Object b, Map<String, String> keyRenames) {
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!sameData(x.get(i), y.get(i), keyRenames)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            Iterator<? extends Map.Entry<?, ?>> other = y.entrySet().iterator();
            for (Map.Entry<?, ?> entry : x.entrySet()) {
                Map.Entry<?, ?> next = other.next();
                String oldKey = String.valueOf(entry.getKey());
                String newKey = String.valueOf(next.getKey());
                if (!newKey.equals(oldKey) && !newKey.equals(keyRenames.get(oldKey))) {
                    return false;
                }
                if (!sameData(entry.getValue(), next.getValue(), keyRenames)) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
