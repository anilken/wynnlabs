package io.github.anilken.wynnlabs.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Once per game run, a few seconds after the player first shows up in a world, asks GitHub for
 * the newest release and, only when it is newer than this jar, says so in one chat line with
 * [Update] (download now, installed on exit), [Details] (the update window) and [GitHub] (the
 * release page). Settings {@code updateCheck} (the
 * reminder) and {@code autoUpdate} (download now, install on exit, no screen): while both are off
 * no request is made. A dev or unknown version skips the check. One request, on its own thread,
 * never retried; a failure is one log line. The line goes to this client's chat only, never to
 * the server. The release's jar for this game is picked by {@link UpdateAsset}; downloading and
 * installing are {@link UpdateDownload} and {@link UpdateInstall}.
 *
 * <p>The settings tab's About page reads {@link #state()} and may ask again with {@link #checkNow()}:
 * only on the player's press, never in a loop, and at most once every ten seconds.
 */
public final class UpdateCheck {

    static final String URL = "https://api.github.com/repos/anilken/wynnlabs/releases/latest";
    /** Ticks in a world before asking: about five seconds, so joining is not crowded. */
    private static final int DELAY_TICKS = 100;
    /** The shortest time between two requests (a press on "Check again" sooner does nothing). */
    static final long DEBOUNCE_MS = 10_000;

    /**
     * A release as GitHub describes it: its tag ("v0.3.0"), its page, when it came out (0:
     * unknown), its notes (markdown) and the jars attached to it that can be trusted.
     */
    record Release(String tag, String url, long published, String notes, List<UpdateAsset> assets) {
        Release(String tag, String url) {
            this(tag, url, 0, "", List.of());
        }
    }

    /** Where the check is. */
    public enum Status { IDLE, CHECKING, UP_TO_DATE, NEWER, FAILED }

    /** Why a check found nothing: no network, GitHub gave no usable answer, the setting is off, a dev build. */
    public enum Reason { NONE, OFFLINE, NO_ANSWER, OFF, DEV }

    /**
     * What the About page shows: the status (and why it failed), this jar's version, the newest
     * release's tag, page and date, when the answer came (0: never), the release notes and the
     * jar for this game (null: none fits, only the page is offered).
     */
    public record State(Status status, Reason reason, String own, String latest, String url, long published, long checkedAt,
                        String notes, UpdateAsset asset) {
        public State(Status status, Reason reason, String own, String latest, String url, long published, long checkedAt) {
            this(status, reason, own, latest, url, published, checkedAt, "", null);
        }
    }

    private static int ticks;
    /** Whether this run already asked (or decided not to). */
    private static boolean started;
    /** A newer release the worker found, shown on the next tick in a world. */
    private static volatile Release pending;
    private static String own = "";
    private static volatile State state = new State(Status.IDLE, Reason.NONE, "", null, null, 0, 0);
    /** When the last request started (0: none yet), for the ten-second wait. */
    private static long lastStart;

    /** Runs a request; tests swap it for one that runs nothing or runs at once. */
    static Consumer<Runnable> starter = job -> {
        Thread t = new Thread(job, "wynnlabs-update");
        t.setDaemon(true);
        t.start();
    };
    /** This jar's version ("" when unknown); tests give their own. */
    public static Supplier<String> version = () -> Platform.get().modVersion("wynnlabs").orElse("");

    private UpdateCheck() {}

