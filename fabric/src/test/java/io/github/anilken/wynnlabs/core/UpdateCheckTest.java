package io.github.anilken.wynnlabs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The update check's version order and its reading of GitHub's answer, with no network. */
class UpdateCheckTest {

    @Test
    void newerComparesNumbersAsNumbers() {
        assertTrue(UpdateCheck.newer("v0.3.0", "0.2.0"));
        assertTrue(UpdateCheck.newer("v0.10.0", "0.9.9"));
        assertTrue(UpdateCheck.newer("1.0.0", "v0.99.99"));
        assertTrue(UpdateCheck.newer("v0.2.1", "0.2"));
        assertFalse(UpdateCheck.newer("v0.2.0", "0.2.0"));
        assertFalse(UpdateCheck.newer("v0.2.0", "0.3.0"));
        assertFalse(UpdateCheck.newer("V0.9.0", "0.10.0"));
    }

    @Test
    void preReleasesComeBeforeTheirReleaseAndBuildMetadataIsIgnored() {
        assertTrue(UpdateCheck.newer("v1.0.0", "1.0.0-beta.2"));
        assertTrue(UpdateCheck.newer("v1.0.0-beta.10", "1.0.0-beta.2"));
        assertTrue(UpdateCheck.newer("v1.0.0-rc.1", "1.0.0-beta"));
        assertFalse(UpdateCheck.newer("v1.0.0-beta", "1.0.0"));
        assertEquals(0, UpdateCheck.compare("v0.2.0+mc1.21.11", "0.2.0"));
    }

    @Test
    void notAVersionIsNeverNewer() {
        assertFalse(UpdateCheck.newer("latest", "0.2.0"));
        assertFalse(UpdateCheck.newer("v0.3.0", "${version}"));
        assertFalse(UpdateCheck.newer("", "0.2.0"));
        assertNull(UpdateCheck.compare("v1.2.3.4", "1.2.3"));
        assertNull(UpdateCheck.compare("v1..3", "1.2.3"));
    }

    @Test
    void devAndUnknownVersionsSkipTheCheck() {
        assertTrue(UpdateCheck.checkable("0.2.0"));
        assertTrue(UpdateCheck.checkable("v0.1.0"));
        assertFalse(UpdateCheck.checkable("0.2.0-dev"));
        assertFalse(UpdateCheck.checkable("0.2.1+dev"), "a local build never updates itself");
        assertFalse(UpdateCheck.newer("v0.2.1", "0.2.1+dev"));
        assertTrue(UpdateCheck.newer("v0.2.2", "0.2.1+dev"));
        assertFalse(UpdateCheck.checkable("0.3.0-SNAPSHOT"));
        assertFalse(UpdateCheck.checkable("0.0.0"));
        assertFalse(UpdateCheck.checkable("${version}"));
        assertFalse(UpdateCheck.checkable(""));
        assertFalse(UpdateCheck.checkable(null));
    }

    @Test
    void readsTagAndPageFromAReleaseAnswer() {
        String json = """
                {
                  "url": "https://api.github.com/repos/anilken/wynnlabs/releases/1",
                  "html_url": "https://github.com/anilken/wynnlabs/releases/tag/v0.3.0",
                  "id": 1,
                  "tag_name": "v0.3.0",
                  "name": "v0.3.0",
                  "draft": false,
                  "prerelease": false,
                  "published_at": "2026-10-01T12:00:00Z",
                  "assets": [{"name": "wynnlabs-fabric-0.3.0.jar"}],
                  "body": "## Changes"
                }
                """;
        UpdateCheck.Release r = UpdateCheck.parse(json);
        assertEquals("v0.3.0", r.tag());
        assertEquals("https://github.com/anilken/wynnlabs/releases/tag/v0.3.0", r.url());
        assertEquals(java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli(), r.published());
        assertEquals(0, UpdateCheck.parse(json.replace("2026-10-01T12:00:00Z", "yesterday")).published());
    }

    // ---- the About page's state and "Check again" ----

