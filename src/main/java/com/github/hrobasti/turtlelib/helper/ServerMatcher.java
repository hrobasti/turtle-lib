package com.github.hrobasti.turtlelib.helper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;

/**
 * Utility that verifies whether the currently running Paper/Minecraft version matches a developer-defined allow list.
 * It uses {@link VersionComparator} for range checks and can either warn or disable the plugin automatically.
 */
public final class ServerMatcher {

    public enum IncompatibleAction {
        WARN_AND_CONTINUE,
        ERROR_AND_DISABLE
    }

    private final JavaPlugin plugin;
    private final Set<String> exactVersions;
    private final List<VersionRange> ranges;
    private final IncompatibleAction action;
    private final Consumer<MatchResult> mismatchConsumer;
    private final String supportedSummary;

    private ServerMatcher(Builder builder) {
        this.plugin = builder.plugin;
        this.exactVersions = Collections.unmodifiableSet(builder.exactVersions);
        this.ranges = Collections.unmodifiableList(builder.ranges);
        this.action = builder.action;
        this.mismatchConsumer = builder.mismatchConsumer;
        this.supportedSummary = builder.describeRules();
    }

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    /**
     * Checks the current server without taking any automatic action.
     */
    public MatchResult check() {
        String serverName = plugin.getServer().getName();
        String mcVersion = Bukkit.getMinecraftVersion();
        boolean supported = isSupported(mcVersion);
        String reason = supported ? "" : buildReason(mcVersion, serverName);
        return new MatchResult(supported, serverName, mcVersion, supportedSummary, reason);
    }

    /**
     * Checks the server and performs the configured {@link IncompatibleAction} when unsupported.
     */
    public MatchResult enforce() {
        MatchResult result = check();
        if (result.supported()) {
            return result;
        }
        if (mismatchConsumer != null) {
            mismatchConsumer.accept(result);
        }
        String detail = "Detected " + result.serverName + " (MC " + result.minecraftVersion
            + "), supported set: " + supportedSummary + '.';
        if (action == IncompatibleAction.WARN_AND_CONTINUE) {
            plugin.getLogger().warning("[ServerMatcher] " + detail
                + " Proceeding, but behaviour is unsupported.");
        } else {
            plugin.getLogger().severe("[ServerMatcher] " + detail
                + " Disabling plugin due to incompatible server version.");
            PluginManager pm = Bukkit.getPluginManager();
            pm.disablePlugin(plugin);
        }
        return result;
    }

    private boolean isSupported(String candidateVersion) {
        if (candidateVersion == null || candidateVersion.isBlank()) {
            return false;
        }
        if (exactVersions.isEmpty() && ranges.isEmpty()) {
            return true; // no restrictions defined
        }
        String normalized = candidateVersion.trim();
        if (exactVersions.contains(normalized)) {
            return true;
        }
        for (VersionRange range : ranges) {
            if (range.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String buildReason(String mcVersion, String serverName) {
        return "Server " + serverName + " (MC " + mcVersion + ") is outside the supported set: "
            + supportedSummary;
    }

    public static final class MatchResult {
        private final boolean supported;
        private final String serverName;
        private final String minecraftVersion;
        private final String supportedDescription;
        private final String message;

        private MatchResult(boolean supported, String serverName, String minecraftVersion,
                            String supportedDescription, String message) {
            this.supported = supported;
            this.serverName = serverName;
            this.minecraftVersion = minecraftVersion;
            this.supportedDescription = supportedDescription;
            this.message = message;
        }

        public boolean supported() {
            return supported;
        }

        public String serverName() {
            return serverName;
        }

        public String minecraftVersion() {
            return minecraftVersion;
        }

        public String supportedDescription() {
            return supportedDescription;
        }

        public String message() {
            return message;
        }
    }

    public static final class Builder {
        private final JavaPlugin plugin;
        private final Set<String> exactVersions = new LinkedHashSet<>();
        private final List<VersionRange> ranges = new ArrayList<>();
        private IncompatibleAction action = IncompatibleAction.ERROR_AND_DISABLE;
        private Consumer<MatchResult> mismatchConsumer;

        private Builder(JavaPlugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        public Builder allowVersion(String version) {
            if (version != null && !version.isBlank()) {
                exactVersions.add(version.trim());
            }
            return this;
        }

        public Builder allowVersions(Collection<String> versions) {
            if (versions != null) {
                for (String v : versions) {
                    allowVersion(v);
                }
            }
            return this;
        }

        public Builder allowRange(String minInclusive, String maxInclusive) {
            if ((minInclusive == null || minInclusive.isBlank())
                && (maxInclusive == null || maxInclusive.isBlank())) {
                return this;
            }
            ranges.add(new VersionRange(nullIfBlank(minInclusive), nullIfBlank(maxInclusive)));
            return this;
        }

        public Builder incompatibleAction(IncompatibleAction action) {
            if (action != null) {
                this.action = action;
            }
            return this;
        }

        public Builder onMismatch(Consumer<MatchResult> consumer) {
            this.mismatchConsumer = consumer;
            return this;
        }

        public ServerMatcher build() {
            return new ServerMatcher(this);
        }

        private String describeRules() {
            if (exactVersions.isEmpty() && ranges.isEmpty()) {
                return "no constraints";
            }
            StringJoiner joiner = new StringJoiner(", ");
            for (VersionRange range : ranges) {
                joiner.add(range.describe());
            }
            for (String version : exactVersions) {
                joiner.add(version);
            }
            return joiner.toString();
        }

        private String nullIfBlank(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private static final class VersionRange {
        private final String min;
        private final String max;

        private VersionRange(String min, String max) {
            this.min = min;
            this.max = max;
        }

        private boolean contains(String candidate) {
            if (candidate == null) {
                return false;
            }
            if (min != null && VersionComparator.compare(candidate, min) < 0) {
                return false;
            }
            if (max != null && VersionComparator.compare(candidate, max) > 0) {
                return false;
            }
            return true;
        }

        private String describe() {
            if (min != null && max != null) {
                return min + "-" + max;
            }
            if (min != null) {
                return ">=" + min;
            }
            if (max != null) {
                return "<=" + max;
            }
            return "any";
        }
    }
}