    public static void onTick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        UpdateInstall.greet(mc);                // last run's install, said once
        Release r = pending;
        if (r != null) {
            pending = null;
            if (!UpdatePrefs.silenced(r.tag())) {
                mc.gui.getChat().addMessage(line(r, own));
            }
            return;
        }
        UpdateDownload.Phase done = UpdateDownload.takeNotice();
        if (done != null) {
            mc.gui.getChat().addMessage(done == UpdateDownload.Phase.READY
                    ? note("wynnlabs.update.downloaded", ChatFormatting.GREEN, UpdateDownload.tag())
                    : note("wynnlabs.update.failed_line", ChatFormatting.RED, UpdateDownload.tag(), failText(UpdateDownload.failure())));
        }
        if (!started && ++ticks >= DELAY_TICKS) {
            firstJoin(System.currentTimeMillis());
        }
    }

    /** The reminder or the auto update is on: the automatic check may ask. */
    private static boolean wanted() {
        return Settings.on("updateCheck") || Settings.on("autoUpdate");
    }

    /**
     * The automatic check, once a run: true when a request started. Both settings off: nothing
     * is asked (turned on later, it asks then).
     */
    static boolean firstJoin(long now) {
        if (started || !wanted()) {
            return false;
        }
        started = true;
        own = ownVersion();
        if (!checkable(own)) {
            return false;                       // a dev build or an unknown version: nothing to compare
        }
        start(own, true, now);
        return true;
    }

    /**
     * What the check knows now. Before any answer: off when the setting is off, a dev build when
     * this jar has no release number, otherwise not asked yet.
     */
    public static State state() {
        State s = state;
        if (s.status() == Status.IDLE || s.reason() == Reason.OFF && wanted()) {
            String mine = ownVersion();
            if (!wanted()) {
                return new State(Status.FAILED, Reason.OFF, mine, null, null, 0, 0);
            }
            if (!checkable(mine)) {
                return new State(Status.FAILED, Reason.DEV, mine, null, null, 0, 0);
            }
            return new State(Status.IDLE, Reason.NONE, mine, null, null, 0, 0);
        }
        return s;
    }

    /** The player pressed "Check again": one request, unless one runs or started under ten seconds ago. */
    public static boolean checkNow() {
        return checkNow(System.currentTimeMillis());
    }

    /** {@link #checkNow()} at {@code now}; false when nothing was asked (waiting, off, a dev build). */
    static synchronized boolean checkNow(long now) {
        if (state.status() == Status.CHECKING || lastStart != 0 && now - lastStart < DEBOUNCE_MS) {
            return false;
        }
        String mine = ownVersion();
        if (!wanted()) {
            state = new State(Status.FAILED, Reason.OFF, mine, null, null, 0, now);
            return false;
        }
        if (!checkable(mine)) {
            state = new State(Status.FAILED, Reason.DEV, mine, null, null, 0, now);
            return false;
        }
        start(mine, false, now);
        return true;
    }

    private static synchronized void start(String mine, boolean auto, long now) {
        lastStart = now;
        State was = state;
        state = new State(Status.CHECKING, Reason.NONE, mine, was.latest(), was.url(), was.published(), was.checkedAt(),
                was.notes(), was.asset());
        starter.accept(() -> fetch(mine, auto));
    }

    private static String ownVersion() {
        try {
            String v = version.get();
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /** On the worker thread: one GET, then the state (and, for the automatic check, a chat note). */
    private static void fetch(String mine, boolean auto) {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var req = java.net.http.HttpRequest.newBuilder(URI.create(URL))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "WynnLabs/" + mine + " (Minecraft client mod; update check; https://github.com/anilken/wynnlabs)")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            finish(mine, res.statusCode(), res.body(), auto, System.currentTimeMillis());
        } catch (Throwable e) {
            System.out.println("[WynnLabs] update check failed: " + e.getClass().getSimpleName());
            state = new State(Status.FAILED, reasonOf(e), mine, null, null, 0, System.currentTimeMillis());
        }
    }

    /**
     * [Update] in the chat line ({@code /wynnlabs-update}): the download starts at once, no window
     * opens. "Downloading…" now, the result (ready on restart, or why not) when it ends; with
     * nothing newer known, that this jar is up to date.
     */
    public static void updateFromChat(Consumer<Component> say) {
        State s = state();
        String tag = s.latest();
        if (!checkable(ownVersion())) {
            say.accept(note("wynnlabs.about.reason_dev", ChatFormatting.GRAY));
        } else if (s.status() != Status.NEWER) {
            say.accept(s.status() == Status.UP_TO_DATE ? note("wynnlabs.update.up_to_date", ChatFormatting.GREEN, tag)
                    : note("wynnlabs.update.none_known", ChatFormatting.GRAY));
        } else if (s.asset() == null) {
            say.accept(note("wynnlabs.update.failed_line", ChatFormatting.RED, tag, L.t("wynnlabs.update.no_fit_short")));
        } else if (tag.equals(UpdateDownload.tag()) && UpdateDownload.phase() == UpdateDownload.Phase.READY) {
            say.accept(note("wynnlabs.update.downloaded", ChatFormatting.GREEN, tag));
        } else if (UpdateDownload.phase() == UpdateDownload.Phase.DOWNLOADING
                || UpdateDownload.start(s.asset(), tag, true)) {
            say.accept(note("wynnlabs.update.downloading", ChatFormatting.YELLOW, tag));
        } else {
            say.accept(note("wynnlabs.update.failed_line", ChatFormatting.RED, tag, failText(UpdateDownload.failure())));
        }
    }

    /** Why a download failed, in words. */
    public static String failText(UpdateDownload.Failure f) {
        return L.t("wynnlabs.update.fail." + f.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** "Wynn Labs ▸ " and one text in a colour: the updater's chat lines. */
    static Component note(String key, ChatFormatting colour, Object... args) {
        return L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD).append(L.c(key, args).withStyle(colour));
    }

    /**
     * GitHub's answer into the state. A newer release found by the automatic check, unless
     * ignored: with auto update on and a jar for this game, downloaded (one line once ready);
     * otherwise, with the reminder on, said in chat.
     */
    static void finish(String mine, int status, String body, boolean auto, long now) {
        if (status != 200) {
            System.out.println("[WynnLabs] update check: HTTP " + status);
            state = new State(Status.FAILED, Reason.NO_ANSWER, mine, null, null, 0, now);
            return;
        }
        Release r = parse(body);
        if (r == null) {
            System.out.println("[WynnLabs] update check: no usable release in the answer");
            state = new State(Status.FAILED, Reason.NO_ANSWER, mine, null, null, 0, now);
            return;
        }
        boolean newer = newer(r.tag(), mine);
        UpdateAsset asset = newer ? UpdateAsset.choose(r.assets(), r.tag()) : null;
        state = new State(newer ? Status.NEWER : Status.UP_TO_DATE, Reason.NONE, mine, r.tag(), r.url(), r.published(), now,
                r.notes(), asset);
        if (!newer || !auto || UpdatePrefs.silenced(r.tag())) {
            return;
        }
        if (Settings.on("autoUpdate") && UpdateDownload.start(asset, r.tag(), true)) {
            return;
        }
        if (Settings.on("updateCheck")) {
            pending = r;
        }
    }

    /** No network (no route, no name, no connection) or GitHub not answering (a timeout, anything else). */
    static Reason reasonOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException || t instanceof java.net.http.HttpConnectTimeoutException) {
                return Reason.OFFLINE;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Reason.NO_ANSWER;
    }

    /** The preview's About shots: this state, as if a check had answered. */
    public static synchronized void preview(State s) {
        state = s;
        lastStart = 0;
    }

    /** Tests: as a fresh game run. */
    public static synchronized void reset() {
        state = new State(Status.IDLE, Reason.NONE, "", null, null, 0, 0);
        lastStart = 0;
        pending = null;
        started = false;
        ticks = 0;
    }

    /**
     * "Wynn Labs ▸ You are running v0.2.0, but the latest version is v0.3.0. Update by clicking
     * here.", as Wynntils says it: "here" downloads at once ({@code /wynnlabs-update}, a client
     * command); the changes and the release page are on the About page.
     */
    static Component line(Release r, String mine) {
        String have = mine.startsWith("v") ? mine : "v" + mine;
        Component here = L.c("wynnlabs.update.update_link").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/wynnlabs-update"))
                .withHoverEvent(new HoverEvent.ShowText(L.c("wynnlabs.update.update_hover"))));
        return L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD)
                .append(L.c("wynnlabs.update.newer", have, r.tag()).withStyle(ChatFormatting.GREEN))
                .append(here)
                .append(L.c("wynnlabs.update.newer_end").withStyle(ChatFormatting.GREEN));
    }

    /**
     * The release in GitHub's answer, or null when it has no tag, its page is not on github.com,
     * or it is a draft or a pre-release.
     */
    static Release parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            if (flag(o, "draft") || flag(o, "prerelease")) {
                return null;
            }
            String tag = text(o, "tag_name");
            String url = text(o, "html_url");
            if (tag == null || tag.isBlank() || url == null || !url.startsWith("https://github.com/")) {
                return null;
            }
            URI.create(url);                    // a page we could not open is no use
            String notes = text(o, "body");
            JsonElement assets = o.get("assets");
            return new Release(tag.trim(), url, instant(text(o, "published_at")), notes == null ? "" : notes,
                    UpdateAsset.parse(assets != null && assets.isJsonArray() ? assets.getAsJsonArray() : null));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An ISO time ("2026-10-01T12:00:00Z") in epoch milliseconds, 0 when missing or not a time. */
    private static long instant(String iso) {
        if (iso == null) {
            return 0;
        }
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** Whether this jar's version is worth comparing (see {@link Versions#checkable}). */
    static boolean checkable(String version) {
        return Versions.checkable(version) && !Versions.localBuild(version);
    }

    /** Whether {@code latest} is a newer version than {@code mine}. */
    static boolean newer(String latest, String mine) {
        return Versions.newer(latest, mine);
    }

    /** Semver order of two versions, null when either is not a version (see {@link Versions#compare}). */
    static Integer compare(String a, String b) {
        return Versions.compare(a, b);
    }
}
