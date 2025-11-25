package com.github.hrobasti.turtlelib.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Prototype helper that prints a MiniMessage-colored startup banner to the console.
 * Plugin authors can either add lines programmatically or provide a resource file
 * inside their plugin JAR (e.g. {@code resources/banner.txt}).
 */
public final class StartupBanner {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final List<String> lines;
    private final String colorTag;

    private StartupBanner(JavaPlugin plugin, List<String> lines, String colorTag) {
        this.plugin = plugin;
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        this.colorTag = (colorTag == null || colorTag.isBlank()) ? "<white>" : colorTag.trim();
    }

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    /**
     * Sends the configured banner to the console. Lines are MiniMessage-escaped automatically.
     */
    public void send() {
        if (lines.isEmpty()) {
            return;
        }
        var console = plugin.getServer().getConsoleSender();
        if (console == null) {
            return;
        }
        for (String rawLine : lines) {
            String escaped = MINI.escapeTags(rawLine == null ? "" : rawLine);
            console.sendMessage(MINI.deserialize(colorTag + escaped + "<reset>"));
        }
    }

    public static final class Builder {
        private final JavaPlugin plugin;
        private final List<String> manualLines = new ArrayList<>();
        private String resourcePath;
        private String colorTag = "<light_purple>";

        private Builder(JavaPlugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
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
                manualLines.addAll(lines);
            }
            return this;
        }

        /**
         * Optional resource path (relative to the plugin JAR) that contains the banner text.
         */
        public Builder resource(String relativePath) {
            this.resourcePath = relativePath;
            return this;
        }

        /**
         * Sets the MiniMessage color/prefix tag, e.g. {@code <gold>} or {@code <gradient:#ff7e5f:#feb47b>}.
         */
        public Builder color(String miniMessageColorTag) {
            if (miniMessageColorTag != null && !miniMessageColorTag.isBlank()) {
                this.colorTag = miniMessageColorTag.trim();
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
                    plugin.getLogger().fine("StartupBanner resource not found: " + resourcePath);
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
                plugin.getLogger().fine("Failed to read banner resource: " + ex.getMessage());
                return Collections.emptyList();
            }
        }
    }
}

