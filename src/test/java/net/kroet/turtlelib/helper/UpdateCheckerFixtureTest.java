package net.kroet.turtlelib.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Feeds Modrinth/Hangar API responses (hangar-result-wrapper.json is a trimmed
 * real response) straight into version selection - no network, no Plugin.
 */
class UpdateCheckerFixtureTest {

    private static JsonElement loadFixture(String name) {
        try (InputStream in = UpdateCheckerFixtureTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load fixture: " + name, e);
        }
    }

    @Test
    void modrinthPicksTheFirstVersionMatchingTheServerVersion() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");
        var selected = checker.selectModrinthVersion(loadFixture("modrinth-match.json"));

        assertEquals("1.8.0", selected.json().get("version_number").getAsString());
        assertFalse(selected.compatibilityUnknown());
    }

    @Test
    void modrinthFallsBackToAnUnlabeledVersionOnlyWhenNothingElseMatches() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");
        var selected = checker.selectModrinthVersion(loadFixture("modrinth-unknown-fallback.json"));

        // No game_versions field at all is treated as "unknown", not an automatic
        // match - it's only picked when no better candidate exists.
        assertEquals("1.9.0", selected.json().get("version_number").getAsString());
        assertTrue(selected.compatibilityUnknown());
    }

    @Test
    void modrinthReturnsNullWhenEveryVersionIsExplicitlyIncompatible() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");

        assertNull(checker.selectModrinthVersion(loadFixture("modrinth-no-match.json")));
    }

    @Test
    void hangarReturnsNullForAnEmptyVersionList() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");

        assertNull(checker.selectHangarVersion(JsonParser.parseString("{\"result\":[]}")));
    }

    @Test
    void hangarThrowsOnAnUnexpectedResponseShape() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");
        JsonElement unexpected = JsonParser.parseString("{\"error\":\"not found\"}");

        assertThrows(IllegalStateException.class, () -> checker.selectHangarVersion(unexpected));
    }

    @Test
    void hangarSkipsOtherGameVersionsAndPrereleases() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");
        var selected = checker.selectHangarVersion(loadFixture("hangar-array.json"));

        // 1.4.0 targets 26.4 only; 1.3.2 matches but is on the Snapshot channel.
        assertEquals("1.3.1", selected.json().get("name").getAsString());
        assertFalse(selected.compatibilityUnknown());
    }

    @Test
    void hangarMatchesAHotfixServerAgainstItsMinorSeries() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3.2");
        var selected = checker.selectHangarVersion(loadFixture("hangar-array.json"));

        assertEquals("1.3.1", selected.json().get("name").getAsString());
    }

    // 1.3.0 is a Velocity build, so it never counts; an entry without platform
    // data is only a fallback when nothing matches.
    @Test
    void hangarEntryWithoutPlatformDataIsOnlyAnUnknownFallback() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.5");

        assertNull(checker.selectHangarVersion(loadFixture("hangar-array.json")));
        var selected = checker.selectHangarVersion(JsonParser.parseString("""
                [{"name": "1.3.0", "channel": {"name": "Release"}, "platformDependencies": {"PAPER": ["26.4"]}},
                 {"name": "1.2.0", "channel": {"name": "Release"}}]
                """));
        assertEquals("1.2.0", selected.json().get("name").getAsString());
        assertTrue(selected.compatibilityUnknown());
    }

    @Test
    void realHangarResponseOffersNothingForAServerItWasNotPublishedFor() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");

        assertNull(checker.selectHangarVersion(loadFixture("hangar-result-wrapper.json")));
    }

    @Test
    void realHangarResponsePicksTheNewestVersionForTheServer() {
        JsonElement fixture = loadFixture("hangar-result-wrapper.json");

        var on262 = new UpdateChecker(false, true, "26.2").selectHangarVersion(fixture);
        var on2612 = new UpdateChecker(false, true, "26.1.2").selectHangarVersion(fixture);

        assertEquals("1.2.2", on262.json().get("name").getAsString());
        assertFalse(on262.compatibilityUnknown());
        assertEquals("1.2.1", on2612.json().get("name").getAsString());
    }

    @Test
    void serverVersionFilterIsSkippedWhenDisabled() {
        UpdateChecker checker = new UpdateChecker(false, false, null);
        var selected = checker.selectModrinthVersion(loadFixture("modrinth-no-match.json"));

        // With filterByServerVersion=false, the first eligible (non-prerelease) entry
        // wins regardless of game_versions.
        assertEquals("2.0.0", selected.json().get("version_number").getAsString());
    }

    // A backport uploaded after a newer release doesn't hide that release.
    @Test
    void eachProviderReportsItsHighestVersionNotItsNewestUpload() {
        UpdateChecker checker = new UpdateChecker(false, true, "26.3");

        var modrinth = checker.selectModrinthVersion(JsonParser.parseString("""
                [{"version_number": "1.9.1", "version_type": "release", "game_versions": ["26.3"]},
                 {"version_number": "2.0.0", "version_type": "release", "game_versions": ["26.3"]},
                 {"version_number": "1.9.0", "version_type": "release", "game_versions": ["26.3"]}]
                """));
        var hangar = checker.selectHangarVersion(JsonParser.parseString("""
                {"result": [
                 {"name": "1.9.1", "channel": {"name": "Release"}, "platformDependencies": {"PAPER": ["26.3"]}},
                 {"name": "2.0.0", "channel": {"name": "Release"}, "platformDependencies": {"PAPER": ["26.3"]}}]}
                """));
        var unknown = new UpdateChecker(false, true, "26.3").selectModrinthVersion(JsonParser.parseString("""
                [{"version_number": "1.9.1"}, {"version_number": "2.0.0"}]
                """));

        assertEquals("2.0.0", modrinth.json().get("version_number").getAsString());
        assertEquals("2.0.0", hangar.json().get("name").getAsString());
        assertEquals("2.0.0", unknown.json().get("version_number").getAsString());
        assertTrue(unknown.compatibilityUnknown());
    }

    // A forged answer could otherwise put fake log lines or terminal escapes into
    // the console and op chat.
    @Test
    void versionNamesWithOtherCharactersOrOverLengthAreSkipped() {
        UpdateChecker checker = new UpdateChecker(false, false, null);

        var modrinth = checker.selectModrinthVersion(JsonParser.parseString("""
                [{"version_number": "9.9.9\\n[12:00:00 INFO]: [Server] fake"},
                 {"version_number": "9.9.8\\u001b[31m"},
                 {"version_number": "9.9.7 beta"},
                 {"version_number": "9%s"},
                 {"version_number": "2.0.0"}]
                """.formatted("9".repeat(64))));
        var hangar = checker.selectHangarVersion(JsonParser.parseString("""
                [{"name": "3.0.0\\u00a7c", "channel": {"name": "Release"}},
                 {"name": "v2.1.0+build.7", "channel": {"name": "Release"}}]
                """));

        assertEquals("2.0.0", modrinth.json().get("version_number").getAsString());
        assertEquals("v2.1.0+build.7", hangar.json().get("name").getAsString());
    }

    @Test
    void usableVersionNamesAreShortAndUseVersionCharactersOnly() {
        for (String name : List.of("2.0.0", "v2.0.0-beta.1+build.5", "26.3_1", "a".repeat(64))) {
            assertEquals(name, UpdateChecker.usableVersionName(nameObject(name), "name"), name);
        }
        for (String name : List.of("", ".1", "-2.0", "2.0 0", "2.0.0é", "a".repeat(65), "2.0\t")) {
            assertNull(UpdateChecker.usableVersionName(nameObject(name), "name"), name);
        }
    }

    @Test
    void aHugeVersionNameDoesntSlowTheSelectionDown() {
        StringBuilder json = new StringBuilder("[{\"version_number\": \"9.").append("9.".repeat(50_000))
                .append("9\"}");
        for (int i = 0; i < 40_000; i++) {
            json.append(",{\"version_number\": \"1.0.").append(i).append("\"}");
        }
        JsonElement versions = JsonParser.parseString(json.append(']').toString());
        UpdateChecker checker = new UpdateChecker(false, false, null);

        var selected = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> checker.selectModrinthVersion(versions));

        assertEquals("1.0.39999", selected.json().get("version_number").getAsString());
    }

    private static JsonObject nameObject(String name) {
        JsonObject version = new JsonObject();
        version.addProperty("name", name);
        return version;
    }

    @Test
    void modrinthBuildsForOtherLoadersDoNotCount() {
        UpdateChecker checker = new UpdateChecker(false, false, null);
        String versions = """
                [{"version_number": "3.0.0", "loaders": ["fabric"]},
                 {"version_number": "2.1.0", "loaders": ["velocity"]},
                 {"version_number": "2.0.0", "loaders": ["fabric", "paper"]},
                 {"version_number": "1.9.0"}]
                """;

        assertEquals("2.0.0", checker.selectModrinthVersion(JsonParser.parseString(versions)).json()
                .get("version_number").getAsString());
        assertEquals("1.9.0", checker.selectModrinthVersion(JsonParser.parseString(
                versions.replace("\"fabric\", \"paper\"", "\"quilt\""))).json().get("version_number").getAsString());
        assertEquals("2.0.0", checker.selectModrinthVersion(JsonParser.parseString(
                versions.replace("\"fabric\", \"paper\"", "\"purpur\""))).json().get("version_number").getAsString());
    }

    // Hangar's Unstable flag marks prereleases whatever the channel is called;
    // without it, the name of the channel or of the version decides.
    @Test
    void hangarPrereleasesComeFromTheChannelFlagNameOrVersion() {
        UpdateChecker checker = new UpdateChecker(false, false, null);

        assertEquals("2.0.0", nameOf(checker, "Stable", "[]", "2.0.0"));
        assertEquals("2.0.0", nameOf(checker, "Latest", "[]", "2.0.0"));
        assertEquals("2.0.0", nameOf(checker, "Release", "[\"PINNED\"]", "2.0.0"));
        assertNull(nameOf(checker, "Latest", "[\"UNSTABLE\"]", "2.0.0"));
        assertNull(nameOf(checker, "Snapshot", "[]", "2.0.0"));
        assertNull(nameOf(checker, "Beta", null, "2.0.0"));
        assertNull(nameOf(checker, "Builds", "[]", "2.1.0-SNAPSHOT"));
        assertEquals("2.1.0-beta1", nameOf(new UpdateChecker(true, false, null), "Beta", null, "2.1.0-beta1"));
    }

    @Test
    void prereleaseChannelNamesAreMatchedByWord() {
        for (String name : List.of("Snapshot", "Beta", "Dev Builds", "Development", "RC", "pre-release", "Nightly",
                "Testing", "Release Candidate")) {
            assertTrue(UpdateChecker.isPrereleaseChannelName(name), name);
        }
        for (String name : List.of("Release", "Stable", "Latest", "Main", "Paper", "Presets")) {
            assertFalse(UpdateChecker.isPrereleaseChannelName(name), name);
        }
    }

    private static String nameOf(UpdateChecker checker, String channel, String flags, String version) {
        String flagsJson = flags == null ? "" : ", \"flags\": " + flags;
        var selected = checker.selectHangarVersion(JsonParser.parseString("[{\"name\": \"" + version
                + "\", \"channel\": {\"name\": \"" + channel + "\"" + flagsJson + "}}]"));
        return selected == null ? null : selected.json().get("name").getAsString();
    }

    @Test
    void hangarVersionsAreReadPageByPage() {
        List<String> urls = new ArrayList<>();
        JsonArray all = UpdateChecker.fetchHangarVersionsAsync(url -> {
            urls.add(url);
            int offset = Integer.parseInt(url.substring(url.lastIndexOf('=') + 1));
            return CompletableFuture.completedFuture(page(offset, Math.min(25, 30 - offset), 30));
        }, "User/Project", 0, new JsonArray()).join();

        assertEquals(30, all.size());
        assertEquals(List.of(
                "https://hangar.papermc.io/api/v1/projects/User/Project/versions?limit=25&offset=0",
                "https://hangar.papermc.io/api/v1/projects/User/Project/versions?limit=25&offset=25"), urls);
    }

    @Test
    void hangarPagingStopsAfterTheLastAllowedPage() {
        List<String> urls = new ArrayList<>();
        JsonArray all = UpdateChecker.fetchHangarVersionsAsync(url -> {
            urls.add(url);
            int offset = Integer.parseInt(url.substring(url.lastIndexOf('=') + 1));
            return CompletableFuture.completedFuture(page(offset, 25, 900));
        }, "User/Project", 0, new JsonArray()).join();

        assertEquals(UpdateChecker.MAX_HANGAR_PAGES, urls.size());
        assertEquals(UpdateChecker.MAX_HANGAR_PAGES * UpdateChecker.HANGAR_PAGE_SIZE, all.size());
    }

    private static JsonElement page(int offset, int size, int total) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < size; i++) {
            JsonObject version = new JsonObject();
            version.addProperty("name", "1.0." + (offset + i));
            result.add(version);
        }
        JsonObject pagination = new JsonObject();
        pagination.addProperty("count", total);
        pagination.addProperty("limit", 25);
        pagination.addProperty("offset", offset);
        JsonObject page = new JsonObject();
        page.add("pagination", pagination);
        page.add("result", result);
        return page;
    }
}