    private static final String NEWER = "{\"tag_name\": \"v0.3.0\", \"html_url\": \"https://github.com/anilken/wynnlabs/releases/tag/v0.3.0\","
            + " \"published_at\": \"2026-10-01T12:00:00Z\"}";

    /** A fresh run of jar {@code version} with the setting {@code on}; returns how many requests started. */
    private static int[] fresh(String version, boolean on) {
        com.google.gson.JsonObject s = new com.google.gson.JsonObject();
        s.addProperty("updateCheck", on);
        Settings.use(s);
        UpdateCheck.reset();
        UpdateCheck.version = () -> version;
        int[] started = {0};
        UpdateCheck.starter = job -> started[0]++;     // no network: the answer is given by hand
        return started;
    }

    @org.junit.jupiter.api.AfterEach
    void restore() {
        UpdateCheck.reset();
        UpdateDownload.reset();
        UpdatePrefs.reset();
        UpdatePrefs.folder = () -> Dirs.config().resolve("updates");
        UpdateDownload.starter = job -> { };
        Settings.use(new com.google.gson.JsonObject());
    }

    @Test
    void checkAgainAsksOnceAndWaitsTenSeconds() {
        int[] started = fresh("0.2.0", true);
        assertEquals(UpdateCheck.Status.IDLE, UpdateCheck.state().status());
        assertTrue(UpdateCheck.checkNow(100_000));
        assertEquals(UpdateCheck.Status.CHECKING, UpdateCheck.state().status());
        assertFalse(UpdateCheck.checkNow(101_000), "one request at a time");
        UpdateCheck.finish("0.2.0", 200, NEWER, false, 102_000);
        UpdateCheck.State s = UpdateCheck.state();
        assertEquals(UpdateCheck.Status.NEWER, s.status());
        assertEquals("v0.3.0", s.latest());
        assertEquals("https://github.com/anilken/wynnlabs/releases/tag/v0.3.0", s.url());
        assertEquals(java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli(), s.published());
        assertEquals(102_000, s.checkedAt());
        assertFalse(UpdateCheck.checkNow(109_999), "under ten seconds after the last press");
        assertTrue(UpdateCheck.checkNow(110_000));
        assertEquals(2, started[0]);
        UpdateCheck.finish("0.2.0", 200, NEWER.replace("v0.3.0", "v0.2.0"), false, 111_000);
        assertEquals(UpdateCheck.Status.UP_TO_DATE, UpdateCheck.state().status());
    }

    @Test
    void failuresSayWhy() {
        fresh("0.2.0", true);
        UpdateCheck.finish("0.2.0", 503, "", false, 1);
        assertEquals(UpdateCheck.Reason.NO_ANSWER, UpdateCheck.state().reason());
        UpdateCheck.finish("0.2.0", 200, "{\"message\": \"rate limited\"}", false, 2);
        assertEquals(UpdateCheck.Status.FAILED, UpdateCheck.state().status());
        assertEquals(UpdateCheck.Reason.NO_ANSWER, UpdateCheck.state().reason());
        assertEquals(UpdateCheck.Reason.OFFLINE, UpdateCheck.reasonOf(new java.net.ConnectException()));
        assertEquals(UpdateCheck.Reason.OFFLINE,
                UpdateCheck.reasonOf(new java.io.IOException("lookup", new java.net.UnknownHostException("api.github.com"))));
        assertEquals(UpdateCheck.Reason.NO_ANSWER, UpdateCheck.reasonOf(new java.net.http.HttpTimeoutException("slow")));
    }

    @Test
    void offOrADevBuildAsksNothing() {
        int[] started = fresh("0.2.0", false);
        assertEquals(UpdateCheck.Reason.OFF, UpdateCheck.state().reason());
        assertFalse(UpdateCheck.checkNow(100_000));
        assertEquals(UpdateCheck.Reason.OFF, UpdateCheck.state().reason());
        started = fresh("0.0.0-dev", true);
        assertEquals(UpdateCheck.Reason.DEV, UpdateCheck.state().reason());
        assertFalse(UpdateCheck.checkNow(100_000));
        assertEquals(0, started[0]);
    }

