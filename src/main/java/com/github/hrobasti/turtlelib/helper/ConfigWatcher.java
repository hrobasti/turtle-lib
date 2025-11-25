package com.github.hrobasti.turtlelib.helper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Polls a target file on a fixed interval and triggers a callback on the main thread when the content changes.
 */
public final class ConfigWatcher implements Runnable {
    private final JavaPlugin plugin;
    private final Supplier<File> fileSupplier;
    private final Runnable onChange;
    private final BooleanSupplier enabledSupplier;
    private final long intervalTicks;
    private BukkitTask task;
    private volatile Fingerprint lastFingerprint = Fingerprint.missing();

    private ConfigWatcher(JavaPlugin plugin,
                          Supplier<File> fileSupplier,
                          Runnable onChange,
                          BooleanSupplier enabledSupplier,
                          long intervalTicks) {
        this.plugin = plugin;
        this.fileSupplier = fileSupplier;
        this.onChange = onChange;
        this.enabledSupplier = enabledSupplier;
        this.intervalTicks = intervalTicks;
    }

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    public synchronized void start() {
        stop();
        refreshBaseline();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this, intervalTicks, intervalTicks);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void refreshBaseline() {
        lastFingerprint = fingerprint(fileSupplier.get(), lastFingerprint);
    }

    @Override
    public void run() {
        if (!plugin.isEnabled()) {
            return;
        }
        if (!enabledSupplier.getAsBoolean()) {
            return;
        }
        try {
            File file = fileSupplier.get();
            Fingerprint current = fingerprint(file, lastFingerprint);
            if (!current.equals(lastFingerprint)) {
                lastFingerprint = current;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        onChange.run();
                    } catch (Throwable ex) {
                        plugin.getLogger().fine("Config watcher callback failed: " + ex.getMessage());
                    }
                });
            }
        } catch (Throwable ex) {
            plugin.getLogger().fine("Config watcher task failed: " + ex.getMessage());
        }
    }

    private Fingerprint fingerprint(File file, Fingerprint previous) {
        if (file == null || !file.exists()) {
            return Fingerprint.missing();
        }
        long modified = file.lastModified();
        long size = file.length();
        int hash = (previous != null && previous.exists() && previous.modified == modified && previous.size == size)
            ? previous.hash
            : computeHash(file);
        return new Fingerprint(modified, size, hash, true);
    }

    private int computeHash(File file) {
        CRC32 crc = new CRC32();
        try (var in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return (int) crc.getValue();
        } catch (IOException ex) {
            plugin.getLogger().fine("Config watcher hash error: " + ex.getMessage());
            return -1;
        }
    }

    public static final class Builder {
        private static final long DEFAULT_INTERVAL_TICKS = 100L;
        private final JavaPlugin plugin;
        private Supplier<File> fileSupplier;
        private Runnable onChange;
        private BooleanSupplier enabledSupplier = () -> true;
        private long intervalTicks = DEFAULT_INTERVAL_TICKS;

        private Builder(JavaPlugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        public Builder file(File file) {
            this.fileSupplier = () -> file;
            return this;
        }

        public Builder fileSupplier(Supplier<File> supplier) {
            this.fileSupplier = supplier;
            return this;
        }

        public Builder onChange(Runnable runnable) {
            this.onChange = runnable;
            return this;
        }

        public Builder enabledSupplier(BooleanSupplier supplier) {
            this.enabledSupplier = supplier;
            return this;
        }

        public Builder intervalTicks(long ticks) {
            this.intervalTicks = ticks;
            return this;
        }

        /**
         * Convenience setter allowing callers to express the polling interval in seconds.
         * Values are clamped to at least one second, then converted to ticks (20 per second).
         */
        public Builder intervalSeconds(long seconds) {
            long safeSeconds = Math.max(1L, seconds);
            this.intervalTicks = safeSeconds * 20L;
            return this;
        }

        public ConfigWatcher build() {
            Objects.requireNonNull(fileSupplier, "fileSupplier");
            Objects.requireNonNull(onChange, "onChange");
            Objects.requireNonNull(enabledSupplier, "enabledSupplier");
            long ticks = intervalTicks <= 0L ? DEFAULT_INTERVAL_TICKS : intervalTicks;
            return new ConfigWatcher(plugin, fileSupplier, onChange, enabledSupplier, ticks);
        }
    }

    private record Fingerprint(long modified, long size, int hash, boolean exists) {
        static Fingerprint missing() {
            return new Fingerprint(0L, -1L, 0, false);
        }
    }
}

