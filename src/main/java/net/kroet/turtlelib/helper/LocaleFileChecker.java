package net.kroet.turtlelib.helper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Checks a plugin's bundled lang files against its base locale and the keys its
 * code uses. Meant for a unit test: {@link #check()} returns readable findings
 * for the test to assert on.
 */
public final class LocaleFileChecker {

    /**
     * Lang keys TurtleLib itself reads from a plugin's lang files; always counted
     * as used.
     */
    public static final Set<String> LIBRARY_KEYS = Set.of(MessageService.PREFIX_KEY);
    /**
     * Placeholders {@link MessageService} resolves in every message; always
     * allowed.
     */
    public static final Set<String> LIBRARY_PLACEHOLDERS = Set.of(MessageService.PREFIX_PLACEHOLDER,
            MessageService.PREFIX_LABEL_PLACEHOLDER);

    // Anything shaped like a MiniMessage tag, including misspelled names such as
    // <dark-red>, hex colors (<#FF8800>) and negations (<!italic>).
    private static final Pattern TAG_PATTERN = Pattern.compile("<(/?[!#]?[A-Za-z0-9_.-]+)(?::[^>]*)?>");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern RESET_TAG = Pattern.compile("<reset>", Pattern.CASE_INSENSITIVE);
    // LangLoader's rule for a locale file name; it never loads any other file.
    private static final Pattern LOCALE_NAME = Pattern.compile("[a-z]{2}_[A-Z]{2}");
    private static final String YML = ".yml";

    /**
     * Lang keys whose value may equal the base locale without any allowlist entry.
     */
    public static final Set<String> DEFAULT_IDENTICAL_ALLOWED = Set.of(MessageService.PREFIX_KEY);

    /**
     * What a finding reports; every finding is a problem that should fail the test.
     */
    public enum Kind {
        INVALID_FILE, MISSING_KEY, UNKNOWN_KEY, TAG_MISMATCH, UNKNOWN_TAG, INVALID_MINIMESSAGE, KEY_NOT_IN_BASE, UNUSED_KEY, UNTRANSLATED
    }

    /**
     * One finding: the file it concerns, the lang key (empty for a whole file) and
     * what is wrong.
     */
    public record Finding(Kind kind, String file, String key, String detail) {

        @Override
        public String toString() {
            return key.isEmpty() ? file + ": " + detail : file + ": '" + key + "' " + detail;
        }
    }

    /** All findings of one {@link #check()} run, in a stable order. */
    public record Result(List<Finding> findings) {

        public Result {
            findings = List.copyOf(findings);
        }

        public List<Finding> ofKind(Kind kind) {
            return findings.stream().filter(f -> f.kind() == kind).toList();
        }

        public boolean hasProblems() {
            return !findings.isEmpty();
        }

        /** The findings, one per line, for an assertion message. */
        public String describeProblems() {
            return findings.stream().map(Finding::toString).collect(Collectors.joining("\n"));
        }
    }

    private final Path langDir;
    private final String configuredBase; // null: chosen like LangLoader does
    private final Set<String> placeholders;
    private final Map<String, String> usedKeys;
    private final List<Path> sourceRoots;
    private final Predicate<String> dynamicKey;
    private final Predicate<String> ignoredLiteral;
    private final Map<String, Set<String>> oldKeysByNew;
    private final Predicate<String> identicalAllowed;
    private final boolean checkUsage;

    private LocaleFileChecker(Builder builder) {
        this.langDir = builder.langDir;
        this.configuredBase = builder.baseLocale;
        this.placeholders = Set.copyOf(builder.placeholders);
        this.usedKeys = new LinkedHashMap<>(builder.usedKeys);
        this.sourceRoots = List.copyOf(builder.sourceRoots);
        this.dynamicKey = globMatcher(builder.dynamicKeys);
        Map<String, String> renames = Map.copyOf(builder.renames);
        this.ignoredLiteral = globMatcher(builder.ignoredLiterals).or(renames::containsKey);
        this.oldKeysByNew = new LinkedHashMap<>();
        renames.forEach((oldKey, newKey) -> oldKeysByNew.computeIfAbsent(newKey, key -> new TreeSet<>()).add(oldKey));
        this.identicalAllowed = globMatcher(builder.identicalAllowed);
        this.checkUsage = builder.checkUsage;
    }

    /**
     * Starts a checker for the {@code *.yml} lang files in {@code langDir}, e.g.
     * {@code src/main/resources/lang}.
     */
    public static Builder builder(Path langDir) {
        return new Builder(langDir);
    }

    /**
     * Collects string literals under {@code sourceRoot} that start with one of
     * {@code sections} plus a dot, mapped to the first {@code .java} file (relative
     * to the root) that contains them.
     */
    public static Map<String, String> collectKeyLiterals(Path sourceRoot, Collection<String> sections) {
        return collectKeyLiterals(sourceRoot, sections, Set.of(), Map.of());
    }

    // Top-level keys count as literals of their own. A new key from the rename map
    // doesn't count on a line that also names its old key, e.g.
    // Map.entry("log.old-name", "log.old_name").
    private static Map<String, String> collectKeyLiterals(Path sourceRoot, Collection<String> sections,
            Collection<String> topLevelKeys, Map<String, Set<String>> oldKeysByNew) {
        Map<String, String> found = new TreeMap<>();
        List<String> forms = new ArrayList<>();
        if (!sections.isEmpty()) {
            forms.add("(?:" + alternatives(sections) + ")\\.[A-Za-z0-9_.-]*");
        }
        if (!topLevelKeys.isEmpty()) {
            forms.add(alternatives(topLevelKeys));
        }
        if (forms.isEmpty()) {
            return found;
        }
        Pattern literal = Pattern.compile("\"(" + String.join("|", forms) + ")\"");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String origin = sourceRoot.relativize(file).toString().replace('\\', '/');
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Matcher matcher = literal.matcher(line);
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        boolean renameEntry = oldKeysByNew.getOrDefault(key, Set.of()).stream()
                                .anyMatch(oldKey -> line.contains("\"" + oldKey + "\""));
                        if (!renameEntry) {
                            found.putIfAbsent(key, origin);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + sourceRoot, e);
        }
        return found;
    }

    private static String alternatives(Collection<String> names) {
        return names.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    }

    /**
     * Runs every check and returns its findings; never throws for broken or missing
     * files.
     */
    public Result check() {
        List<Finding> findings = new ArrayList<>();
        String baseLocale = configuredBase != null ? configuredBase : defaultBaseLocale();
        String baseFile = baseLocale + YML;
        Map<String, Map<String, String>> locales = loadLocales(findings);
        Map<String, String> base = locales.get(baseLocale);
        if (base == null || base.isEmpty()) {
            if (base != null) {
                findings.add(new Finding(Kind.INVALID_FILE, baseFile, "", "base locale has no keys"));
            } else if (findings.stream().noneMatch(f -> f.file().equals(baseFile))) {
                findings.add(new Finding(Kind.INVALID_FILE, baseFile, "", "base locale not found in " + langDir));
            }
            // Without a base nothing can be compared or checked for usage; the other
            // locales' tags still can.
            checkTags(locales, findings);
            return new Result(findings);
        }
        for (Map.Entry<String, Map<String, String>> locale : locales.entrySet()) {
            if (!locale.getKey().equals(baseLocale)) {
                compareWithBase(locale.getKey() + YML, locale.getValue(), base, baseFile, findings);
            }
        }
        checkTags(locales, findings);
        if (checkUsage) {
            checkUsage(base, baseFile, findings);
        }
        return new Result(findings);
    }

    // Like LangLoader: en_US if the folder has it, else the alphabetically first
    // file named like a locale.
    private String defaultBaseLocale() {
        try (Stream<Path> list = Files.list(langDir)) {
            List<String> locales = list.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(YML))
                    .map(name -> name.substring(0, name.length() - YML.length()))
                    .filter(name -> LOCALE_NAME.matcher(name).matches())
                    .sorted()
                    .toList();
            return locales.isEmpty() || locales.contains(LangLoader.DEFAULT_LOCALE)
                    ? LangLoader.DEFAULT_LOCALE
                    : locales.get(0);
        } catch (IOException e) {
            // loadLocales reports the folder.
            return LangLoader.DEFAULT_LOCALE;
        }
    }

    private Map<String, Map<String, String>> loadLocales(List<Finding> findings) {
        Map<String, Map<String, String>> locales = new TreeMap<>();
        if (!Files.isDirectory(langDir)) {
            findings.add(new Finding(Kind.INVALID_FILE, langDir.toString(), "", "lang directory not found"));
            return locales;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(langDir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(YML)).sorted().toList();
        } catch (IOException e) {
            findings.add(new Finding(Kind.INVALID_FILE, langDir.toString(), "", "cannot be listed: " + e));
            return locales;
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!LOCALE_NAME.matcher(name.substring(0, name.length() - YML.length())).matches()) {
                findings.add(new Finding(Kind.INVALID_FILE, name, "",
                        "isn't named like a locale (xx_YY), so LangLoader never loads it"));
                continue;
            }
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                Map<String, String> values = new LinkedHashMap<>();
                flatten(yaml, "", values);
                locales.put(name.substring(0, name.length() - YML.length()), values);
            } catch (IOException | InvalidConfigurationException e) {
                String message = Objects.requireNonNullElse(e.getMessage(), e.toString()).lines().findFirst().orElse("");
                findings.add(new Finding(Kind.INVALID_FILE, name, "", "cannot be read as UTF-8 YAML: " + message));
            }
        }
        return locales;
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, String> out) {
        for (String key : section.getKeys(false)) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                flatten(section.getConfigurationSection(key), fullKey, out);
            } else {
                out.put(fullKey, section.getString(key, ""));
            }
        }
    }

    private void compareWithBase(String file, Map<String, String> localized, Map<String, String> base,
            String baseFile, List<Finding> findings) {
        for (Map.Entry<String, String> entry : base.entrySet()) {
            String value = localized.get(entry.getKey());
            if (value == null) {
                findings.add(new Finding(Kind.MISSING_KEY, file, entry.getKey(), "is missing (present in " + baseFile + ")"));
                continue;
            }
            Set<String> baseTags = comparedTagsOf(entry.getValue());
            Set<String> localizedTags = comparedTagsOf(value);
            if (!baseTags.equals(localizedTags)) {
                findings.add(new Finding(Kind.TAG_MISMATCH, file, entry.getKey(),
                        "has tags " + localizedTags + " but " + baseFile + " has " + baseTags));
            }
            if (value.equals(entry.getValue()) && !identicalAllowed.test(entry.getKey())) {
                findings.add(new Finding(Kind.UNTRANSLATED, file, entry.getKey(),
                        "is identical to " + baseFile + " (untranslated, or add it to identicalAllowed)"));
            }
        }
        for (String key : localized.keySet()) {
            if (!base.containsKey(key)) {
                findings.add(new Finding(Kind.UNKNOWN_KEY, file, key, "is not present in " + baseFile));
            }
        }
    }

    private void checkTags(Map<String, Map<String, String>> locales, List<Finding> findings) {
        TagResolver standard = TagResolver.standard();
        MiniMessage strict = MiniMessage.builder().strict(true).build();
        // Dummy values: the check is about tag structure, not the text a placeholder
        // inserts.
        TagResolver dummies = TagResolver.resolver(placeholders.stream().map(p -> Placeholder.unparsed(p, p)).toList());
        for (Map.Entry<String, Map<String, String>> locale : locales.entrySet()) {
            String file = locale.getKey() + YML;
            for (Map.Entry<String, String> entry : locale.getValue().entrySet()) {
                boolean unknownTag = false;
                for (String tag : tagsOf(entry.getValue())) {
                    String name = tag.startsWith("!") ? tag.substring(1) : tag;
                    if (!HEX_COLOR.matcher(name).matches() && !standard.has(name) && !placeholders.contains(name)) {
                        findings.add(new Finding(Kind.UNKNOWN_TAG, file, entry.getKey(), "uses unknown tag <" + tag + ">"));
                        unknownTag = true;
                    }
                }
                if (unknownTag) {
                    continue;
                }
                try {
                    strict.deserialize(afterLastReset(entry.getValue()), dummies);
                } catch (ParsingException e) {
                    String detail = Objects.requireNonNullElse(e.detailMessage(), e.getMessage());
                    findings.add(new Finding(Kind.INVALID_MINIMESSAGE, file, entry.getKey(), "is not valid MiniMessage: " + detail));
                }
            }
        }
    }

    // Strict mode rejects <reset>, which closes every open tag; the text after the
    // last one starts afresh and gets the strict check.
    private static String afterLastReset(String value) {
        Matcher matcher = RESET_TAG.matcher(value);
        int end = 0;
        while (matcher.find()) {
            end = matcher.end();
        }
        return value.substring(end);
    }

    private void checkUsage(Map<String, String> base, String baseFile, List<Finding> findings) {
        Map<String, String> used = new TreeMap<>(usedKeys);
        Set<String> sections = base.keySet().stream().map(k -> k.split("\\.", 2)[0]).collect(Collectors.toCollection(TreeSet::new));
        Set<String> topLevelKeys = base.keySet().stream().filter(k -> !k.contains(".")).collect(Collectors.toCollection(TreeSet::new));
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                findings.add(new Finding(Kind.INVALID_FILE, root.toString(), "", "source directory not found"));
                continue;
            }
            try {
                collectKeyLiterals(root, sections, topLevelKeys, oldKeysByNew).forEach(used::putIfAbsent);
            } catch (UncheckedIOException e) {
                findings.add(new Finding(Kind.INVALID_FILE, root.toString(), "", "cannot be scanned: " + e.getCause()));
            }
        }
        LIBRARY_KEYS.forEach(key -> used.putIfAbsent(key, "TurtleLib"));

        // A literal that ends mid-key or names a whole section is the start of a
        // composed key.
        List<String> prefixes = new ArrayList<>();
        for (Map.Entry<String, String> entry : used.entrySet()) {
            String key = entry.getKey();
            boolean prefix = key.endsWith(".") || key.endsWith("_") || key.endsWith("-")
                    || base.keySet().stream().anyMatch(k -> k.startsWith(key + "."));
            if (prefix) {
                prefixes.add(key.endsWith(".") || key.endsWith("_") || key.endsWith("-") ? key : key + ".");
            } else if (!base.containsKey(key) && !ignoredLiteral.test(key)) {
                findings.add(new Finding(Kind.KEY_NOT_IN_BASE, baseFile, key, "is used in " + entry.getValue() + " but missing"));
            }
        }
        for (String key : base.keySet()) {
            boolean isUsed = used.containsKey(key) || dynamicKey.test(key) || prefixes.stream().anyMatch(key::startsWith);
            if (!isUsed) {
                findings.add(new Finding(Kind.UNUSED_KEY, baseFile, key, "is not used in code"));
            }
        }
    }

    // Hex colors and negations may differ between locales, as before this checker
    // saw them at all. Tag names are compared in lower case, as MiniMessage reads
    // them.
    private static Set<String> comparedTagsOf(String value) {
        Set<String> tags = tagsOf(value);
        tags.removeIf(tag -> tag.startsWith("#") || tag.startsWith("!"));
        return tags;
    }

    private static Set<String> tagsOf(String value) {
        Set<String> tags = new TreeSet<>();
        Matcher matcher = TAG_PATTERN.matcher(value);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            tags.add(name.startsWith("/") ? name.substring(1) : name);
        }
        return tags;
    }

    private static Predicate<String> globMatcher(Collection<String> globs) {
        if (globs.isEmpty()) {
            return key -> false;
        }
        String regex = globs.stream()
                .map(glob -> Stream.of(glob.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*")))
                .collect(Collectors.joining("|"));
        return Pattern.compile(regex).asMatchPredicate();
    }

    /**
     * Configures a {@link LocaleFileChecker}; every collection setter adds to what
     * was set before.
     */
    public static final class Builder {

        private final Path langDir;
        private String baseLocale;
        private final Set<String> placeholders = new LinkedHashSet<>(LIBRARY_PLACEHOLDERS);
        private final Map<String, String> usedKeys = new LinkedHashMap<>();
        private final List<Path> sourceRoots = new ArrayList<>();
        private final List<String> dynamicKeys = new ArrayList<>();
        private final List<String> ignoredLiterals = new ArrayList<>();
        private final Map<String, String> renames = new LinkedHashMap<>();
        private final List<String> identicalAllowed = new ArrayList<>(DEFAULT_IDENTICAL_ALLOWED);
        private boolean checkUsage;

        private Builder(Path langDir) {
            this.langDir = Objects.requireNonNull(langDir, "langDir");
        }

        /**
         * The locale all others are compared with. By default the one LangLoader falls
         * back to: {@code en_US} if the folder has it, else the alphabetically first
         * locale file.
         */
        public Builder baseLocale(String baseLocale) {
            this.baseLocale = Objects.requireNonNull(baseLocale, "baseLocale");
            return this;
        }

        /**
         * Placeholder tags the plugin resolves; {@link #LIBRARY_PLACEHOLDERS} are
         * always included.
         */
        public Builder placeholders(Collection<String> names) {
            placeholders.addAll(names);
            return this;
        }

        public Builder placeholders(String... names) {
            return placeholders(List.of(names));
        }

        /** Lang keys the code uses; enables the check of used against defined keys. */
        public Builder usedKeys(Collection<String> keys) {
            keys.forEach(key -> usedKeys.putIfAbsent(key, "code"));
            checkUsage = true;
            return this;
        }

        /**
         * Collects used keys from string literals in {@code .java} files; enables the
         * usage check.
         */
        public Builder scanSources(Path sourceRoot) {
            sourceRoots.add(Objects.requireNonNull(sourceRoot, "sourceRoot"));
            checkUsage = true;
            return this;
        }

        /**
         * Base keys the code builds at runtime, counted as used; {@code *} matches any
         * text.
         */
        public Builder dynamicKeys(Collection<String> globs) {
            dynamicKeys.addAll(globs);
            return this;
        }

        public Builder dynamicKeys(String... globs) {
            return dynamicKeys(List.of(globs));
        }

        /**
         * Literals that look like lang keys but are not, e.g. old keys in a rename map;
         * {@code *} matches any text.
         */
        public Builder ignoreLiterals(Collection<String> globs) {
            ignoredLiterals.addAll(globs);
            return this;
        }

        public Builder ignoreLiterals(String... globs) {
            return ignoreLiterals(List.of(globs));
        }

        /**
         * The code's key renames (old to new): old keys are ignored literals, and a new
         * key named only next to its old key, as in the map itself, isn't used.
         */
        public Builder renames(Map<String, String> oldToNew) {
            renames.putAll(oldToNew);
            return this;
        }

        /**
         * Keys whose value may equal the base locale, e.g. shared terms; {@code *}
         * matches any text.
         */
        public Builder identicalAllowed(Collection<String> globs) {
            identicalAllowed.addAll(globs);
            return this;
        }

        public Builder identicalAllowed(String... globs) {
            return identicalAllowed(List.of(globs));
        }

        public LocaleFileChecker build() {
            return new LocaleFileChecker(this);
        }
    }
}
