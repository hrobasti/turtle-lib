package net.kroet.turtlelib.helper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.YamlLines.Kind;
import net.kroet.turtlelib.helper.YamlLines.Line;
import net.kroet.turtlelib.helper.YamlLines.Parsed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Adds keys the bundled default file has but the server's copy lacks, as raw
 * text with the comments above them; unlike a {@code YamlConfiguration#save}
 * round-trip, every existing byte stays as it was.
 */
public final class ConfigDefaultsInserter {
    static final Charset ANSI = Charset.forName("windows-1252");
    static final String SEEN_FILE = ".seen-defaults.yml";

    private ConfigDefaultsInserter() {
    }

    /**
     * Inserts every key of {@code defaults} that {@code file} lacks and returns
     * their dotted paths (empty if none). A missing section comes whole; keys under
     * a plain value or an inline section like {@code {key: value}} are skipped.
     */
    public static List<String> insertMissingKeys(File file, InputStream defaults, Logger logger, String fileLabel) {
        return insertMissingKeys(file, defaults, logger, fileLabel, List.of());
    }

    /**
     * Like {@link #insertMissingKeys(File, InputStream, Logger, String)}, but
     * inside {@code openSections} each default key is offered only once (tracked in
     * {@value #SEEN_FILE}), so entries the admin deleted stay deleted.
     */
    public static List<String> insertMissingKeys(File file, InputStream defaults, Logger logger, String fileLabel,
            Collection<String> openSections) {
        return insertMissingKeys(file, defaults, logger, fileLabel, openSections, false);
    }

    // With skipUnencodable, a key whose block still has characters the file's
    // encoding can't store is skipped with a warning instead of written with '?'.
    // Any failure, even a stack overflow while parsing, leaves the file unchanged.
    static List<String> insertMissingKeys(File file, InputStream defaults, Logger logger, String fileLabel,
            Collection<String> openSections, boolean skipUnencodable) {
        try {
            return addMissingKeys(file, defaults, logger, fileLabel, openSections, skipUnencodable);
        } catch (RuntimeException | StackOverflowError e) {
            warn(logger, "Could not add new default keys to " + fileLabel + " (" + e + "); the file was left unchanged");
            return List.of();
        }
    }

    private static List<String> addMissingKeys(File file, InputStream defaults, Logger logger, String fileLabel,
            Collection<String> openSections, boolean skipUnencodable) {
        if (file == null || !file.isFile() || defaults == null) {
            return List.of();
        }
        byte[] bytes;
        String defaultsText;
        try {
            bytes = Files.readAllBytes(file.toPath());
            defaultsText = new String(defaults.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn(logger, "Could not read " + fileLabel + " to add new default keys: " + e.getMessage());
            return List.of();
        }

        // Inserted text is encoded like the file (UTF-8, or ANSI if it isn't valid
        // UTF-8); ISO-8859-1 then splices raw bytes, leaving the rest untouched.
        Charset charset = YamlLines.isValidUtf8(bytes) ? StandardCharsets.UTF_8 : ANSI;
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        String decoded = new String(bytes, charset);
        YamlConfiguration defaultConfig = YamlLines.parseYaml(defaultsText);
        if (defaultConfig == null) {
            warn(logger, "Skipped adding new default keys to " + fileLabel + ": the bundled default file isn't valid"
                    + " YAML (" + YamlLines.yamlError(defaultsText) + ")");
            return List.of();
        }
        YamlConfiguration current = YamlLines.parseYaml(decoded);
        if (current == null) {
            warn(logger, "Skipped adding new default keys to " + fileLabel + ": it isn't valid YAML ("
                    + YamlLines.yamlError(decoded) + ")");
            return List.of();
        }

        Parsed defaultsParsed = YamlLines.parse(defaultsText);
        List<Line> defaultLines = defaultsParsed.lines();
        Target target = new Target(text, YamlLines.parse(text, charset), defaultLines);
        Set<String> openDefaults = pathsInside(defaultsParsed, openSections);
        SeenDefaults seen = openDefaults.isEmpty() ? null : SeenDefaults.load(file, logger);
        List<String> added = new ArrayList<>();
        Blocked inline = new Blocked();
        Blocked aliases = new Blocked();
        List<String> skipped = new ArrayList<>(); // not written now, offered again later
        List<String> unencodable = new ArrayList<>();
        Map<Integer, StringBuilder> insertions = new TreeMap<>();
        boolean replacedChars = false;
        for (int i = 0; i < defaultLines.size(); i++) {
            Line line = defaultLines.get(i);
            String path = line.path();
            if (line.kind() != Kind.KEY || path == null || current.contains(path) || target.has(path)
                    || insideAny(path, added)) {
                continue;
            }
            String parent = line.key().parent();
            if (!parent.isEmpty() && !current.isConfigurationSection(parent) && !target.isEmptyKey(parent)) {
                skipped.add(path); // under a plain value or a list
                continue;
            }
            if (openDefaults.contains(path) && sectionExists(path, openSections, current)
                    && seen.alreadyOffered(path)) {
                continue;
            }
            String section = target.unwritableSection(parent);
            if (section != null) {
                (target.isAlias(section) ? aliases : inline).add(path, section);
                continue;
            }
            String block = buildBlock(defaultsParsed, i, target, defaultConfig, charset);
            EncodedBlock encoded = encode(block, charset);
            if (encoded.replaced() && skipUnencodable) {
                unencodable.add(path);
                continue;
            }
            replacedChars |= encoded.replaced();
            target.insert(insertions, i, encoded.latin1());
            added.add(path);
        }
        inline.warn(logger, fileLabel, "written inline like {key: value}; write %s with one key per line");
        aliases.warn(logger, fileLabel, "an alias of another section (like *name); write %s out with one key per line");
        if (!unencodable.isEmpty()) {
            warn(logger, fileLabel + ": can't add " + String.join(", ", unencodable) + " because the file isn't"
                    + " saved as UTF-8 and these texts contain characters its encoding (Windows-1252) can't store;"
                    + " save the file as UTF-8 to get them");
        }
        // Skipped keys are offered again once they can be written.
        Set<String> offered = new LinkedHashSet<>(openDefaults);
        offered.removeAll(inline.keys);
        offered.removeAll(aliases.keys);
        offered.removeAll(unencodable);
        skipped.forEach(offered::remove);
        if (added.isEmpty()) {
            markSeen(seen, offered, logger);
            return List.of();
        }

        String result = target.splice(insertions);
        YamlConfiguration after = YamlLines.parseYaml(new String(result.getBytes(StandardCharsets.ISO_8859_1), charset));
        if (after == null || !keepsAndAdds(current, after, added, defaultConfig,
                YamlLines.parse(result, charset).lines())) {
            warn(logger, "Could not add new default keys to " + fileLabel + " safely; the file was left unchanged");
            return List.of();
        }
        if (replacedChars) {
            warn(logger, "Some characters of the new default keys can't be stored in " + fileLabel
                    + " (not saved as UTF-8) and were replaced with '?'");
        }
        try {
            AtomicFiles.write(file.toPath(), result.getBytes(StandardCharsets.ISO_8859_1), logger);
        } catch (IOException e) {
            warn(logger, "Failed to write new default keys to " + fileLabel + ": " + e.getMessage());
            return List.of();
        }
        markSeen(seen, offered, logger);
        return List.copyOf(added);
    }

    private static boolean insideAny(String path, List<String> sections) {
        for (String section : sections) {
            if (path.startsWith(section + ".")) {
                return true;
            }
        }
        return false;
    }

    // Every existing value reads back unchanged and every added key is there; a
    // default without a value only shows as its key line.
    private static boolean keepsAndAdds(YamlConfiguration before, YamlConfiguration after, List<String> added,
            YamlConfiguration defaults, List<Line> lines) {
        for (String path : before.getKeys(true)) {
            Object value = before.get(path);
            if (!(value instanceof ConfigurationSection) && !Objects.equals(value, after.get(path))) {
                return false;
            }
        }
        for (String path : added) {
            if (!after.contains(path) && (defaults.contains(path) || YamlLines.indexOfPath(lines, path) < 0)) {
                return false;
            }
        }
        return true;
    }

    // Keys that can't be added because of how their section is written, and those
    // sections.
    private static final class Blocked {
        final List<String> keys = new ArrayList<>();
        final Set<String> sections = new LinkedHashSet<>();

        void add(String key, String section) {
            keys.add(key);
            sections.add(section);
        }

        void warn(Logger logger, String fileLabel, String problem) {
            if (keys.isEmpty()) {
                return;
            }
            boolean one = sections.size() == 1;
            ConfigDefaultsInserter.warn(logger, fileLabel + ": can't add the new default "
                    + (keys.size() == 1 ? "key " : "keys ") + String.join(", ", keys) + " because "
                    + String.join(", ", sections) + (one ? " is " : " are ")
                    + String.format(problem, one ? "that section" : "those sections") + " to get them");
        }
    }

    // The admin's file as parsed once: where its keys are and where new blocks go.
    private static final class Target {
        final String text;
        final Parsed parsed;
        final String lineEnding;
        private final List<Line> defaults;
        private final Map<String, Integer> index = new HashMap<>();
        private final int[] previousPresent;
        private final int[] nextPresent;

        Target(String text, Parsed parsed, List<Line> defaults) {
            this.text = text;
            this.parsed = parsed;
            this.defaults = defaults;
            this.lineEnding = YamlLines.lineEndingOf(text);
            List<Line> lines = parsed.lines();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).path() != null) {
                    index.putIfAbsent(lines.get(i).path(), i);
                }
            }
            // Per default key, the nearest sibling before and after it that the file has.
            previousPresent = new int[defaults.size()];
            nextPresent = new int[defaults.size()];
            fillPresentSiblings(defaults, previousPresent, 0, defaults.size(), 1);
            fillPresentSiblings(defaults, nextPresent, defaults.size() - 1, -1, -1);
        }

        private void fillPresentSiblings(List<Line> defaults, int[] result, int from, int to, int step) {
            Map<String, Integer> lastPresent = new HashMap<>();
            for (int i = from; i != to; i += step) {
                Line line = defaults.get(i);
                result[i] = -1;
                if (line.kind() != Kind.KEY || line.path() == null) {
                    continue;
                }
                String parent = line.key().parent();
                result[i] = lastPresent.getOrDefault(parent, -1);
                Integer present = index.get(line.path());
                if (present != null) {
                    lastPresent.put(parent, present);
                }
            }
        }

        boolean has(String path) {
            return index.containsKey(path);
        }

        Line line(String path) {
            Integer found = index.get(path);
            if (found == null) {
                throw new IllegalStateException("No block-style line for '" + path + "'");
            }
            return parsed.lines().get(found);
        }

        // "key:" with nothing after it, which can take keys below it.
        boolean isEmptyKey(String path) {
            return has(path) && YamlLines.valueOf(line(path).content()).isEmpty();
        }

        boolean isAlias(String section) {
            return has(section) && YamlLines.valueOf(line(section).content()).startsWith("*");
        }

        // Nearest enclosing section, from the key's parent up, that the file doesn't
        // write one key per line (inline, or an alias), or null.
        String unwritableSection(String parent) {
            String hidden = null;
            for (String section = parent; !section.isEmpty(); section = YamlLines.parentOf(section)) {
                if (has(section)) {
                    return YamlLines.valueOf(line(section).content()).isEmpty() ? hidden : section;
                }
                hidden = section;
            }
            return hidden;
        }

        int indentStep(Parsed defaults) {
            return parsed.indentStep() > 0 ? parsed.indentStep() : defaults.indentStep();
        }

        int indentFor(int keyIndex, Parsed defaultsParsed) {
            int sibling = previousPresent[keyIndex] >= 0 ? previousPresent[keyIndex] : nextPresent[keyIndex];
            if (sibling >= 0) {
                return parsed.lines().get(sibling).indent();
            }
            String parent = defaults.get(keyIndex).key().parent();
            return parent.isEmpty() ? 0 : line(parent).indent() + Math.max(1, indentStep(defaultsParsed));
        }

        // Queues the block after its previous sibling, before its next one, or right
        // under its parent (at the end of the file for top-level keys).
        void insert(Map<Integer, StringBuilder> insertions, int keyIndex, String block) {
            List<Line> lines = parsed.lines();
            String blank = blankBefore(defaults, keyIndex) ? lineEnding : "";
            int offset;
            String insertion;
            if (previousPresent[keyIndex] >= 0) {
                offset = lines.get(YamlLines.blockEnd(lines, previousPresent[keyIndex])).end();
                insertion = blank + block;
            } else if (nextPresent[keyIndex] >= 0) {
                // The next key's leading blank line now sits above the new block.
                offset = lines.get(YamlLines.commentsStart(lines, nextPresent[keyIndex])).start();
                insertion = block + blank;
            } else {
                String parent = defaults.get(keyIndex).key().parent();
                offset = parent.isEmpty() ? text.length() : line(parent).end();
                insertion = blank + block;
            }
            insertions.computeIfAbsent(Math.max(offset, YamlLines.bomLength(text)), key -> new StringBuilder())
                    .append(insertion);
        }

        // A byte order mark stays the file's first bytes; text goes in after it.
        String splice(Map<Integer, StringBuilder> insertions) {
            int bom = YamlLines.bomLength(text);
            StringBuilder result = new StringBuilder(text.length() + 256);
            int copied = 0;
            for (Map.Entry<Integer, StringBuilder> insertion : insertions.entrySet()) {
                int offset = insertion.getKey();
                result.append(text, copied, offset);
                if (offset > bom && text.charAt(offset - 1) != '\n') {
                    result.append(lineEnding);
                }
                result.append(insertion.getValue());
                copied = offset;
            }
            return result.append(text, copied, text.length()).toString();
        }
    }

    // Default key paths strictly inside one of the open sections.
    private static Set<String> pathsInside(Parsed defaults, Collection<String> openSections) {
        Set<String> paths = new LinkedHashSet<>();
        for (Line line : defaults.lines()) {
            if (line.kind() == Kind.KEY && line.path() != null && openSectionOf(line.path(), openSections) != null) {
                paths.add(line.path());
            }
        }
        return paths;
    }

    private static String openSectionOf(String path, Collection<String> openSections) {
        for (String section : openSections) {
            if (path.startsWith(section + ".")) {
                return section;
            }
        }
        return null;
    }

    // A missing open section is still inserted as a whole.
    private static boolean sectionExists(String path, Collection<String> openSections, YamlConfiguration current) {
        return current.isConfigurationSection(openSectionOf(path, openSections));
    }

    private static void markSeen(SeenDefaults seen, Set<String> openDefaults, Logger logger) {
        if (seen != null) {
            seen.markAll(openDefaults, logger);
        }
    }

    /**
     * Per-folder record of the open-section default keys already offered for each
     * file. Without an entry for a file (new install, clean upgrade, first use),
     * every current default counts as offered, so nothing deleted comes back.
     */
    private static final class SeenDefaults {
        private final File stateFile;
        private final String fileName;
        private final YamlConfiguration state;
        private final Set<String> offered;
        private final boolean known;

        private SeenDefaults(File stateFile, String fileName, YamlConfiguration state) {
            this.stateFile = stateFile;
            this.fileName = fileName;
            this.state = state;
            this.known = state.isList(fileName);
            this.offered = new LinkedHashSet<>(state.getStringList(fileName));
        }

        static SeenDefaults load(File file, Logger logger) {
            File stateFile = new File(file.getAbsoluteFile().getParentFile(), SEEN_FILE);
            return new SeenDefaults(stateFile, file.getName(), loadState(stateFile, logger));
        }

        boolean alreadyOffered(String path) {
            return !known || offered.contains(path);
        }

        void markAll(Set<String> paths, Logger logger) {
            if (known && offered.containsAll(paths)) {
                return;
            }
            offered.addAll(paths);
            state.set(fileName, new ArrayList<>(offered));
            saveState(stateFile, state, logger);
        }
    }

    // The folder's state file (.seen-defaults.yml); a NUL path separator keeps
    // names like "config.yml" one top-level key.
    static YamlConfiguration loadState(File stateFile, Logger logger) {
        YamlConfiguration state = new YamlConfiguration();
        state.options().pathSeparator('\0');
        if (stateFile.isFile()) {
            try {
                state.loadFromString(Files.readString(stateFile.toPath(), StandardCharsets.UTF_8));
            } catch (IOException | InvalidConfigurationException e) {
                warn(logger, "Could not read " + SEEN_FILE + ": " + e.getMessage());
            }
        }
        return state;
    }

    static void saveState(File stateFile, YamlConfiguration state, Logger logger) {
        state.options().setHeader(List.of("Written by TurtleLib: per file, default entries already offered and old",
                "duplicate keys already reported. Entries listed here aren't added or reported again."));
        try {
            AtomicFiles.write(stateFile.toPath(), state.saveToString().getBytes(StandardCharsets.UTF_8), logger);
        } catch (IOException e) {
            warn(logger, "Could not write " + SEEN_FILE + ": " + e.getMessage());
        }
    }

    // The key's lines with the comments above it, indented like the admin's file;
    // the text of a multi-line value moves with its key and isn't re-indented.
    private static String buildBlock(Parsed defaults, int keyIndex, Target target, YamlConfiguration defaultConfig,
            Charset charset) {
        List<Line> lines = defaults.lines();
        Line key = lines.get(keyIndex);
        int from = YamlLines.commentsStart(lines, keyIndex);
        int to = YamlLines.blockEnd(lines, keyIndex);
        int targetIndent = target.indentFor(keyIndex, defaults);
        int defaultStep = defaults.indentStep();
        int targetStep = target.indentStep(defaults);

        StringBuilder block = new StringBuilder();
        int shift = 0; // how far the line that started the current value moved
        for (int i = from; i <= to; i++) {
            Line line = lines.get(i);
            if (line.kind() == Kind.BLANK) {
                block.append(target.lineEnding);
                continue;
            }
            int indent;
            if (line.kind() == Kind.VALUE) {
                indent = Math.max(0, line.indent() + shift);
            } else {
                int relative = Math.max(0, line.indent() - key.indent());
                if (defaultStep > 0 && relative % defaultStep == 0) {
                    relative = relative / defaultStep * targetStep;
                }
                indent = targetIndent + relative;
                shift = indent - line.indent();
            }
            String content = line.content();
            String escaped = escapedValueLine(lines, i, defaultConfig, charset);
            if (escaped != null) {
                content = escaped;
                i = YamlLines.blockEnd(lines, i); // the value's other lines are now part of it
            }
            block.append(" ".repeat(indent)).append(content).append(target.lineEnding);
        }
        return block.toString();
    }

    // A text value whose lines the encoding can't store becomes one double-quoted
    // YAML line with escape sequences, built from the parsed value; else null.
    private static String escapedValueLine(List<Line> lines, int index, YamlConfiguration defaults, Charset charset) {
        Line line = lines.get(index);
        String content = line.content();
        int colon = YamlLines.afterColon(content);
        if (line.kind() != Kind.KEY || line.path() == null || colon < 0 || !defaults.isString(line.path())
                || !charset.newEncoder().canEncode(content.substring(0, colon))) {
            return null;
        }
        int end = YamlLines.blockEnd(lines, index);
        if (lines.subList(index, end + 1).stream().allMatch(value -> charset.newEncoder().canEncode(value.content()))) {
            return null;
        }
        CharsetEncoder encoder = charset.newEncoder();
        StringBuilder quoted = new StringBuilder(content.substring(0, colon)).append(" \"");
        defaults.getString(line.path()).codePoints().forEach(codePoint -> {
            String character = Character.toString(codePoint);
            if (codePoint == '"' || codePoint == '\\') {
                quoted.append('\\').append(character);
            } else if (codePoint == '\n') {
                quoted.append("\\n");
            } else if (codePoint == '\t') {
                quoted.append("\\t");
            } else if (codePoint < 0x20 || codePoint == 0x7F || !encoder.canEncode(character)) {
                quoted.append(codePoint > 0xFFFF
                        ? String.format("\\U%08X", codePoint)
                        : String.format("\\u%04X", codePoint));
            } else {
                quoted.append(character);
            }
        });
        return quoted.append('"').toString();
    }

    private static boolean blankBefore(List<Line> lines, int keyIndex) {
        int start = YamlLines.commentsStart(lines, keyIndex);
        return start > 0 && lines.get(start - 1).kind() == Kind.BLANK;
    }

    private record EncodedBlock(String latin1, boolean replaced) {
    }

    private static EncodedBlock encode(String block, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        boolean replaced = !encoder.canEncode(block);
        encoder.onUnmappableCharacter(CodingErrorAction.REPLACE).onMalformedInput(CodingErrorAction.REPLACE);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(block));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return new EncodedBlock(new String(bytes, StandardCharsets.ISO_8859_1), replaced);
        } catch (CharacterCodingException e) {
            return new EncodedBlock(block, true); // unreachable with REPLACE
        }
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
