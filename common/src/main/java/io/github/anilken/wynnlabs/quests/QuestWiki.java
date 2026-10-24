package io.github.anilken.wynnlabs.quests;

import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.advice.Advice;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.game.Professions;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A quest's page on the Wynncraft wiki (wynncraft.wiki.gg), read once and kept (memory, and the
 * raw page on disk for a week): its stages with their task, hints and coordinates, the mobs
 * table (level, health, weak elements) and the rewards. Fetched in the background; never asks
 * the game server for anything.
 */
public final class QuestWiki {

    /** {@code points}/{@code names}: every location the stage gives (three camps, …); {@code at} is the first. */
    public record Stage(String title, String task, List<String> hints, int[] at, List<int[]> points, List<String> names) {}

    public record Mob(String name, int level, int health, List<String> weak, String where) {}

    /**
     * A quest page's facts. {@code professions}: the profession levels it needs before it opens
     * ("MINING" → 20), from the infobox's {@code miningLevel = 20} lines; empty when none.
     */
    public record Info(String length, String difficulty, String province, int level, long xp, int emeralds,
            List<String> rewards, List<Stage> stages, List<Mob> mobs, Map<String, Integer> professions) {

        public Info(String length, String difficulty, String province, int level, long xp, int emeralds,
                List<String> rewards, List<Stage> stages, List<Mob> mobs) {
            this(length, difficulty, province, level, xp, emeralds, rewards, stages, mobs, Map.of());
        }
    }

