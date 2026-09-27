package net.kroet.turtlelib.helper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class UpdateChecker {
    /**
     * Maximum bytes read from a provider response, to protect against oversized or
     * malicious replies.
     */
    private static final long MAX_RESPONSE_BYTES = 1_048_576L; // 1 MiB
    // The request timeout ends at the headers; this one covers the body too.
    private static final long RESPONSE_TIMEOUT_SECONDS = 10L;
    private static final Pattern MODRINTH_SLUG_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern HANGAR_NAMESPACE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_-]{1,64}$");
    // Hangar returns at most 25 versions per request; four pages cover 100 uploads.
    static final int HANGAR_PAGE_SIZE = 25;
    static final int MAX_HANGAR_PAGES = 4;
    // Modrinth loaders whose builds run on Paper.
    private static final Set<String> PAPER_LOADERS = Set.of("paper", "spigot", "bukkit", "purpur", "folia");
    // Version names the checker accepts: at most 64 characters versions use, so an
    // answer can't bring control characters or huge texts into logs and chat.
    private static final int MAX_VERSION_NAME = 64;
    private static final Pattern VERSION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+_-]*");
    // Words in a Hangar channel name that mark prereleases: any word starting with
    // one of the first, or exactly one of the second.
    private static final List<String> PRERELEASE_CHANNEL_WORDS = List.of("alpha", "beta", "snapshot", "dev",
            "nightly", "experiment", "unstable", "preview", "prerelease", "canary", "test", "candidate");
    private static final Set<String> PRERELEASE_CHANNEL_TOKENS = Set.of("rc", "pre");

    // Own daemon pool and HTTP client, made on first use, closed by shutdown();
    // idle threads end by themselves, so a /reload leaves none behind.
    private static Http http;

    private final Plugin plugin;
    private final int providerMode; // 0 both, 1 modrinth, 2 hangar
    private final boolean includePrereleases;
    private final boolean filterByServerVersion;
    private final String modrinthSlug;
    private final String hangarNamespace;
    private final String userAgent;
    private final String minecraftVersionLower;
    private final String minecraftVersionPrefixLower;
    private volatile boolean logFailures = true;

    public UpdateChecker(Plugin plugin, int providerMode, boolean includePrereleases,
            String modrinthSlug, String hangarNamespace) {
        this(plugin, providerMode, includePrereleases, false, modrinthSlug, hangarNamespace);
    }

    public UpdateChecker(Plugin plugin, int providerMode, boolean includePrereleases,
            boolean filterByServerVersion,
            String modrinthSlug, String hangarNamespace) {
        if (providerMode < 0 || providerMode > 2) {
            throw new IllegalArgumentException("providerMode must be 0 (both), 1 (Modrinth) or 2 (Hangar), got "
                    + providerMode);
        }
        this.plugin = plugin;
        this.providerMode = providerMode;
        this.includePrereleases = includePrereleases;
        this.filterByServerVersion = filterByServerVersion;
        this.modrinthSlug = sanitizeSlug(plugin.getLogger(), modrinthSlug);
        this.hangarNamespace = sanitizeNamespace(plugin.getLogger(), hangarNamespace);
        this.userAgent = plugin.getPluginMeta().getName() + "-UpdateChecker";
        String mcVersion = filterByServerVersion ? normalizeMinecraftVersion(safeMinecraftVersion()) : null;
        this.minecraftVersionLower = mcVersion == null ? null : mcVersion.toLowerCase(Locale.ROOT);
        String mcVersionPrefix = mcVersion == null ? null : deriveMinorPrefix(mcVersion);
        this.minecraftVersionPrefixLower = mcVersionPrefix == null ? null : mcVersionPrefix.toLowerCase(Locale.ROOT);
    }

    /**
     * Test-only constructor exposing just the state the
     * {@code select*}/{@code *Compatibility} logic reads, so
     * {@code UpdateCheckerFixtureTest} can exercise it without a {@code Plugin}.
     */
    UpdateChecker(boolean includePrereleases, boolean filterByServerVersion, String minecraftVersion) {
        this.plugin = null;
        this.providerMode = 0;
        this.includePrereleases = includePrereleases;
        this.filterByServerVersion = filterByServerVersion;
        this.modrinthSlug = null;
        this.hangarNamespace = null;
        this.userAgent = null;
        String mcVersion = filterByServerVersion ? normalizeMinecraftVersion(minecraftVersion) : null;
        this.minecraftVersionLower = mcVersion == null ? null : mcVersion.toLowerCase(Locale.ROOT);
        String mcVersionPrefix = mcVersion == null ? null : deriveMinorPrefix(mcVersion);
        this.minecraftVersionPrefixLower = mcVersionPrefix == null ? null : mcVersionPrefix.toLowerCase(Locale.ROOT);
    }

    record Http(HttpClient client, ExecutorService executor) {
        boolean stopped() {
            return executor.isShutdown();
        }
    }

    static synchronized Http http() {
        if (http == null) {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "turtle-lib-UpdateChecker");
                        thread.setDaemon(true);
                        return thread;
                    });
            executor.allowCoreThreadTimeOut(true);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(executor)
                    .build();
            http = new Http(client, executor);
        }
        return http;
    }

    /**
     * Whether a failed provider is logged as a {@code WARNING} (the default). Turn
     * it off when you report {@link ProviderResult#errorMessage()} yourself.
     */
    public void setLogFailures(boolean logFailures) {
        this.logFailures = logFailures;
    }

    /**
     * Stops the update checks' HTTP client and threads, e.g. in {@code onDisable};
     * running checks end quietly, and a later check starts fresh ones.
     */
    public static synchronized void shutdown() {
        if (http != null) {
            http.client().shutdownNow();
            http.executor().shutdownNow();
            http = null;
        }
    }

    /**
     * Checks the configured providers without blocking; the returned future
     * completes on the checker's own thread, not the Bukkit main thread.
     */
    public CompletableFuture<UpdateInfo> checkAsync() {
        String current = plugin.getPluginMeta().getVersion();
        Http http = http();
        List<CompletableFuture<ProviderResult>> checks = new ArrayList<>();
        for (Provider provider : providersToCheck(providerMode, modrinthSlug, hangarNamespace)) {
            checks.add(provider == Provider.MODRINTH ? fetchModrinthStatusAsync(http) : fetchHangarStatusAsync(http));
        }
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture<?>[0]))
                .thenApplyAsync(ignored -> {
                    List<ProviderResult> providers = new ArrayList<>(checks.size());
                    for (CompletableFuture<ProviderResult> check : checks) {
                        providers.add(check.join());
                    }
                    String latest = findNewestAvailableVersion(current, providers);
                    return new UpdateInfo(current, latest, providers);
                }, http.executor())
                .exceptionally(t -> {
                    // The providers catch their own failures; after shutdown() this stays quiet.
                    if (!http.stopped()) {
                        plugin.getLogger().warning("Update check failed: " + t.getMessage());
                    }
                    return new UpdateInfo(current, null, List.of());
                });
    }

    public record UpdateInfo(String currentVersion, String latestVersion, List<ProviderResult> providers) {
        public boolean hasUpdate() {
            return latestVersion != null && !latestVersion.isBlank();
        }
    }

    public record ProviderResult(Provider provider, String latestVersion, String url, String errorMessage, JsonObject metadata,
            boolean compatibilityUnknown) {
        public boolean success() {
            return errorMessage == null;
        }
    }

    /**
     * Package-private (not {@code private}) so tests can read the selection result.
     */
    record SelectedVersion(JsonObject json, boolean compatibilityUnknown) {
    }

    private enum Compatibility {
        MATCH, MISMATCH, UNKNOWN
    }

    public enum Provider {
        MODRINTH("Modrinth"), HANGAR("Hangar");

        private final String displayName;

        Provider(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * Checking both, a provider without an ID is skipped rather than reported as an
     * error, so a plugin published on one platform only can still use mode 0.
     * Package-private for UpdateCheckerTest.
     */
    static List<Provider> providersToCheck(int providerMode, String modrinthSlug, String hangarNamespace) {
        List<Provider> providers = new ArrayList<>(2);
        if (providerMode == 1 || providerMode == 0 && isConfigured(modrinthSlug)) {
            providers.add(Provider.MODRINTH);
        }
        if (providerMode == 2 || providerMode == 0 && isConfigured(hangarNamespace)) {
            providers.add(Provider.HANGAR);
        }
        return providers;
    }

    private static boolean isConfigured(String id) {
        return id != null && !id.isBlank();
    }

    private CompletableFuture<ProviderResult> fetchModrinthStatusAsync(Http http) {
        if (!isConfigured(modrinthSlug)) {
            return CompletableFuture.completedFuture(errorResult(Provider.MODRINTH, "No project slug configured"));
        }
        return fetchJsonAsync("https://api.modrinth.com/v2/project/" + modrinthSlug + "/version", http)
                .thenApply(this::selectModrinthVersion)
                .thenApply(selected -> {
                    if (selected == null) {
                        return noEligibleVersionResult(Provider.MODRINTH);
                    }
                    String latest = usableVersionName(selected.json(), "version_number");
                    return new ProviderResult(Provider.MODRINTH, latest, providerBaseUrl(Provider.MODRINTH), null,
                            selected.json(), selected.compatibilityUnknown());
                })
                .exceptionally(ex -> failedResult(Provider.MODRINTH, ex, http));
    }

    private CompletableFuture<ProviderResult> fetchHangarStatusAsync(Http http) {
        if (!isConfigured(hangarNamespace)) {
            return CompletableFuture.completedFuture(errorResult(Provider.HANGAR, "No project namespace configured"));
        }
        return fetchHangarVersionsAsync(url -> fetchJsonAsync(url, http), hangarNamespace, 0, new JsonArray())
                .thenApply(this::selectHangarFromArray)
                .thenApply(selected -> {
                    if (selected == null) {
                        return noEligibleVersionResult(Provider.HANGAR);
                    }
                    String latest = usableVersionName(selected.json(), "name");
                    return new ProviderResult(Provider.HANGAR, latest, providerBaseUrl(Provider.HANGAR), null,
                            selected.json(), selected.compatibilityUnknown());
                })
                .exceptionally(ex -> failedResult(Provider.HANGAR, ex, http));
    }

    /**
     * Reads Hangar's version list page by page, up to {@link #MAX_HANGAR_PAGES}
     * pages. Package-private for UpdateCheckerFixtureTest.
     */
    static CompletableFuture<JsonArray> fetchHangarVersionsAsync(Function<String, CompletableFuture<JsonElement>> fetch,
            String namespace, int page, JsonArray collected) {
        String url = "https://hangar.papermc.io/api/v1/projects/" + namespace + "/versions?limit=" + HANGAR_PAGE_SIZE
                + "&offset=" + page * HANGAR_PAGE_SIZE;
        return fetch.apply(url).thenCompose(json -> {
            JsonArray entries = hangarEntries(json);
            collected.addAll(entries);
            int read = (page + 1) * HANGAR_PAGE_SIZE;
            int total = hangarTotal(json);
            boolean more = total >= 0 ? read < total : entries.size() >= HANGAR_PAGE_SIZE;
            if (!more || page + 1 >= MAX_HANGAR_PAGES)
                return CompletableFuture.completedFuture(collected);
            return fetchHangarVersionsAsync(fetch, namespace, page + 1, collected);
        });
    }

    private static JsonArray hangarEntries(JsonElement el) {
        if (el.isJsonArray()) {
            return el.getAsJsonArray();
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("result") && obj.get("result").isJsonArray()) {
                return obj.get("result").getAsJsonArray();
            }
        }
        throw new IllegalStateException("Unexpected Hangar response format");
    }

    // The number of versions Hangar has in total, or -1 if the page doesn't say.
    private static int hangarTotal(JsonElement el) {
        if (!el.isJsonObject() || !el.getAsJsonObject().has("pagination"))
            return -1;
        JsonElement pagination = el.getAsJsonObject().get("pagination");
        if (!pagination.isJsonObject())
            return -1;
        JsonElement count = pagination.getAsJsonObject().get("count");
        try {
            return count != null && count.isJsonPrimitive() ? count.getAsInt() : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private ProviderResult failedResult(Provider provider, Throwable failure, Http http) {
        String reason = describeFailure(failure);
        if (logFailures && !http.stopped()) {
            plugin.getLogger().warning(provider.displayName() + " update check failed: " + reason);
        }
        return errorResult(provider, reason);
    }

    /**
     * A short reason for a failed check that admins can read, like "HTTP 404" or
     * "timeout". Package-private for UpdateCheckerTest.
     */
    static String describeFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
            return "timeout";
        }
        if (cause instanceof CancellationException) {
            return "cancelled";
        }
        if (message.startsWith("HTTP ")) {
            return message;
        }
        if (message.startsWith("Response exceeded")) {
            return "response over 1 MiB";
        }
        if (cause instanceof JsonParseException || cause instanceof IllegalStateException
                || cause instanceof UnsupportedOperationException) {
            return "unexpected response";
        }
        if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
            return "no connection";
        }
        return cause instanceof IOException ? "connection error" : "unexpected error";
    }

    private ProviderResult errorResult(Provider provider, String message) {
        return new ProviderResult(provider, null, providerBaseUrl(provider), message, null, false);
    }

    // A successful check that found nothing installable (e.g. no build for this
    // Minecraft version yet) is not an error: success() with a null latestVersion.
    private ProviderResult noEligibleVersionResult(Provider provider) {
        return new ProviderResult(provider, null, providerBaseUrl(provider), null, null, false);
    }

    static String findNewestAvailableVersion(String current, List<ProviderResult> providers) {
        String latest = null;
        for (ProviderResult result : providers) {
            String candidate = result.latestVersion();
            if (candidate == null)
                continue;
            if (!VersionComparator.isGreater(candidate, current))
                continue;
            if (latest == null || VersionComparator.isGreater(candidate, latest)) {
                latest = candidate;
            }
        }
        return latest;
    }

    /**
     * Allows only letters, digits, underscores and hyphens, so a slug can't smuggle
     * extra path segments into the request URL. Invalid values are logged and
     * treated as "not configured" instead of failing startup.
     */
    static String sanitizeSlug(Logger logger, String slug) {
        if (slug == null || slug.isBlank()) {
            return slug;
        }
        String trimmed = slug.trim();
        if (!MODRINTH_SLUG_PATTERN.matcher(trimmed).matches()) {
            logger.warning("UpdateChecker: ignoring invalid Modrinth slug (unexpected characters): " + trimmed);
            return null;
        }
        return trimmed;
    }

    /**
     * Same idea as {@link #sanitizeSlug}, for Hangar's {@code User/Project}
     * namespace format.
     */
    static String sanitizeNamespace(Logger logger, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return namespace;
        }
        String trimmed = namespace.trim();
        if (!HANGAR_NAMESPACE_PATTERN.matcher(trimmed).matches()) {
            logger.warning("UpdateChecker: ignoring invalid Hangar namespace (expected User/Project): " + trimmed);
            return null;
        }
        return trimmed;
    }

    /**
     * Fetches and parses JSON with guardrails for a third-party HTTP API: HTTPS
     * only, no redirects, an explicit Accept header, a byte cap and a time limit on
     * the whole response. No thread waits on the network meanwhile.
     */
    CompletableFuture<JsonElement> fetchJsonAsync(String urlString, Http http) {
        URI uri = URI.create(urlString);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Refusing non-HTTPS update endpoint"));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(4))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            exchange = http.client().sendAsync(request, info -> new LimitedBody(MAX_RESPONSE_BYTES));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<JsonElement> json = exchange
                .thenApplyAsync(response -> parseJsonResponse(response.statusCode(), response.body()), http.executor());
        // Cancelling the exchange on timeout closes the connection.
        CompletableFuture.delayedExecutor(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS, http.executor()).execute(() -> {
            if (json.completeExceptionally(new TimeoutException(
                    "No complete response within " + RESPONSE_TIMEOUT_SECONDS + " seconds"))) {
                exchange.cancel(true);
            }
        });
        return json;
    }

    // Accepts only status 200; LimitedBody has already capped the body's size.
    static JsonElement parseJsonResponse(int statusCode, byte[] body) {
        if (statusCode != 200) {
            throw new IllegalStateException("HTTP " + statusCode);
        }
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Collects a response body as it arrives, without a waiting thread, and fails
     * once it passes {@code limit} bytes; the cancel then closes the connection.
     */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBody(long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + (long) buffer.remaining() > limit) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Response exceeded " + limit + " bytes"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    /**
     * Picks the highest eligible version out of a Modrinth version list, or
     * {@code null} if none qualifies. Package-private for UpdateCheckerFixtureTest.
     */
    SelectedVersion selectModrinthVersion(JsonElement el) {
        JsonArray entries = el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
        return selectHighest(entries, "version_number", this::isModrinthPrerelease, this::modrinthCompatibility);
    }

    // The highest version by VersionComparator, not the first in the provider's
    // order; one without compatibility data only if nothing matches.
    private SelectedVersion selectHighest(JsonArray entries, String nameKey, Predicate<JsonObject> prerelease,
            Function<JsonObject, Compatibility> compatibility) {
        JsonObject best = null;
        JsonObject bestUnknown = null;
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject())
                continue;
            JsonObject version = entry.getAsJsonObject();
            String name = usableVersionName(version, nameKey);
            if (name == null || !includePrereleases && prerelease.test(version))
                continue;
            Compatibility match = compatibility.apply(version);
            if (match == Compatibility.MATCH
                    && (best == null || VersionComparator.isGreater(name, usableVersionName(best, nameKey)))) {
                best = version;
            } else if (match == Compatibility.UNKNOWN && (bestUnknown == null
                    || VersionComparator.isGreater(name, usableVersionName(bestUnknown, nameKey)))) {
                bestUnknown = version;
            }
        }
        if (best != null) {
            return new SelectedVersion(best, false);
        }
        return bestUnknown == null ? null : new SelectedVersion(bestUnknown, true);
    }

    private static String versionName(JsonObject version, String key) {
        JsonElement name = version.get(key);
        return name != null && name.isJsonPrimitive() ? name.getAsString() : null;
    }

    // The entry's version name if the checker may report it, else null (the entry
    // is skipped).
    static String usableVersionName(JsonObject version, String key) {
        String name = versionName(version, key);
        return name != null && name.length() <= MAX_VERSION_NAME && VERSION_NAME.matcher(name).matches() ? name : null;
    }

    /**
     * Classifies a Modrinth version entry using only the documented
     * {@code game_versions} field, never a raw-string fallback. A version missing
     * that field is {@code UNKNOWN} rather than treated as compatible.
     */
    private Compatibility modrinthCompatibility(JsonObject version) {
        if (!hasPaperLoader(version)) {
            return Compatibility.MISMATCH;
        }
        if (!filterByServerVersion || minecraftVersionLower == null) {
            return Compatibility.MATCH;
        }
        JsonElement versions = version.get("game_versions");
        if (versions == null || !versions.isJsonArray() || versions.getAsJsonArray().isEmpty()) {
            return Compatibility.UNKNOWN;
        }
        return matchesModrinthVersions(version) ? Compatibility.MATCH : Compatibility.MISMATCH;
    }

    /**
     * Picks the highest eligible version out of a Hangar version list, or
     * {@code null} if none qualifies. Package-private for UpdateCheckerFixtureTest.
     */
    SelectedVersion selectHangarVersion(JsonElement el) {
        return selectHangarFromArray(hangarEntries(el));
    }

    private SelectedVersion selectHangarFromArray(JsonArray arr) {
        return selectHighest(arr, "name", this::isHangarPrerelease, this::hangarCompatibility);
    }

    /**
     * Classifies a Hangar version by its {@code platformDependencies.PAPER} game
     * version list. A version without Paper data is {@code UNKNOWN}.
     */
    private Compatibility hangarCompatibility(JsonObject version) {
        if (!hasPaperPlatform(version)) {
            return Compatibility.MISMATCH;
        }
        if (!filterByServerVersion || minecraftVersionLower == null) {
            return Compatibility.MATCH;
        }
        JsonArray paperVersions = hangarPaperVersions(version);
        if (paperVersions == null || paperVersions.isEmpty()) {
            return Compatibility.UNKNOWN;
        }
        return matchesVersionArray(paperVersions) ? Compatibility.MATCH : Compatibility.MISMATCH;
    }

    // Hangar maps each platform to its supported game versions, e.g.
    // "platformDependencies": {"PAPER": ["26.2"]}.
    private JsonArray hangarPaperVersions(JsonObject version) {
        JsonElement deps = version.get("platformDependencies");
        if (deps == null || !deps.isJsonObject()) {
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : deps.getAsJsonObject().entrySet()) {
            if ("PAPER".equalsIgnoreCase(entry.getKey()) && entry.getValue().isJsonArray()) {
                return entry.getValue().getAsJsonArray();
            }
        }
        return null;
    }

    // Without loader data a build may well run on Paper.
    private static boolean hasPaperLoader(JsonObject version) {
        JsonElement loaders = version.get("loaders");
        if (loaders == null || !loaders.isJsonArray() || loaders.getAsJsonArray().isEmpty()) {
            return true;
        }
        for (JsonElement loader : loaders.getAsJsonArray()) {
            if (loader.isJsonPrimitive() && PAPER_LOADERS.contains(loader.getAsString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPaperPlatform(JsonObject version) {
        JsonElement deps = version.get("platformDependencies");
        if (deps == null || !deps.isJsonObject() || deps.getAsJsonObject().isEmpty()) {
            return true;
        }
        return deps.getAsJsonObject().keySet().stream().anyMatch("PAPER"::equalsIgnoreCase);
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
                if (version.get("prerelease").getAsBoolean())
                    return true;
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

    // A channel flagged Unstable holds prereleases, the Release channel releases;
    // any other channel only if its name or the version's own reads like one, as
    // Hangar leaves the flag off channels like Snapshot unless the author sets it.
    private boolean isHangarPrerelease(JsonObject version) {
        JsonElement channelEl = version.get("channel");
        String channelName = null;
        if (channelEl != null && channelEl.isJsonObject()) {
            JsonObject channel = channelEl.getAsJsonObject();
            JsonElement flags = channel.get("flags");
            if (flags != null && flags.isJsonArray()) {
                for (JsonElement flag : flags.getAsJsonArray()) {
                    if (flag.isJsonPrimitive() && "UNSTABLE".equalsIgnoreCase(flag.getAsString()))
                        return true;
                }
            }
            channelName = versionName(channel, "name");
        } else if (channelEl != null && channelEl.isJsonPrimitive()) {
            channelName = channelEl.getAsString();
        }
        if (channelName == null || "release".equalsIgnoreCase(channelName)) {
            return false;
        }
        String name = versionName(version, "name");
        return isPrereleaseChannelName(channelName) || name != null && VersionComparator.isPrerelease(name);
    }

    static boolean isPrereleaseChannelName(String channelName) {
        for (String word : channelName.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (PRERELEASE_CHANNEL_TOKENS.contains(word) || PRERELEASE_CHANNEL_WORDS.stream().anyMatch(word::startsWith))
                return true;
        }
        return false;
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