    @Test
    void badAnswersGiveNoRelease() {
        assertNull(UpdateCheck.parse("not json {"));
        assertNull(UpdateCheck.parse("[]"));
        assertNull(UpdateCheck.parse("{\"message\": \"API rate limit exceeded\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": \"v0.3.0\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": \"v0.3.0\", \"html_url\": \"https://evil.example/x\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": 3, \"html_url\": \"https://github.com/anilken/wynnlabs\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": \"v0.3.0\", \"html_url\": \"https://github.com/a/b\", \"draft\": true}"));
    }

    // ---- the updater: notes, assets, the first join, the chat line, ignoring ----

    private static final String WITH_ASSETS = """
            {"tag_name": "v0.3.0", "html_url": "https://github.com/anilken/Wynnlabs/releases/tag/v0.3.0",
             "draft": false, "prerelease": false, "body": "### Bug Fixes\\n\\n* one",
             "assets": [
               {"name": "wynnlabs-fabric-0.3.0.jar", "size": 4320605, "state": "uploaded",
                "digest": "sha256:d4d3f04e5cb10bf4601652ca12cef4ab785d44d49a4f918af5c1c01cd126a5ad",
                "browser_download_url": "https://github.com/anilken/Wynnlabs/releases/download/v0.3.0/wynnlabs-fabric-0.3.0.jar"},
               {"name": "wynnlabs-neoforge-0.3.0.jar", "size": 4329012, "state": "uploaded",
                "digest": "sha256:8ed4b3b425e7a8c7f986ba7c64a97e24d515eb7bbb6604905a6bb85cf2f144c4",
                "browser_download_url": "https://github.com/anilken/Wynnlabs/releases/download/v0.3.0/wynnlabs-neoforge-0.3.0.jar"}
             ]}
            """;

    @Test
    void readsNotesAndAssetsAndPicksThisGamesJar() {
        UpdateCheck.Release r = UpdateCheck.parse(WITH_ASSETS);
        assertEquals("### Bug Fixes\n\n* one", r.notes());
        assertEquals(2, r.assets().size());
        assertNull(UpdateCheck.parse(WITH_ASSETS.replace("\"prerelease\": false", "\"prerelease\": true")));
        fresh("0.2.0", true);
        UpdateAsset.loader = () -> "neoforge";
        UpdateAsset.minecraft = () -> "1.21.11";
        try {
            UpdateCheck.finish("0.2.0", 200, WITH_ASSETS, false, 1);
            UpdateCheck.State s = UpdateCheck.state();
            assertEquals("wynnlabs-neoforge-0.3.0.jar", s.asset().name());
            assertEquals("### Bug Fixes\n\n* one", s.notes());
        } finally {
            UpdateAsset.loader = () -> io.github.anilken.wynnlabs.core.Platform.get().loaderName();
        }
    }

    @Test
    void theFirstJoinAsksOnlyWhenTheReminderOrAutoUpdateIsOn() {
        com.google.gson.JsonObject both = new com.google.gson.JsonObject();
        both.addProperty("updateCheck", false);
        both.addProperty("autoUpdate", false);
        int[] started = fresh("0.2.0", false);
        Settings.use(both);
        assertFalse(UpdateCheck.firstJoin(1));
        assertEquals(0, started[0]);
        both.addProperty("autoUpdate", true);
        Settings.use(both);
        assertTrue(UpdateCheck.firstJoin(2));
        assertFalse(UpdateCheck.firstJoin(3), "once a run");
        assertEquals(1, started[0]);
    }