    private static final Map<String, Info> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Pattern COORDS = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]");
    private static final Pattern RENDER = Pattern.compile("\\|\\s*x\\s*=\\s*(-?\\d+)\\s*\\|\\s*y\\s*=\\s*(-?\\d+)\\s*\\|\\s*z\\s*=\\s*(-?\\d+)");
    /** A RenderLocation with x and z only (the Silent Expanse's pages): the height is guessed. */
    private static final Pattern RENDER_XZ = Pattern.compile("\\|\\s*x\\s*=\\s*(-?\\d+)\\s*\\|\\s*z\\s*=\\s*(-?\\d+)");

    private static final Pattern RENDER_NAME = Pattern.compile("location\\s*=\\s*([^|}]+)");

    private QuestWiki() {}

    /**
     * The quest's guide as the menu shows it. With a shipped guide (assets/wynnlabs/quests): its
     * stages in the player's language. When the wiki's stages changed since it was shipped, a
     * translated guide still shows (flagged {@link #newer}: some steps may be out of date); an
     * English one shows the live page instead, unless that page has no stages. Without a shipped
     * guide: the live page, or null while it loads.
     */
    public static Info get(String quest) {
        Info live = live(quest);
        QuestGuides.Guide g = quest == null ? null : QuestGuides.find(quest);
        if (g == null) {
            return live;
        }
        String key = quest + "|" + L.code();
        Shown was = SHOWN.get(key);
        if (was != null && was.live() == live) {
            return was.info();
        }
        Info out;
        boolean changed = live != null && !stageHash(live.stages()).equals(g.hash());
        if (changed && g.translated() == null && !live.stages().isEmpty()) {
            out = live;                                 // English: the wiki as it is now
        } else {
            List<Stage> stages = g.translated() != null ? g.translated() : g.english();
            out = live != null
                    ? new Info(live.length(), live.difficulty(), live.province(), live.level(), live.xp(), live.emeralds(),
                            live.rewards(), stages, live.mobs(), live.professions())
                    : new Info("", "", "", 0, 0, 0, List.of(), stages, List.of(), Map.of());
        }
        if (changed && out != live) {
            NEWER.add(out);
        }
        ENGLISH.put(out, g.english());
        SHOWN.put(key, new Shown(live, out));
        return out;
    }

    /** What {@link #get} built for a quest and language, and from which live page. */
    private record Shown(Info live, Info info) {}

    private static final Map<String, Shown> SHOWN = new ConcurrentHashMap<>();
    /** Translated guides whose wiki page changed since they were shipped. */
    private static final java.util.Set<Info> NEWER = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** The English stages behind a translated guide, for matching the game's English task line. */
    private static final Map<Info, List<Stage>> ENGLISH = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** True when the guide shows a translation the wiki has changed since (some steps may be out of date). */
    public static boolean newer(Info info) {
        return info != null && NEWER.contains(info);
    }

    /** For tests: a live page as if it had been read. */
    public static void putLive(String quest, Info info) {
        CACHE.put(quest, info);
        SHOWN.clear();
    }

    /** True when a quest has a guide shipped with the mod. */
    static boolean shipped(String quest) {
        return quest != null && QuestGuides.find(quest) != null;
    }

    /** The parsed live page, or null while it loads (the first call starts the download) or when guides are off. */
    static Info live(String quest) {
        if (quest == null || quest.isBlank() || !on()) {
            return null;
        }
        Info i = CACHE.get(quest);
        if (i != null) {
            return i;
        }
        Long failed = FAILED.get(quest);
        if (failed != null && System.currentTimeMillis() - failed < 5 * 60_000L) {
            return null;
        }
        if (LOADING.add(quest)) {
            CompletableFuture.runAsync(() -> {
                try {
                    String text = page(quest);
                    if (text == null) {
                        FAILED.put(quest, System.currentTimeMillis());
                    } else {
                        CACHE.put(quest, parse(text));
                    }
                } catch (Exception e) {
                    FAILED.put(quest, System.currentTimeMillis());
                } finally {
                    LOADING.remove(quest);
                }
            });
        }
        return null;
    }

    /** The "Quest guides from the wiki" setting: off means no request to wynncraft.wiki.gg at all. */
    public static boolean on() {
        return Settings.on("questGuides");
    }

    public static boolean failed(String quest) {
        return FAILED.containsKey(quest) && !LOADING.contains(quest);
    }

    private static Path dir() {
        return Dirs.config().resolve("wiki");
    }

    /** Where the wiki's pages are kept for a week ({@code config/wynnlabs/wiki}); other readers keep their files there too. */
    public static Path cacheDir() {
        return dir();
    }

    /**
     * One call to the wiki's API ({@code api.php?<query>}, with the same User-Agent and time-outs
     * as the quest pages); the reply's body. Callers check {@link #on()} first.
     */
    public static String api(String query) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://wynncraft.wiki.gg/api.php?" + query))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "WynnLabs (Minecraft client mod; quest guide; https://github.com/anilken/wynnlabs)").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    /** The raw wikitext: from disk when under a week old, else from the wiki (then saved). */
    public static String page(String quest) throws Exception {
        Path f = dir().resolve(quest.replaceAll("[^A-Za-z0-9 ._'-]", "_") + ".txt");
        if (Files.exists(f) && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < 7L * 86_400_000L) {
            String kept = Files.readString(f, StandardCharsets.UTF_8);
            if (!DISAMBIGUATION.matcher(kept).find()) {
                return kept;
            }
        }
        String text = fetch(quest);
        // "Zhight Island" is a page listing the quest and the place: the quest's own page then
        if (text != null && DISAMBIGUATION.matcher(text).find() && text.contains("[[" + quest + " (Quest)]]")) {
            text = fetch(quest + " (Quest)");
        }
        if (text == null) {
            return null;
        }
        Files.createDirectories(dir());
        Files.writeString(f, text, StandardCharsets.UTF_8);
        return text;
    }

    private static final Pattern DISAMBIGUATION = Pattern.compile("(?i)\\{\\{\\s*disambiguation");

    /** One wiki page's wikitext (redirects followed), or null when there is none. */
    private static String fetch(String title) throws Exception {
        String url = "https://wynncraft.wiki.gg/api.php?action=query&prop=revisions&rvprop=content&rvslots=main"
                + "&format=json&redirects=1&titles=" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "WynnLabs (Minecraft client mod; quest guide; https://github.com/anilken/wynnlabs)").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var pages = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("query").getAsJsonObject("pages");
        var p = pages.entrySet().iterator().next().getValue().getAsJsonObject();
        if (!p.has("revisions")) {
            return null;
        }
        return p.getAsJsonArray("revisions").get(0).getAsJsonObject().getAsJsonObject("slots")
                .getAsJsonObject("main").get("*").getAsString();
    }

    /** Wikitext → plain text: links to their labels, colour templates to their text, other templates and tags dropped. */
    public static String clean(String s) {
        String t = s.trim();
        // colour templates keep their text: {{c|var(--…)|[5 Gold Chunks]}} → 5 Gold Chunks; the
        // innermost first, so one nested in another ({{c|#a00|told {{c|#aaa|you}}}}) unwraps too
        for (int i = 0; i < 4; i++) {
            String u = t.replaceAll("\\{\\{c\\|[^|{}]*\\|\\[?([^{}\\]]*)]?}}", "$1");
            if (u.equals(t)) {
                break;
            }
            t = u;
        }
        // tags first: a link's label may be a <span> around "[1 Bob's Tear]"
        t = t.replaceAll("<[^>]+>", "");
        // links: [[Page|label]] → label, [[Page]] → Page; the label may hold single brackets
        t = t.replaceAll("\\[\\[(?:[^|\\[\\]]*\\|)?(.*?)]](?!])", "$1");
        // any other template, innermost first, until none is left
        String before;
        do {
            before = t;
            t = t.replaceAll("\\{\\{[^{}]*}}", "");
        } while (!t.equals(before));
        return t.replaceAll("'''?", "").replaceAll("<[^>]+>", "").replaceAll("\\[https?://\\S+ ([^]]*)]", "$1")
                .replaceFirst("^[*#:]+\\s*", "").replaceAll("\\s{2,}", " ").trim();
    }

    private static String field(String text, String key) {
        Matcher m = Pattern.compile("(?m)^\\|\\s*" + key + "\\s*=\\s*(.*)$").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static int num(String s) {
        String d = s.replaceAll("[^0-9]", "");
        try {
            return d.isEmpty() ? 0 : Integer.parseInt(d.length() > 9 ? d.substring(0, 9) : d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Templates spread over several lines ({{Infobox/Items … }}) carry no guide text: drop them,
     * innermost first. RenderLocation stays (its coordinates are read).
     */
    public static String dropBlockTemplates(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("{{", i);
            if (open < 0) {
                out.append(s, i, s.length());
                break;
            }
            // the matching "}}", counting nested templates ({{WynnIcon|…}} inside an infobox)
            int depth = 0;
            int end = -1;
            for (int j = open; j < s.length() - 1; j++) {
                if (s.startsWith("{{", j)) {
                    depth++;
                    j++;
                } else if (s.startsWith("}}", j)) {
                    depth--;
                    j++;
                    if (depth == 0) {
                        end = j + 1;
                        break;
                    }
                }
            }
            String head = s.substring(open, Math.min(s.length(), open + 16));
            boolean keep = end < 0 || head.startsWith("{{RenderLocation") || head.startsWith("{{HideSpoiler")
                    || s.substring(open, end).indexOf('\n') < 0;
            if (keep) {
                out.append(s, i, open + 2);
                i = open + 2;
            } else {
                out.append(s, i, open);
                i = end;
            }
        }
        return out.toString();
    }

    public static Info parse(String text) {
        text = text.replaceAll("(?s)<ref.*?</ref>", "").replaceAll("(?s)<!--.*?-->", "");
        // rewards: the infobox line and the "*" lines right under it
        List<String> rewards = new ArrayList<>();
        Matcher rm = Pattern.compile("(?ms)^\\|\\s*reward\\s*=(.*?)(?=^\\||^}})").matcher(text);
        if (rm.find()) {
            for (String l : rm.group(1).split("\n")) {
                String c = clean(l);
                if (!c.isEmpty()) {
                    rewards.add(c);
                }
            }
        }
        // profession requirements: "miningLevel = 20", one line each, in the game's order
        Map<String, Integer> profs = new java.util.LinkedHashMap<>();
        for (String p : Professions.ALL) {
            int lv = num(field(text, p.toLowerCase(Locale.ROOT) + "Level"));
            if (lv > 0) {
                profs.put(p, lv);
            }
        }
        Info base = new Info(field(text, "length"), field(text, "difficulty"), field(text, "province"),
                num(field(text, "level")), num(field(text, "experience")), num(field(text, "emeralds")), rewards,
                new ArrayList<>(), new ArrayList<>(), profs);
        // stages: "== Stage N ==" blocks; the task is the "»" line, the rest are hints (no dialogue)
        // a part under a stage ("=== Puzzle ===", "Tips"): its lines and places join that stage,
        // after a "▸ Puzzle" line; the page's other sections (trivia, rewards, dialogue) stay out
        List<String[]> stageBlocks = new ArrayList<>();
        // a place without a height takes the last one given, on this stage or an earlier one
        int lastY = 64;
        boolean segments = WikiPlaces.segmentPage(text);    // "???": "== Segment 1 - … ==" in place of stages
        for (String b : text.split("(?m)^==\\s*")) {
            String head = b.contains("\n") ? b.substring(0, b.indexOf('\n')).replaceAll("=", "").trim() : "";
            String rest = b.contains("\n") ? b.substring(b.indexOf('\n') + 1) : "";
            if (head.toLowerCase(Locale.ROOT).startsWith("stage") || segments && WikiPlaces.segmentHead(head)) {
                stageBlocks.add(new String[] {head, rest});
            } else if (!stageBlocks.isEmpty() && !OTHER_SECTION.matcher(head).find()) {
                String[] last = stageBlocks.get(stageBlocks.size() - 1);
                last[1] = last[1] + "\n▸ " + head + "\n" + rest;
            }
        }
        for (String[] sb : stageBlocks) {
            String head = sb[0];
            String body = sb[1];
            // mobs: {{MobTable}} … |} with rows split by "|-"
            Matcher tm = Pattern.compile("(?s)\\{\\{MobTable}}(.*?)\\n\\|}").matcher(body);
            while (tm.find()) {
                for (String row : tm.group(1).split("\\n\\|-")) {
                    List<String> cells = new ArrayList<>();
                    for (String l : row.split("\n")) {
                        if (l.startsWith("|")) {
                            cells.add(l.substring(1).trim());
                        }
                    }
                    if (cells.size() >= 4) {
                        List<String> weak = new ArrayList<>();
                        Matcher em = Pattern.compile("\\{\\{Element\\|(\\w+)\\|Weak").matcher(row);
                        while (em.find()) {
                            weak.add(em.group(1).toLowerCase(Locale.ROOT));
                        }
                        String name = clean(cells.get(1).replace("<br>", " "));
                        base.mobs().add(new Mob(name, num(cells.get(2)), num(cells.get(3)), weak,
                                cells.isEmpty() ? "" : clean(cells.get(cells.size() - 1))));
                    }
                }
            }
            body = WikiPlaces.joinTemplates(body.replaceAll("(?s)\\{\\{MobTable}}.*?\\n\\|}", ""));
            // a spoiler's text on the line after "{{HideSpoiler" starts with "|" and is skipped: its places are still read
            java.util.Set<String> spoilers = new java.util.HashSet<>();
            Matcher sp = SPOILER_LINE.matcher(body);
            while (sp.find()) {
                spoilers.add(sp.group(1).trim());
            }
            // spoilers: drop the wrapper only; its closing "|label}}" line starts with "|" and is skipped
            body = body.replace("{{HideSpoiler|1=", "").replace("{{HideSpoiler|", "").replace("{{HideSpoiler", "");
            body = dropBlockTemplates(body);
            String task = "";
            List<String> hints = new ArrayList<>();
            int[] at = null;
            List<int[]> points = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String raw : body.split("\n")) {
                String l = WikiPlaces.dropPictureBeforeTask(raw.trim());
                if (l.startsWith("|") && spoilers.contains(l)) {
                    for (WikiPlaces.Template t : WikiPlaces.templates(l, lastY(points, lastY))) {
                        points.add(t.at());
                        names.add(t.name() != null ? clean(t.name()) : UNNAMED);
                    }
                    textPlaces(clean(l.substring(1)), points, names, lastY(points, lastY));
                    continue;
                }
                if (l.isEmpty() || l.startsWith("[[File:") || l.startsWith("'''Dialogue") || l.startsWith("*'''")
                        || l.startsWith("{{Clear") || l.startsWith("|") || l.startsWith("{|")) {
                    continue;
                }
                // a map template ({{RenderLocation | location = … | x = … | y = … | z = … }}, values in
                // any order); one without a height takes the last point's
                List<WikiPlaces.Template> ts = WikiPlaces.templates(l, lastY(points, lastY));
                Matcher r = RENDER.matcher(l);
                boolean full = r.find();
                if (ts.isEmpty() && full) {
                    Matcher nm = RENDER_NAME.matcher(l);
                    ts = List.of(new WikiPlaces.Template(new int[] {Integer.parseInt(r.group(1)), Integer.parseInt(r.group(2)),
                            Integer.parseInt(r.group(3))}, nm.find() ? nm.group(1) : null));
                }
                for (WikiPlaces.Template t : ts) {
                    points.add(t.at());
                    names.add(t.name() != null ? clean(t.name()) : UNNAMED);
                    if (at == null) {
                        at = t.at();
                    }
                }
                // a place line: the template alone, or one the guides always took as such; any other
                // line with a template ("Go to the cave.{{RenderLocation|coordinates=…}}") stays text
                if (full || l.contains("{{RenderLocation") && RENDER_XZ.matcher(l).find() || !ts.isEmpty() && WikiPlaces.onlyTemplates(l)) {
                    // the task may follow the place on the same line: "{{RenderLocation ...}}» Go ...",
                    // or be it: "» Talk to Zeph at {{MapLink|x=…}}." is the task, the template cut out
                    // (a later one, a fallback stage's, is still left out as before)
                    int close = l.indexOf("}}");
                    if (l.startsWith("»") && task.isEmpty()) {
                        l = WikiPlaces.withoutTemplates(l);
                    } else if (close < 0 || l.indexOf('»', close) < 0) {
                        continue;
                    } else {
                        l = l.substring(l.indexOf('»', close));
                    }
                }
                String c = clean(l);
                if (c.isEmpty() || IMAGE.matcher(c).find()) {        // a picture's file name and caption
                    continue;
                }
                int[] bracketed = textPlaces(c, points, names, lastY(points, lastY));
                if (at == null) {
                    at = bracketed;
                }
                if (c.startsWith("»") && task.isEmpty()) {
                    task = c.substring(1).trim();
                } else {
                    hints.add(c);
                }
            }
            if (at == null && !points.isEmpty()) {
                at = points.get(0);                     // a stage named only loosely still has a place to head for
            }
            lastY = lastY(points, lastY);
            base.stages().add(new Stage(head, task, hints, at, points, names));
        }
        return base;
    }

    /** "{{HideSpoiler" with its text starting the next line ("|Go through the gate at …|the next clue}}"). */
    private static final Pattern SPOILER_LINE = Pattern.compile("(?m)^\\{\\{HideSpoiler\\s*(\\|[^\\n]*)");

    private static int lastY(List<int[]> points, int before) {
        return points.isEmpty() ? before : points.get(points.size() - 1)[1];
    }

    /**
     * The places a cleaned line names, added to the stage's (each named by the line): every
     * "[x, y, z]"; else "x, y, z" without brackets ("* Cargo: 2027, 74, -4631"); else the looser
     * forms {@link WikiPlaces} reads ("at 465 28 -7920", "at (685, -407)"). The first bracketed
     * place, or null.
     */
    private static int[] textPlaces(String c, List<int[]> points, List<String> names, int lastY) {
        String name = c.length() > 40 ? c.substring(0, 40) : c;
        int[] first = null;
        Matcher m = COORDS.matcher(c);
        while (m.find()) {
            int[] pt = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            points.add(pt);
            names.add(name);
            if (first == null) {
                first = pt;
            }
        }
        if (first != null) {
            return first;
        }
        boolean found = false;
        Matcher lm = LOOSE.matcher(c);
        while (lm.find()) {
            int[] pt = {Integer.parseInt(lm.group(1)), Integer.parseInt(lm.group(2)), Integer.parseInt(lm.group(3))};
            if (place(pt)) {
                points.add(pt);
                names.add(name);
                found = true;
            }
        }
        if (!found) {
            for (int[] pt : WikiPlaces.loose(c, lastY)) {
                points.add(pt);
                names.add(name);
            }
        }
        return null;
    }

    /** A gallery or picture line: "File:Spider Cave.png|Spider Cave", "Yuman and Zeph (Cave hideout).png". */
    private static final Pattern IMAGE = Pattern.compile("(?i)^File:|\\.(png|jpe?g|gif|webp)(\\||$)");

    /** Page sections that are not part of the walkthrough. */
    private static final Pattern OTHER_SECTION = Pattern.compile(
            "(?i)\\b(trivia|rewards?|bonus|navigation|gallery|references|history|bugs?|videos?|dialogue|transcript|removed|npcs?|mobs?|items?)\\b");

    /** True when x, y, z can be a place in the world: y in the build height, not at the origin. */
    private static boolean place(int[] at) {
        return at[1] >= -64 && at[1] <= 320 && Math.abs(at[0]) + Math.abs(at[2]) >= 100;
    }

    /** The stage whose task shares the most words with the game's tracked task; -1 if none. */
    private static final Pattern STAGE_HEAD = Pattern.compile("(?i)stage\\s+(\\d+)");

    /** A place to pin: the stage it belongs to (0-based), where, and what is there. */
    public record Place(int stage, int[] at, String what) {}

    /** "x, y, z" in a line, bracketed or not; read as a place when it can be one (y in the world, not near the origin). */
    private static final Pattern LOOSE = Pattern.compile("(?<![\\d.])(-?\\d{1,5})\\s*,\\s*(-?\\d{1,3})\\s*,\\s*(-?\\d{1,5})(?![\\d.])");

    /**
     * Every place of the stages from {@code from} on, in order: each stage's points, then the
     * coordinates its task and hints name that are not among them (a door, a lever, a room).
     * A hint's place is labelled with the hint, without the coordinates.
     */
    /**
     * A map point the page gives no name. Parsed pages are kept per quest in every language
     * (and their stage hash must match the shipped guide's), so the parse keeps this English
     * word and {@link #pointName} words it when the point is shown.
     */
    static final String UNNAMED = "point";

    /** A point's name for display: an unnamed point in the mod's language, others as the wiki has them. */
    static String pointName(String name) {
        return UNNAMED.equals(name) ? L.t("wynnlabs.guide.point") : placeText(name);
    }

    public static List<Place> places(Info info, int from) {
        List<Place> out = new ArrayList<>();
        if (info == null) {
            return out;
        }
        for (int i = Math.max(0, from); i < info.stages().size(); i++) {
            Stage st = info.stages().get(i);
            List<String> lines = new ArrayList<>();
            lines.add(st.task());
            lines.addAll(st.hints());
            for (int k = 0; k < st.points().size(); k++) {
                // a place a line names is told by that line's part around it; a map template's keeps its name
                int[] at = st.points().get(k);
                String line = lineWith(lines, at);
                String what = line != null ? placeText(line, at)
                        : st.points().size() > 1 && k < st.names().size() ? pointName(st.names().get(k))
                        : st.task().isEmpty() ? stageTitle(st.title()) : placeText(st.task());
                add(out, new Place(i, at, what));
            }
            for (String line : lines) {
                Matcher m = LOOSE.matcher(line);
                while (m.find()) {
                    int[] at = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
                    if (at[1] < -64 || at[1] > 320 || Math.abs(at[0]) + Math.abs(at[2]) < 100) {
                        continue;
                    }
                    add(out, new Place(i, at, placeText(line, at)));
                }
            }
        }
        return out;
    }

    private static void add(List<Place> out, Place p) {
        // only within a stage: a later stage back at an earlier place (the quest giver at the end)
        // is a step of its own, else it shows as done with the first visit
        for (Place q : out) {
            if (q.stage() == p.stage() && Math.abs(q.at()[0] - p.at()[0]) <= 2 && Math.abs(q.at()[1] - p.at()[1]) <= 3 && Math.abs(q.at()[2] - p.at()[2]) <= 2) {
                return;                                 // the same place, already named
            }
        }
        out.add(p);
    }

    /** A guide line as shown: without the wiki's leftovers (a spoiler's "|How to open the door}}"). */
    public static String shown(String line) {
        // a spoiler's closing "|Magic of Nature Puzzle|60|Spoiler alert: …}}" wherever it sits in the line
        String s = line.replaceAll("\\s*\\|[^{}]*?}}", " ").replaceAll("\\{\\{[^{}]*}}", "")
                .replaceAll("\\s{2,}", " ").trim();
        return s.isEmpty() ? line : s;
    }

    /** A stage's hover: its task and its hints (no dialogue), wrapped, and whether lines were left out. */
    public record Tip(List<String> task, List<String> hints, boolean more) {}

    /** An NPC's line in a guide ("Worid: …"), told by its English hint. */
    private static final Pattern SPEAKER = Pattern.compile("^[A-Z?][^:]{0,40}: ");

    /**
     * The hover of a stage: the task, then the hints that are not NPC dialogue (told by the English
     * stage's hints, as the Turkish speaker names vary), cleaned and wrapped to {@code width}
     * characters, at most {@code maxLines} lines in all; {@code more} when hints were left out.
     */
    public static Tip tip(Stage st, Stage english, int width, int maxLines) {
        List<String> task = st.task().isBlank() ? List.of() : Advice.wrap(shown(st.task()), width);
        List<String> hints = new ArrayList<>();
        boolean more = false;
        for (int j = 0; j < st.hints().size(); j++) {
            String en = english != null && j < english.hints().size() ? english.hints().get(j) : st.hints().get(j);
            String h = shown(st.hints().get(j));
            if (SPEAKER.matcher(en).find() || h.isBlank()) {
                continue;
            }
            List<String> wrapped = Advice.wrap(h, width);
            if (task.size() + hints.size() + wrapped.size() > maxLines) {
                more = true;
                break;
            }
            hints.addAll(wrapped);
        }
        return new Tip(task, hints, more);
    }

    /** The English stages behind a guide (the guide's own when it is English). */
    public static List<Stage> englishStages(Info info) {
        return info == null ? List.of() : ENGLISH.getOrDefault(info, info.stages());
    }

    /** The first line that names this place, or null. */
    private static String lineWith(List<String> lines, int[] at) {
        for (String line : lines) {
            Matcher m = LOOSE.matcher(line);
            while (m.find()) {
                if (Integer.parseInt(m.group(1)) == at[0] && Integer.parseInt(m.group(2)) == at[1] && Integer.parseInt(m.group(3)) == at[2]) {
                    return line;
                }
            }
        }
        return null;
    }

    /** "[x, y, z] noktasındaki kapı…": a Turkish place's words come after it. */
    private static final Pattern AFTER = Pattern.compile("^\\s*[\\])]?\\s*(?:noktasında(?:ki)?|noktasına|konumunda(?:ki)?|konumuna)\\s+");
    /** "Erisk, mağaranın sonunda [x, y, z] noktasında bulunur.": only a verb after it, the place starts the sentence. */
    private static final Pattern THERE = Pattern.compile("^\\s*[\\])]?\\s*(?:noktasında|noktasına|konumunda|konumuna)\\s+\\S+(?:\\s+\\S+)?\\s*$");

    /**
     * What is at one of a line's places: the part of its sentence that names it. In Turkish the
     * words after "[x, y, z] noktasındaki", or the sentence's first clause when only a verb
     * follows ("…, [x, y, z] noktasında bulunur"); else the clause before the coordinates ("…, step on
     * the pressure plate at [x, y, z]"). A line with one place and nothing better: the whole line.
     */
    static String placeText(String line, int[] at) {
        for (String sentence : line.split("(?<=[.!?])\\s+")) {
            Matcher m = LOOSE.matcher(sentence);
            while (m.find()) {
                if (Integer.parseInt(m.group(1)) != at[0] || Integer.parseInt(m.group(2)) != at[1] || Integer.parseInt(m.group(3)) != at[2]) {
                    continue;
                }
                String after = sentence.substring(m.end());
                Matcher a = AFTER.matcher(after);
                String part;
                if (a.find() && !THERE.matcher(after).find()) {
                    part = after.substring(a.end());
                } else {
                    String before = sentence.substring(0, m.start()).replaceAll("[\\[(]\\s*$", "");
                    String[] clauses = before.split(",\\s+|;\\s+");
                    // Turkish puts the subject first ("Erisk, mağaranın sonunda … bulunur"); English the place last
                    part = clauses[THERE.matcher(after).find() ? 0 : clauses.length - 1];
                    if (placeText(part).length() < 6) {
                        part = before;
                    }
                }
                String out = placeText(part).replaceAll("(?i)\\s+(at|near|to)$", "");
                return out.length() >= 4 ? out : placeText(sentence);
            }
        }
        return placeText(line);
    }

    /** A line as a pin label: no coordinates, wiki leftovers or doubled spaces. */
    public static String placeText(String line) {
        // the coordinates with the words that only point at them: "at [x, y, z]", "[x, y, z] noktasındaki"
        String s = line.replaceAll("(?i)(\\s+(?:at|near))?\\s*[\\[(]?\\s*-?\\d{1,5}\\s*,\\s*-?\\d{1,3}\\s*,\\s*-?\\d{1,5}\\s*[\\])]?"
                        + "(\\s+(?:noktasında(?:ki)?|noktasına|konumunda(?:ki)?|konumuna))?", " ")
                .replaceAll("\\{\\{[^}]*}}|\\|[^|]*}}|\\{\\{|}}|\\[\\[|]]", " ")
                .replaceAll("\\s+([.,;:!?])", "$1").replaceAll("\\s{2,}", " ").trim();
        return s.replaceAll("^[.,;:»•*]+\\s*|\\s*[,;:]+$", "");
    }

    /** A stage heading for display: the wiki's "Stage 6" in the player's language, others as they are. */
    public static String stageTitle(String head) {
        Matcher m = STAGE_HEAD.matcher(head.trim());
        return m.matches() ? L.t("wynnlabs.guide.stage", m.group(1)) : head;
    }

    /**
     * A fingerprint of a page's stages (titles, tasks, hints, places), as the shipped quest
     * guides record it: equal while the wiki's stages are unchanged. Built the same way by the
     * dump that writes the shipped guides (QuestDump) and at run time.
     */
    static String stageHash(List<Stage> stages) {
        StringBuilder sb = new StringBuilder();
        for (Stage st : stages) {
            sb.append(st.title()).append('\u0001').append(st.task()).append('\u0001');
            for (String h : st.hints()) {
                sb.append(h).append('\u0002');
            }
            sb.append('\u0001');
            for (int[] at : st.points()) {
                sb.append(at[0]).append(',').append(at[1]).append(',').append(at[2]).append(';');
            }
            sb.append('\u0001').append(String.join("\u0002", st.names())).append('\n');
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The tracked step to show: the matched stage's task in the guide's language, else the game's own line. */
    public static String stepText(Info info, String trackedTask) {
        int i = currentStage(info, trackedTask);
        if (i < 0 || info.stages().get(i).task().isBlank()) {
            return trackedTask == null ? "" : trackedTask;
        }
        return info.stages().get(i).task();
    }

    /** The stage the game's (English) tracked task line is at; a translated guide is matched on its English stages. */
    public static int currentStage(Info info, String trackedTask) {
        if (info == null || trackedTask == null || trackedTask.isBlank()) {
            return -1;
        }
        List<Stage> stages = ENGLISH.getOrDefault(info, info.stages());
        String low = trackedTask.toLowerCase(Locale.ROOT);
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < stages.size(); i++) {
            int score = 0;
            for (String w : stages.get(i).task().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (w.length() > 3 && low.contains(w)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }
}
