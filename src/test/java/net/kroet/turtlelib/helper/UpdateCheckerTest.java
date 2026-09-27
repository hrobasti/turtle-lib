package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.UpdateChecker.Provider;
import net.kroet.turtlelib.helper.UpdateChecker.ProviderResult;
import org.junit.jupiter.api.Test;

/**
 * Version choice, input checks and response handling of {@link UpdateChecker};
 * nothing here opens a network connection.
 */
class UpdateCheckerTest {

    private static final int MAX_BYTES = 1_048_576;

    private final List<LogRecord> records = new ArrayList<>();
    private final Logger logger = capturingLogger(records);

    private static ProviderResult found(Provider provider, String version) {
        return new ProviderResult(provider, version, "https://example.invalid", null, null, false);
    }

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // Feeds data in 8 KiB buffers, like the HTTP client does, and records a cancel.
    private static CompletableFuture<byte[]> receive(byte[] data, AtomicBoolean cancelled) {
        UpdateChecker.LimitedBody body = new UpdateChecker.LimitedBody(MAX_BYTES);
        body.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
        for (int offset = 0; offset < data.length; offset += 8192) {
            body.onNext(List.of(ByteBuffer.wrap(data, offset, Math.min(8192, data.length - offset))));
        }
        body.onComplete();
        return body.getBody().toCompletableFuture();
    }

    @Test
    void newestVersionAboveTheRunningOneWins() {
        assertEquals("2.2.0", UpdateChecker.findNewestAvailableVersion("2.0.0",
                List.of(found(Provider.MODRINTH, "2.1.0"), found(Provider.HANGAR, "2.2.0"))));
        assertEquals("2.2.0", UpdateChecker.findNewestAvailableVersion("2.0.0",
                List.of(found(Provider.MODRINTH, "2.2.0"), found(Provider.HANGAR, "2.1.0"))));
    }

    @Test
    void failedProvidersAndVersionsThatAreNotNewerGiveNoUpdate() {
        ProviderResult failed = new ProviderResult(Provider.HANGAR, null, "https://example.invalid", "HTTP 500", null,
                false);

        assertNull(UpdateChecker.findNewestAvailableVersion("2.0.0",
                List.of(failed, found(Provider.MODRINTH, "2.0.0"), found(Provider.HANGAR, "1.9.9"))));
        assertNull(UpdateChecker.findNewestAvailableVersion("2.0.0", List.of()));
    }

    @Test
    void preReleaseCountsOnlyWhenItIsAboveTheRunningVersion() {
        assertEquals("2.1.0-beta.1", UpdateChecker.findNewestAvailableVersion("2.0.0",
                List.of(found(Provider.MODRINTH, "2.1.0-beta.1"))));
        assertEquals("2.1.0", UpdateChecker.findNewestAvailableVersion("2.0.0",
                List.of(found(Provider.MODRINTH, "2.1.0-beta.1"), found(Provider.HANGAR, "2.1.0"))));
        assertNull(UpdateChecker.findNewestAvailableVersion("2.1.0",
                List.of(found(Provider.MODRINTH, "2.1.0-rc.1"))));
    }

    @Test
    void validSlugAndNamespaceAreTrimmed() {
        assertEquals("timberella", UpdateChecker.sanitizeSlug(logger, " timberella "));
        assertEquals("a".repeat(64), UpdateChecker.sanitizeSlug(logger, "a".repeat(64)));
        assertEquals("hro_basti/Timberella", UpdateChecker.sanitizeNamespace(logger, " hro_basti/Timberella "));
        assertTrue(records.isEmpty());
    }

    @Test
    void blankSlugAndNamespaceMeanNotConfigured() {
        assertNull(UpdateChecker.sanitizeSlug(logger, null));
        assertEquals("  ", UpdateChecker.sanitizeSlug(logger, "  "));
        assertNull(UpdateChecker.sanitizeNamespace(logger, null));
        assertEquals("", UpdateChecker.sanitizeNamespace(logger, ""));
        assertTrue(records.isEmpty());
    }

    @Test
    void slugThatCouldChangeTheRequestPathIsIgnoredWithAWarning() {
        List<String> invalid = List.of("../evil", "a/b", "slug?x=1", "with space", "ümlaut", "a".repeat(65));

        for (String slug : invalid) {
            assertNull(UpdateChecker.sanitizeSlug(logger, slug), slug);
        }
        assertEquals(invalid.size(), records.size());
        assertEquals("UpdateChecker: ignoring invalid Modrinth slug (unexpected characters): ../evil",
                records.get(0).getMessage());
    }

    @Test
    void namespaceMustBeUserSlashProject() {
        List<String> invalid = List.of("Timberella", "a/b/c", "../a", "a/../b", "a/b?c=1", "/b", "a/");

        for (String namespace : invalid) {
            assertNull(UpdateChecker.sanitizeNamespace(logger, namespace), namespace);
        }
        assertEquals(invalid.size(), records.size());
        assertTrue(records.get(0).getMessage().startsWith("UpdateChecker: ignoring invalid Hangar namespace"));
    }

    @Test
    void okResponseIsParsed() {
        JsonElement json = UpdateChecker.parseJsonResponse(200, body("[{\"version_number\":\"2.0.0\"}]"));

        assertEquals("2.0.0", json.getAsJsonArray().get(0).getAsJsonObject().get("version_number").getAsString());
    }

