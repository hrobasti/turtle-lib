package net.kroet.turtlelib.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Prints a MiniMessage-colored startup banner to the console. Plugin authors
 * can either add lines programmatically or provide a resource file inside their
 * plugin JAR (e.g. {@code resources/banner.txt}).
 */
public final class StartupBanner {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String DEFAULT_COLOR = "<light_purple>";
    private static final Pattern OPENING_TAG = Pattern.compile("<(!?)([A-Za-z0-9_#-]+)(?::[^>]*)?>");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private final JavaPlugin plugin;
    private final List<String> lines;
    private final String colorTag;

    private StartupBanner(JavaPlugin plugin, List<String> lines, String colorTag) {
        this.plugin = plugin;
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        this.colorTag = (colorTag == null || colorTag.isBlank()) ? "<white>" : colorTag.trim();
    }

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(Objects.requireNonNull(plugin, "plugin"));
    }

    // Lines only, for tests; resource(), an invalid color() and send() need a
    // plugin.
    static Builder linesBuilder() {
        return new Builder(null);
    }

    List<String> lines() {
        return lines;
    }

    /**
     * Sends the configured banner to the console. Lines are MiniMessage-escaped
     * automatically.
     */
    public void send() {
        if (lines.isEmpty()) {
            return;
        }
        var console = plugin.getServer().getConsoleSender();
        if (console == null) {
            return;
        }
        for (String line : lines) {
            console.sendMessage(render(colorTag, line));
        }
    }

    /**
     * One banner line in the color, its text printed as it is: backslashes are
     * escaped too, as MiniMessage reads {@code \\} as one. Package-private for
     * StartupBannerTest.
     */
    static Component render(String colorTag, String line) {
        String escaped = MINI.escapeTags(line.replace("\\", "\\\\"));
        return MINI.deserialize(colorTag + escaped + "<reset>");
    }

    /**
     * Whether the text is only opening MiniMessage tags that exist, such as
     * {@code <gold>}, {@code <bold><aqua>} or {@code <gradient:#ff7e5f:#feb47b>}.
     */
    static boolean isColorTag(String text) {
        Matcher matcher = OPENING_TAG.matcher(text);
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end || !isKnownTag(matcher.group(2).toLowerCase(Locale.ROOT)))
                return false;
            end = matcher.end();
        }
        return end > 0 && end == text.length();
    }

    // Any standard tag; a hex color only with all six digits.
    private static boolean isKnownTag(String name) {
        return name.startsWith("#") ? HEX_COLOR.matcher(name).matches() : TagResolver.standard().has(name);
    }

    public static final class Builder {
        private final JavaPlugin plugin;
        private final List<String> manualLines = new ArrayList<>();
        private String resourcePath;
        private String colorTag = DEFAULT_COLOR;

        private Builder(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Adds a single banner line defined in code.
         */
        public Builder addLine(String line) {
            if (line != null) {
                manualLines.add(line);
            }
            return this;
        }

        /**
         * Replaces existing manual lines with the provided list.
         */
        public Builder lines(List<String> lines) {
            manualLines.clear();
            if (lines != null) {
                lines.stream().filter(Objects::nonNull).forEach(manualLines::add);
            }
            return this;
        }

        /**
         * Optional resource path (relative to the plugin JAR) that contains the banner
         * text.
         */
        public Builder resource(String relativePath) {
            this.resourcePath = relativePath;
            return this;
        }

        /**
         * Sets the MiniMessage color/prefix tag, e.g. {@code <gold>} or
         * {@code <gradient:#ff7e5f:#feb47b>}. Anything else logs a warning and switches
         * to the default {@code <light_purple>}, also after an earlier valid color.
         */
        public Builder color(String miniMessageColorTag) {
            if (miniMessageColorTag == null || miniMessageColorTag.isBlank()) {
                return this;
            }
            String tag = miniMessageColorTag.trim();
            if (isColorTag(tag)) {
                this.colorTag = tag;
            } else {
                plugin.getLogger().warning("StartupBanner: " + tag + " isn't a MiniMessage color tag; using "
                        + DEFAULT_COLOR);
                this.colorTag = DEFAULT_COLOR;
            }
            return this;
        }

        public StartupBanner build() {
            List<String> resolved = new ArrayList<>();
            resolved.addAll(loadResourceLines());
            resolved.addAll(manualLines);
            return new StartupBanner(plugin, resolved, colorTag);
        }

        private List<String> loadResourceLines() {
            if (resourcePath == null || resourcePath.isBlank()) {
                return Collections.emptyList();
            }
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in == null) {
                    plugin.getLogger().warning("StartupBanner resource not found: " + resourcePath);
                    return Collections.emptyList();
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    List<String> result = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.add(line);
                    }
                    return result;
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to read banner resource: " + ex.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
