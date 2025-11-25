package com.github.hrobasti.turtlelib.helper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class UpdateChecker {
    private final Plugin plugin;
    private final int providerMode; // 0 both, 1 modrinth, 2 hangar
    private final boolean includePrereleases;
    private final boolean filterByServerVersion;
    private final String modrinthSlug;
    private final String hangarNamespace;
    private final String userAgent;
    private final String minecraftVersion;
    private final String minecraftVersionLower;
    private final String minecraftVersionPrefix;
    private final String minecraftVersionPrefixLower;

    public UpdateChecker(Plugin plugin, int providerMode, boolean includePrereleases,
                         String modrinthSlug, String hangarNamespace) {
        this(plugin, providerMode, includePrereleases, false, modrinthSlug, hangarNamespace);
    }

    public UpdateChecker(Plugin plugin, int providerMode, boolean includePrereleases,
                         boolean filterByServerVersion,
                         String modrinthSlug, String hangarNamespace) {
        this.plugin = plugin;
        this.providerMode = providerMode;
        this.includePrereleases = includePrereleases;
        this.filterByServerVersion = filterByServerVersion;
        this.modrinthSlug = modrinthSlug;
        this.hangarNamespace = hangarNamespace;
        this.userAgent = plugin.getPluginMeta().getName() + "-UpdateChecker";
        String mcVersion = filterByServerVersion ? normalizeMinecraftVersion(safeMinecraftVersion()) : null;
        this.minecraftVersion = mcVersion;
        this.minecraftVersionLower = mcVersion == null ? null : mcVersion.toLowerCase(Locale.ROOT);
        this.minecraftVersionPrefix = mcVersion == null ? null : deriveMinorPrefix(mcVersion);
        this.minecraftVersionPrefixLower = minecraftVersionPrefix == null
            ? null
            : minecraftVersionPrefix.toLowerCase(Locale.ROOT);
    }

    public CompletableFuture<UpdateInfo> checkAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String current = plugin.getPluginMeta().getVersion();
                List<ProviderResult> providers = new ArrayList<>();
                if (providerMode == 0 || providerMode == 1) {
                    providers.add(fetchModrinthStatus());
                }
                if (providerMode == 0 || providerMode == 2) {
                    providers.add(fetchHangarStatus());
                }
                String latest = findNewestAvailableVersion(current, providers);
                return new UpdateInfo(current, latest, providers);
            } catch (Throwable t) {
                plugin.getLogger().fine("Update check failed: " + t.getMessage());
                return new UpdateInfo(plugin.getPluginMeta().getVersion(), null, List.of());
            }
        });
    }

    public record UpdateInfo(String currentVersion, String latestVersion, List<ProviderResult> providers) {
        public boolean hasUpdate() {
            return latestVersion != null && !latestVersion.isBlank();
        }
    }

    public record ProviderResult(Provider provider, String latestVersion, String url, String errorMessage, JsonObject metadata) {
        public boolean success() {
            return errorMessage == null;
        }
    }

    public enum Provider {
        MODRINTH("Modrinth"),
        HANGAR("Hangar");

        private final String displayName;

        Provider(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private ProviderResult fetchModrinthStatus() {
        try {
            JsonObject version = latestFromModrinth();
            if (version == null || !version.has("version_number")) {
                return errorResult(Provider.MODRINTH, "Fehler beim Abruf");
            }
            String latest = version.get("version_number").getAsString();
            return new ProviderResult(Provider.MODRINTH, latest, providerBaseUrl(Provider.MODRINTH), null, version);
        } catch (Exception ex) {
            plugin.getLogger().fine("Modrinth update check failed: " + ex.getMessage());
            return errorResult(Provider.MODRINTH, "Fehler beim Abruf");
        }
    }

    private ProviderResult fetchHangarStatus() {
        try {
            JsonObject version = latestFromHangar();
            if (version == null || !version.has("name")) {
                return errorResult(Provider.HANGAR, "Fehler beim Abruf");
            }
            String latest = version.get("name").getAsString();
            return new ProviderResult(Provider.HANGAR, latest, providerBaseUrl(Provider.HANGAR), null, version);
        } catch (Exception ex) {
            plugin.getLogger().fine("Hangar update check failed: " + ex.getMessage());
            return errorResult(Provider.HANGAR, "Fehler beim Abruf");
        }
    }

    private ProviderResult errorResult(Provider provider, String message) {
        return new ProviderResult(provider, null, providerBaseUrl(provider), message, null);
    }

    private String findNewestAvailableVersion(String current, List<ProviderResult> providers) {
        String latest = null;
        for (ProviderResult result : providers) {
            String candidate = result.latestVersion();
            if (candidate == null) continue;
            if (!VersionComparator.isGreater(candidate, current)) continue;
            if (latest == null || VersionComparator.isGreater(candidate, latest)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private JsonObject latestFromModrinth() throws Exception {
        if (modrinthSlug == null || modrinthSlug.isBlank()) {
            throw new IllegalStateException("No Modrinth slug configured");
        }
        URL url = URI.create("https://api.modrinth.com/v2/project/" + modrinthSlug + "/version").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", userAgent);
        if (conn.getResponseCode() != 200) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JsonElement el = JsonParser.parseString(sb.toString());
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement entry : arr) {
                    if (!entry.isJsonObject()) continue;
                    JsonObject version = entry.getAsJsonObject();
                    if (!includePrereleases && isModrinthPrerelease(version)) continue;
                    if (!matchesServerVersion(version, Provider.MODRINTH)) continue;
                    if (version.has("version_number")) {
                        return version;
                    }
                }
            }
        }
        throw new IllegalStateException("No versions found");
    }

    private JsonObject latestFromHangar() throws Exception {
        if (hangarNamespace == null || hangarNamespace.isBlank()) {
            throw new IllegalStateException("No Hangar namespace configured");
        }
        URL url = URI.create("https://hangar.papermc.io/api/v1/projects/" + hangarNamespace + "/versions").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", userAgent);
        if (conn.getResponseCode() != 200) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JsonElement el = JsonParser.parseString(sb.toString());
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement entry : arr) {
                    if (!entry.isJsonObject()) continue;
                    JsonObject version = entry.getAsJsonObject();
                    if (!includePrereleases && isHangarPrerelease(version)) continue;
                    if (!matchesServerVersion(version, Provider.HANGAR)) continue;
                    if (version.has("name")) {
                        return version;
                    }
                }
            } else if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("result")) {
                    JsonElement res = obj.get("result");
                    if (res.isJsonArray()) {
                        JsonArray arr = res.getAsJsonArray();
                        for (JsonElement entry : arr) {
                            if (!entry.isJsonObject()) continue;
                            JsonObject version = entry.getAsJsonObject();
                            if (!includePrereleases && isHangarPrerelease(version)) continue;
                            if (!matchesServerVersion(version, Provider.HANGAR)) continue;
                            if (version.has("name")) return version;
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("No versions found");
    }

    private String providerBaseUrl(Provider provider) {
        return switch (provider) {
            case MODRINTH -> modrinthSlug == null || modrinthSlug.isBlank()
                    ? "https://modrinth.com"
                    : "https://modrinth.com/plugin/" + modrinthSlug;
            case HANGAR -> hangarNamespace == null || hangarNamespace.isBlank()
                    ? "https://hangar.papermc.io"
                    : "https://hangar.papermc.io/" + hangarNamespace;
        };
    }

    private boolean isModrinthPrerelease(JsonObject version) {
        if (version.has("prerelease") && version.get("prerelease").isJsonPrimitive()) {
            try {
                if (version.get("prerelease").getAsBoolean()) return true;
            } catch (Exception ignored) {
                // ignore parse issues
            }
        }
        if (version.has("version_type") && version.get("version_type").isJsonPrimitive()) {
            String type = version.get("version_type").getAsString();
            return !"release".equalsIgnoreCase(type);
        }
        return false;
    }

    private boolean isHangarPrerelease(JsonObject version) {
        if (version.has("channel")) {
            JsonElement channelEl = version.get("channel");
            String channelName = null;
            if (channelEl.isJsonObject()) {
                JsonObject channelObj = channelEl.getAsJsonObject();
                if (channelObj.has("name")) {
                    channelName = channelObj.get("name").getAsString();
                }
            } else if (channelEl.isJsonPrimitive()) {
                channelName = channelEl.getAsString();
            }
            if (channelName != null) {
                return !"release".equalsIgnoreCase(channelName);
            }
        }
        if (version.has("visibility") && version.get("visibility").isJsonPrimitive()) {
            return false;
        }
        return false;
    }

    private boolean matchesServerVersion(JsonObject version, Provider provider) {
        if (!filterByServerVersion || minecraftVersionLower == null) {
            return true;
        }
        boolean matched = switch (provider) {
            case MODRINTH -> matchesModrinthVersions(version);
            case HANGAR -> matchesHangarVersions(version);
        };
        if (matched) {
            return true;
        }
        String raw = version.toString();
        return fallbackMatches(raw);
    }

    private boolean matchesModrinthVersions(JsonObject version) {
        JsonElement versions = version.get("game_versions");
        if (versions != null && versions.isJsonArray()) {
            JsonArray arr = versions.getAsJsonArray();
            for (JsonElement entry : arr) {
                if (!entry.isJsonPrimitive()) {
                    continue;
                }
                if (matchesVersionToken(entry.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesHangarVersions(JsonObject version) {
        JsonElement deps = version.get("platformDependencies");
        if (deps != null) {
            if (deps.isJsonArray()) {
                for (JsonElement entry : deps.getAsJsonArray()) {
                    if (!entry.isJsonObject()) {
                        continue;
                    }
                    if (matchesHangarDependency(entry.getAsJsonObject())) {
                        return true;
                    }
                }
            } else if (deps.isJsonObject()) {
                if (matchesHangarDependency(deps.getAsJsonObject())) {
                    return true;
                }
            }
        }
        JsonElement mcVersions = version.get("minecraft_versions");
        if (mcVersions != null && mcVersions.isJsonArray()) {
            for (JsonElement entry : mcVersions.getAsJsonArray()) {
                if (!entry.isJsonPrimitive()) continue;
                if (matchesVersionToken(entry.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesHangarDependency(JsonObject dependency) {
        if (dependency == null) {
            return false;
        }
        if (dependency.has("required") && dependency.get("required").isJsonArray()) {
            if (matchesVersionArray(dependency.get("required").getAsJsonArray())) {
                return true;
            }
        }
        if (dependency.has("compatible") && dependency.get("compatible").isJsonArray()) {
            if (matchesVersionArray(dependency.get("compatible").getAsJsonArray())) {
                return true;
            }
        }
        if (dependency.has("versions") && dependency.get("versions").isJsonArray()) {
            return matchesVersionArray(dependency.get("versions").getAsJsonArray());
        }
        return false;
    }

    private boolean matchesVersionArray(JsonArray array) {
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            if (matchesVersionToken(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesVersionToken(String value) {
        if (value == null || minecraftVersionLower == null) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        normalized = normalized.replace("\"", "");
        if (normalized.isEmpty()) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals(minecraftVersionLower)) {
            return true;
        }
        if (minecraftVersionPrefixLower != null) {
            if (lower.equals(minecraftVersionPrefixLower)) {
                return true;
            }
            if (lower.startsWith(minecraftVersionPrefixLower + ".")) {
                return true;
            }
            if (lower.equals(minecraftVersionPrefixLower + ".x") || lower.equals(minecraftVersionPrefixLower + "x")) {
                return true;
            }
        }
        return false;
    }

    private boolean fallbackMatches(String raw) {
        if (raw == null || minecraftVersion == null) {
            return false;
        }
        if (raw.contains("\"" + minecraftVersion + "\"")) {
            return true;
        }
        if (raw.contains(minecraftVersion)) {
            return true;
        }
        if (minecraftVersionPrefix != null) {
            if (raw.contains("\"" + minecraftVersionPrefix + "\"")) {
                return true;
            }
            if (raw.contains("\"" + minecraftVersionPrefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private String safeMinecraftVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String normalizeMinecraftVersion(String version) {
        if (version == null) {
            return null;
        }
        int dash = version.indexOf('-');
        if (dash > 0) {
            version = version.substring(0, dash);
        }
        return version.trim();
    }

    private String deriveMinorPrefix(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return version;
    }
}