    @Test
    void autoUpdateDownloadsInsteadOfTheReminder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        fresh("0.2.0", true);
        com.google.gson.JsonObject on = new com.google.gson.JsonObject();
        on.addProperty("autoUpdate", true);
        Settings.use(on);
        UpdatePrefs.folder = () -> tmp;
        UpdateAsset.loader = () -> "fabric";
        UpdateAsset.minecraft = () -> "1.21.11";
        java.nio.file.Path jar = java.nio.file.Files.writeString(tmp.resolve("wynnlabs.jar"), "old");
        UpdateDownload.ownJar = () -> java.util.Optional.of(jar);
        int[] downloads = {0};
        UpdateDownload.starter = job -> downloads[0]++;
        try {
            UpdateCheck.finish("0.2.0", 200, WITH_ASSETS, true, 1);
            assertEquals(1, downloads[0], "the download started");
            assertEquals(UpdateDownload.Phase.DOWNLOADING, UpdateDownload.phase());
            // ignored: neither a download nor a line
            UpdateDownload.reset();
            UpdatePrefs.ignore("v0.3.0");
            UpdateCheck.finish("0.2.0", 200, WITH_ASSETS, true, 2);
            assertEquals(1, downloads[0]);
        } finally {
            UpdateAsset.loader = () -> io.github.anilken.wynnlabs.core.Platform.get().loaderName();
            UpdateDownload.ownJar = UpdateDownload::findOwnJar;
        }
    }

    @Test
    void ignoringAVersionKeepsLaterOnesAnnounced(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        UpdatePrefs.folder = () -> tmp;
        UpdatePrefs.reset();
        assertFalse(UpdatePrefs.silenced("v0.2.5"));
        UpdatePrefs.ignore("v0.2.5");
        UpdatePrefs.reset();                    // a new run reads the stored choice
        assertTrue(UpdatePrefs.silenced("v0.2.5"));
        assertFalse(UpdatePrefs.silenced("v0.2.6"));
        UpdatePrefs.ignoreNow();
        assertTrue(UpdatePrefs.silenced("v0.2.6"), "ignore now: everything, this run");
    }

    @Test
    void theChatLineHasOneLinkThatUpdates() {
        UpdateCheck.Release r = new UpdateCheck.Release("v0.3.0", "https://github.com/anilken/Wynnlabs/releases/tag/v0.3.0");
        java.util.List<net.minecraft.network.chat.ClickEvent> clicks = new java.util.ArrayList<>();
        UpdateCheck.line(r, "0.2.0").visit((style, text) -> {
            if (style.getClickEvent() != null && !text.isEmpty()) {
                clicks.add(style.getClickEvent());
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        assertEquals(java.util.List.of(new net.minecraft.network.chat.ClickEvent.RunCommand("/wynnlabs-update")), clicks);
    }

    @Test
    void updateInChatDownloadsAtOnceAndSaysSo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        fresh("0.2.0", true);
        java.util.List<String> said = new java.util.ArrayList<>();
        UpdateCheck.updateFromChat(c -> said.add(c.getString()));
        assertEquals(java.util.List.of(L.c("wynnlabs.wear.prefix").getString() + L.t("wynnlabs.update.none_known")), said);

        UpdateAsset.loader = () -> "fabric";
        UpdateAsset.minecraft = () -> "1.21.11";
        java.nio.file.Path jar = java.nio.file.Files.writeString(tmp.resolve("wynnlabs.jar"), "old");
        UpdateDownload.ownJar = () -> java.util.Optional.of(jar);
        int[] downloads = {0};
        UpdateDownload.starter = job -> downloads[0]++;
        try {
            UpdateCheck.finish("0.2.0", 200, WITH_ASSETS, false, 1);
            said.clear();
            UpdateCheck.updateFromChat(c -> said.add(c.getString()));
            assertEquals(1, downloads[0], "no window: the download started");
            assertTrue(said.get(0).endsWith(L.t("wynnlabs.update.downloading", "v0.3.0")));
            UpdateCheck.updateFromChat(c -> said.add(c.getString()));
            assertEquals(1, downloads[0], "pressed again while it runs");

            UpdateDownload.preview(UpdateDownload.Phase.READY, 1, UpdateDownload.Failure.NONE, "v0.3.0");
            said.clear();
            UpdateCheck.updateFromChat(c -> said.add(c.getString()));
            assertTrue(said.get(0).endsWith(L.t("wynnlabs.update.downloaded", "v0.3.0")));
            assertEquals(1, downloads[0]);
        } finally {
            UpdateAsset.loader = () -> io.github.anilken.wynnlabs.core.Platform.get().loaderName();
            UpdateDownload.ownJar = UpdateDownload::findOwnJar;
        }
    }
}
