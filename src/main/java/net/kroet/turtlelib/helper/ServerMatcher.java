package net.kroet.turtlelib.helper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Utility that verifies whether the currently running Paper/Minecraft version
 * matches a developer-defined allow list. It uses {@link VersionComparator} for
 * range checks and can either warn or disable the plugin automatically.
 */
public final class ServerMatcher {

    public enum IncompatibleAction {
        WARN_AND_CONTINUE, ERROR_AND_DISABLE
    }

    private final JavaPlugin plugin;
    private final Set<String> exactVersions;
    private final List<VersionRange> ranges;
    private final Set<String> minorSeries;
    private final IncompatibleAction action;
    private final Consumer<MatchResult> mismatchConsumer;
    private final String supportedSummary;

    private ServerMatcher(Builder builder) {
        this.plugin = builder.plugin;
        this.exactVersions = Collections.unmodifiableSet(builder.exactVersions);
        this.ranges = Collections.unmodifiableList(builder.ranges);
        this.minorSeries = Collections.unmodifiableSet(builder.minorSeries);
        this.action = builder.action;
        this.mismatchConsumer = builder.mismatchConsumer;
        this.supportedSummary = builder.describeRules();
    }

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(Objects.requireNonNull(plugin, "plugin"));
    }

    // Rules only, for tests; check() and enforce() need a plugin.
    static Builder rulesBuilder() {
        return new Builder(null);
    }

    /**
     * Checks the current server without taking any automatic action.
     */
    public MatchResult check() {
        return evaluate(plugin.getServer().getName(), readMinecraftVersion(Bukkit::getMinecraftVersion));
    }

    /**
     * The server's Minecraft version, or {@code null} where the Paper method is
     * missing, as on Spigot. Package-private for ServerMatcherTest.
     */
    static String readMinecraftVersion(Supplier<String> paperMinecraftVersion) {
        try {
            return paperMinecraftVersion.get();
        } catch (NoSuchMethodError e) {
            return null;
        }
    }

    // A server that can't report its Minecraft version is never supported.
    MatchResult evaluate(String serverName, String mcVersion) {
        if (mcVersion == null) {
            return new MatchResult(false, serverName, "unknown", supportedSummary, "Server " + serverName
                    + " doesn't report its Minecraft version (no Paper API), so it counts as unsupported");
        }
        boolean supported = isSupported(mcVersion);
        String reason = supported ? "" : buildReason(mcVersion, serverName);
        return new MatchResult(supported, serverName, mcVersion, supportedSummary, reason);
    }

    /**
     * Checks the server and performs the configured {@link IncompatibleAction} when
     * unsupported.
     */
    public MatchResult enforce() {
        return enforce(check(), plugin.getLogger(), () -> Bukkit.getPluginManager().disablePlugin(plugin));
    }

    // The configured action runs even if the onMismatch hook throws.
    // Package-private
    // for ServerMatcherTest.
    MatchResult enforce(MatchResult result, Logger logger, Runnable disable) {
        if (result.supported()) {
            return result;
        }
        boolean hookReported = false;
        if (mismatchConsumer != null) {
            try {
                mismatchConsumer.accept(result);
                hookReported = true;
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[ServerMatcher] The onMismatch hook failed", e);
            }
        }
        String detail = "Detected " + result.serverName + " (MC " + result.minecraftVersion
                + "), supported set: " + supportedSummary + '.';
        if (action == IncompatibleAction.WARN_AND_CONTINUE) {
            // An onMismatch consumer already told the admin; one warning is enough.
            if (!hookReported) {
                logger.warning("[ServerMatcher] " + detail + " Proceeding, but behaviour is unsupported.");
            }
        } else {
            logger.severe("[ServerMatcher] " + detail + " Disabling plugin due to incompatible server version.");
            disable.run();
        }
        return result;
    }

    boolean isSupported(String candidateVersion) {
        if (candidateVersion == null || candidateVersion.isBlank()) {
            return false;
        }
        if (exactVersions.isEmpty() && ranges.isEmpty() && minorSeries.isEmpty()) {
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
        if (!minorSeries.isEmpty()) {
            String candidatePrefix = minorPrefix(normalized);
            if (candidatePrefix != null && minorSeries.contains(candidatePrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the {@code major.minor} prefix from a version string, e.g.
     * {@code "26.3.1"} or {@code "26.3-pre1"} to {@code "26.3"}. Used to match any
     * patch, hotfix or prerelease of an allowed minor series.
     */
    static String minorPrefix(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String release = version.trim();
        int dash = release.indexOf('-');
        if (dash > 0) {
            release = release.substring(0, dash);
        }
        String[] parts = release.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return release;
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
        private final Set<String> minorSeries = new LinkedHashSet<>();
        private IncompatibleAction action = IncompatibleAction.ERROR_AND_DISABLE;
        private Consumer<MatchResult> mismatchConsumer;

        private Builder(JavaPlugin plugin) {
            this.plugin = plugin;
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

        /**
         * Allows a whole {@code major.minor} series including hotfixes, e.g.
         * {@code allowMinorSeries("26.3")} accepts {@code "26.3.1"}. Prefer this over
         * {@link #allowRange} with equal min/max, which rejects hotfixes.
         */
        public Builder allowMinorSeries(String minorVersion) {
            String prefix = minorPrefix(minorVersion);
            if (prefix != null) {
                minorSeries.add(prefix);
            }
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
            if (exactVersions.isEmpty() && ranges.isEmpty() && minorSeries.isEmpty()) {
                return "no constraints";
            }
            StringJoiner joiner = new StringJoiner(", ");
            for (VersionRange range : ranges) {
                joiner.add(range.describe());
            }
            for (String series : minorSeries) {
                joiner.add(series + ".x");
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