    @Test
    void otherStatusCodesAreRejected() {
        for (int status : new int[]{204, 301, 404, 429, 500}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> UpdateChecker.parseJsonResponse(status, body("[]")));
            assertEquals("HTTP " + status, e.getMessage());
        }
    }

    @Test
    void responseOfExactlyOneMebibyteIsAccepted() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        byte[] json = body("\"" + "a".repeat(MAX_BYTES - 2) + "\"");

        byte[] received = receive(json, cancelled).get();

        assertEquals(MAX_BYTES, received.length);
        assertEquals(MAX_BYTES - 2, UpdateChecker.parseJsonResponse(200, received).getAsString().length());
        assertFalse(cancelled.get());
    }

    @Test
    void largerResponseIsRejectedAndTheTransferCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean();

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> receive(new byte[MAX_BYTES + 1], cancelled).get());

        assertInstanceOf(IOException.class, e.getCause());
        assertEquals("Response exceeded 1048576 bytes", e.getCause().getMessage());
        assertTrue(cancelled.get());
    }

    @Test
    void checksRunOnTheirOwnDaemonThreadsAndShutdownStopsThem() throws Exception {
        UpdateChecker.Http first = UpdateChecker.http();
        Thread worker = CompletableFuture.supplyAsync(Thread::currentThread, first.executor()).get();

        UpdateChecker.shutdown();

        assertEquals("turtle-lib-UpdateChecker", worker.getName());
        assertTrue(worker.isDaemon());
        assertTrue(first.stopped());
        UpdateChecker.Http second = UpdateChecker.http();
        assertNotSame(first, second);
        assertFalse(second.stopped());
        UpdateChecker.shutdown();
        UpdateChecker.shutdown();
    }

    @Test
    void plainHttpEndpointIsRefusedBeforeAnyRequest() {
        CompletableFuture<JsonElement> response = new UpdateChecker(false, false, null)
                .fetchJsonAsync("http://127.0.0.1:9/versions", null);

        ExecutionException e = assertThrows(ExecutionException.class, response::get);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals("Refusing non-HTTPS update endpoint", e.getCause().getMessage());
    }

    @Test
    void modrinthPrereleaseFlagAndVersionTypeAreSkippedUnlessPrereleasesAreWanted() {
        JsonElement versions = JsonParser.parseString("""
                [
                  {"version_number": "3.0.0", "prerelease": true},
                  {"version_number": "2.9.0", "version_type": "beta"},
                  {"version_number": "2.8.0", "version_type": "alpha"},
                  {"version_number": "2.7.0", "prerelease": false, "version_type": "release"}
                ]
                """);

        var releasesOnly = new UpdateChecker(false, false, null).selectModrinthVersion(versions);
        var withPrereleases = new UpdateChecker(true, false, null).selectModrinthVersion(versions);

        assertEquals("2.7.0", releasesOnly.json().get("version_number").getAsString());
        assertEquals("3.0.0", withPrereleases.json().get("version_number").getAsString());
    }

    @Test
    void modrinthEntryWithoutTypeCountsAsARelease() {
        JsonElement versions = JsonParser.parseString("[{\"version_number\": \"1.5.0\"}]");

        var selected = new UpdateChecker(false, false, null).selectModrinthVersion(versions);

        assertEquals("1.5.0", selected.json().get("version_number").getAsString());
    }

    private static Logger capturingLogger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @Test
    void checkingBothSkipsAProviderWithoutAnId() {
        assertEquals(List.of(Provider.HANGAR), UpdateChecker.providersToCheck(0, " ", "User/Project"));
        assertEquals(List.of(Provider.MODRINTH), UpdateChecker.providersToCheck(0, "slug", null));
        assertEquals(List.of(), UpdateChecker.providersToCheck(0, null, ""));
        assertEquals(List.of(Provider.MODRINTH, Provider.HANGAR),
                UpdateChecker.providersToCheck(0, "slug", "User/Project"));
        // Checking one provider only, a missing ID still shows as its error result.
        assertEquals(List.of(Provider.MODRINTH), UpdateChecker.providersToCheck(1, null, "User/Project"));
        assertEquals(List.of(Provider.HANGAR), UpdateChecker.providersToCheck(2, "slug", null));
    }

    @Test
    void providerModeOutsideZeroToTwoIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateChecker(null, 3, false, "slug", "User/Project"));
        assertThrows(IllegalArgumentException.class, () -> new UpdateChecker(null, -1, false, "slug", "User/Project"));
    }

    // What a plugin shows admins in its own translated line.
    @Test
    void failureIsDescribedShortly() {
        assertEquals("HTTP 404", UpdateChecker.describeFailure(
                new CompletionException(new IllegalStateException("HTTP 404"))));
        assertEquals("timeout", UpdateChecker.describeFailure(new CompletionException(new TimeoutException("x"))));
        assertEquals("timeout", UpdateChecker.describeFailure(new HttpConnectTimeoutException("connect")));
        assertEquals("response over 1 MiB", UpdateChecker.describeFailure(
                new CompletionException(new IOException("Response exceeded 1048576 bytes"))));
        assertEquals("unexpected response", UpdateChecker.describeFailure(
                new CompletionException(new IllegalStateException("Unexpected Hangar response format"))));
        assertEquals("unexpected response", UpdateChecker.describeFailure(new JsonSyntaxException("bad")));
        assertEquals("no connection", UpdateChecker.describeFailure(
                new CompletionException(new ConnectException("refused"))));
        assertEquals("connection error", UpdateChecker.describeFailure(new IOException("reset")));
        assertEquals("unexpected error", UpdateChecker.describeFailure(new NullPointerException()));
    }
}
