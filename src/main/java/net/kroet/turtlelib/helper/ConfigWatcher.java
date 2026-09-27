package net.kroet.turtlelib.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Polls a target file on a fixed interval and triggers a callback on the main
 * thread when the content changes. A missing or empty file never triggers it;
 * {@code onSkipped} reports that instead, once per state.
 */
public final class ConfigWatcher implements Runnable {
    private final JavaPlugin plugin;
    private final Supplier<File> fileSupplier;
    private final Runnable onChange;
    private final Consumer<SkipReason> onSkipped;
    private long intervalTicks;
    private volatile boolean enabled;
    private BukkitTask task;
    private final Object stateLock = new Object();
    // Content the plugin last loaded; observed only saves re-hashing each poll.
    private Fingerprint baseline = Fingerprint.missing();
    private Fingerprint observed = Fingerprint.missing();
    private SkipReason reportedSkip;

    /** Why a change of the watched file didn't trigger {@code onChange}. */
    public enum SkipReason {
        /** The file was deleted or moved away. */
        MISSING,
        /** The file has no keys: it is empty or holds only comments or blank lines. */
        EMPTY
    }

    enum Outcome {
        NONE, CHANGED, MISSING, EMPTY
    }

    ConfigWatcher(JavaPlugin plugin,
            Supplier<File> fileSupplier,
            Runnable onChange,
            Consumer<SkipReason> onSkipped,
            boolean enabled,
            long intervalTicks) {
        this.plugin = plugin;
        this.fileSupplier = fileSupplier;
        this.onChange = onChange;
        this.onSkipped = onSkipped;
        this.enabled = enabled;
        this.intervalTicks = intervalTicks;
    }

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    /**
     * Checks a YAML file as {@code JavaPlugin#reloadConfig()} would read it, so a
     * reload can skip a broken file: {@code null} if it's valid or missing, else a
     * reason like "line 5, column 28: mapping values are not allowed here".
     */
    public static String describeYamlError(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            new YamlConfiguration().load(file);
            return null;
        } catch (InvalidConfigurationException e) {
            return YamlLines.describeYamlError(e);
        } catch (IOException e) {
            return "the file couldn't be read: " + e.getMessage();
        }
    }

    public synchronized void start() {
        stop();
        refreshBaseline();
        task = schedule();
    }

    /**
     * Changes the polling interval (at least one second), e.g. on a reload; returns
     * {@code false} if it is unchanged. A running watcher keeps its baseline.
     */
    public synchronized boolean setIntervalSeconds(long seconds) {
        long ticks = secondsToTicks(seconds);
        if (ticks == intervalTicks) {
            return false;
        }
        intervalTicks = ticks;
        if (task != null) {
            task.cancel();
            task = schedule();
        }
        return true;
    }

    synchronized long intervalTicks() {
        return intervalTicks;
    }

    static long secondsToTicks(long seconds) {
        return Math.max(1L, seconds) * 20L;
    }

    private BukkitTask schedule() {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this, intervalTicks, intervalTicks);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Takes the file's current state as the loaded one, e.g. after the plugin wrote
     * or reloaded it. A missing or empty file then isn't reported either.
     */
    public void refreshBaseline() {
        refreshBaseline(fileSupplier.get(), plugin.getLogger());
    }

    void refreshBaseline(File file, Logger logger) {
        synchronized (stateLock) {
            observed = fingerprint(file, observed, logger);
            baseline = observed;
            reportedSkip = observed.skipReason();
        }
    }

    /**
     * Turns polling on or off; safe to call from the main thread at any time. A
     * paused watcher turned back on reads the file right away and starts from it.
     */
    public void setEnabled(boolean enabled) {
        setEnabled(enabled, this::refreshBaseline);
    }

    void setEnabled(boolean enabled, File file, Logger logger) {
        setEnabled(enabled, () -> refreshBaseline(file, logger));
    }

    private void setEnabled(boolean enabled, Runnable refresh) {
        if (enabled && !this.enabled) {
            refresh.run(); // edits made during the pause don't count as changes
        }
        this.enabled = enabled;
    }

    @Override
    public void run() {
        if (!plugin.isEnabled()) {
            return;
        }
        if (!enabled) {
            return;
        }
        Logger logger = plugin.getLogger();
        try {
            File file = fileSupplier.get();
            Outcome outcome = poll(file, logger);
            if (outcome == Outcome.CHANGED) {
                Bukkit.getScheduler().runTask(plugin, () -> runCallback(onChange, logger));
            } else if (outcome != Outcome.NONE) {
                SkipReason reason = outcome == Outcome.MISSING ? SkipReason.MISSING : SkipReason.EMPTY;
                Bukkit.getScheduler().runTask(plugin,
                        () -> runCallback(() -> reportSkipped(reason, file, logger), logger));
            }
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "Config watcher task failed", ex);
        }
    }

    // One poll: CHANGED once per new content, MISSING or EMPTY once per state;
    // the baseline stays at the last content with keys.
    Outcome poll(File file, Logger logger) {
        synchronized (stateLock) {
            observed = fingerprint(file, observed, logger);
            SkipReason skip = observed.skipReason();
            if (skip != null) {
                if (skip == reportedSkip) {
                    return Outcome.NONE;
                }
                reportedSkip = skip;
                return skip == SkipReason.MISSING ? Outcome.MISSING : Outcome.EMPTY;
            }
            reportedSkip = null;
            if (observed.sameContent(baseline)) {
                return Outcome.NONE;
            }
            baseline = observed;
            return Outcome.CHANGED;
        }
    }

    void reportSkipped(SkipReason reason, File file, Logger logger) {
        if (onSkipped != null) {
            onSkipped.accept(reason);
            return;
        }
        String name = file == null ? "The watched file" : file.getName();
        logger.warning(name + (reason == SkipReason.MISSING ? " was deleted" : " is empty")
                + "; the current settings stay until it has settings again or the plugin reloads.");
    }

    // A failing callback is only logged, so the polling task keeps running.
    static void runCallback(Runnable onChange, Logger logger) {
        try {
            onChange.run();
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "Config watcher callback failed", ex);
        }
    }

    static Fingerprint fingerprint(File file, Fingerprint previous, Logger logger) {
        if (file == null || !file.exists()) {
            return Fingerprint.missing();
        }
        long modified = file.lastModified();
        long size = file.length();
        if (previous != null && previous.exists() && previous.modified == modified && previous.size == size) {
            return previous;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Config watcher hash error for " + file.getName(), ex);
            return new Fingerprint(modified, size, -1, true, false);
        }
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return new Fingerprint(modified, size, (int) crc.getValue(), true, hasNoKeys(bytes));
    }

    // Empty as Bukkit loads it: valid YAML without a single key. A file that isn't
    // valid YAML counts as content, so the reload can report the error.
    static boolean hasNoKeys(byte[] bytes) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException ex) {
            return false;
        }
        return yaml.getKeys(false).isEmpty();
    }

    public static final class Builder {
        private static final long DEFAULT_INTERVAL_TICKS = 100L;
        private final JavaPlugin plugin;
        private Supplier<File> fileSupplier;
        private Runnable onChange;
        private Consumer<SkipReason> onSkipped;
        private boolean enabled = true;
        private long intervalTicks = DEFAULT_INTERVAL_TICKS;

        private Builder(JavaPlugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        public Builder file(File file) {
            this.fileSupplier = () -> file;
            return this;
        }

        /**
         * Resolves the file on every poll, on the background thread, so it must not
         * read the plugin's {@code FileConfiguration}.
         */
        public Builder fileSupplier(Supplier<File> supplier) {
            this.fileSupplier = supplier;
            return this;
        }

        /** Runs on the main thread when the file has new content with keys. */
        public Builder onChange(Runnable runnable) {
            this.onChange = runnable;
            return this;
        }

        /**
         * Runs on the main thread once the file goes missing or empty, e.g. to log a
         * translated warning; without it, the watcher logs an English one.
         */
        public Builder onSkipped(Consumer<SkipReason> callback) {
            this.onSkipped = callback;
            return this;
        }

        /**
         * Initial enabled state, before the first
         * {@link ConfigWatcher#setEnabled(boolean)} call.
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder intervalTicks(long ticks) {
            this.intervalTicks = ticks;
            return this;
        }

        /**
         * Convenience setter allowing callers to express the polling interval in
         * seconds. Values are clamped to at least one second, then converted to ticks
         * (20 per second).
         */
        public Builder intervalSeconds(long seconds) {
            this.intervalTicks = secondsToTicks(seconds);
            return this;
        }

        public ConfigWatcher build() {
            Objects.requireNonNull(fileSupplier, "fileSupplier");
            Objects.requireNonNull(onChange, "onChange");
            long ticks = intervalTicks <= 0L ? DEFAULT_INTERVAL_TICKS : intervalTicks;
            return new ConfigWatcher(plugin, fileSupplier, onChange, onSkipped, enabled, ticks);
        }
    }

    record Fingerprint(long modified, long size, int hash, boolean exists, boolean empty) {
        static Fingerprint missing() {
            return new Fingerprint(0L, -1L, 0, false, false);
        }

        SkipReason skipReason() {
            if (!exists) {
                return SkipReason.MISSING;
            }
            return empty ? SkipReason.EMPTY : null;
        }

        // The mtime only decides whether to re-hash; a rewrite with identical bytes
        // must not count as a change.
        boolean sameContent(Fingerprint other) {
            return exists == other.exists && size == other.size && hash == other.hash;
        }
    }
}
