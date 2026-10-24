package io.github.anilken.wynnlabs.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feature switches, toggled on the road map's settings tab (J) and kept in
 * config/wynnlabs/settings.json for every character. Everything is on by default.
 */
public final class Settings {

    /** Key → label shown in the menu, in menu order. */
    /** Setting → its language key, in the order the settings tab shows them. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("itemAdvice", "wynnlabs.setting.itemAdvice");
        LABELS.put("altAdvice", "wynnlabs.setting.altAdvice");
        LABELS.put("menuAdvice", "wynnlabs.setting.menuAdvice");
        LABELS.put("wornHint", "wynnlabs.setting.wornHint");
        LABELS.put("powderHint", "wynnlabs.setting.powderHint");
        LABELS.put("tooltipPrice", "wynnlabs.setting.tooltipPrice");
        LABELS.put("boxPrices", "wynnlabs.setting.boxPrices");
        LABELS.put("glowTree", "wynnlabs.setting.glowTree");
        LABELS.put("glowUpgrade", "wynnlabs.setting.glowUpgrade");
        LABELS.put("glowShop", "wynnlabs.setting.glowShop");
        LABELS.put("chatUpgrade", "wynnlabs.setting.chatUpgrade");
        LABELS.put("chatSkills", "wynnlabs.setting.chatSkills");
        LABELS.put("chatLevelUp", "wynnlabs.setting.chatLevelUp");
        LABELS.put("groundGlow", "wynnlabs.setting.groundGlow");
        LABELS.put("groundBeam", "wynnlabs.setting.groundBeam");
        LABELS.put("groundGlowShop", "wynnlabs.setting.groundGlowShop");
        LABELS.put("groundGlowNormal", "wynnlabs.setting.groundGlowNormal");
        LABELS.put("groundGlowOther", "wynnlabs.setting.groundGlowOther");
        LABELS.put("mobTrack", "wynnlabs.setting.mobTrack");
        LABELS.put("mobTrackSound", "wynnlabs.setting.mobTrackSound");
        LABELS.put("autoTree", "wynnlabs.setting.autoTree");
        LABELS.put("learning", "wynnlabs.setting.learning");
        LABELS.put("manaMeter", "wynnlabs.setting.manaMeter");
        LABELS.put("treeBonuses", "wynnlabs.setting.treeBonuses");
        LABELS.put("glowQuest", "wynnlabs.setting.glowQuest");
        LABELS.put("priceApi", "wynnlabs.setting.priceApi");
        LABELS.put("dangerWarn", "wynnlabs.setting.dangerWarn");
        LABELS.put("sellSummary", "wynnlabs.setting.sellSummary");
        LABELS.put("bankHint", "wynnlabs.setting.bankHint");
        LABELS.put("sourceHint", "wynnlabs.setting.sourceHint");
        LABELS.put("survivalWarn", "wynnlabs.setting.survivalWarn");
        LABELS.put("coachToasts", "wynnlabs.setting.coachToasts");
        LABELS.put("dpsHud", "wynnlabs.setting.dpsHud");
        LABELS.put("sellGlow", "wynnlabs.setting.sellGlow");
        LABELS.put("verdictBadges", "wynnlabs.setting.verdictBadges");
        LABELS.put("hotbarBadges", "wynnlabs.setting.hotbarBadges");
        LABELS.put("miniQuests", "wynnlabs.setting.miniQuests");
        LABELS.put("questGuides", "wynnlabs.setting.questGuides");
        LABELS.put("updateCheck", "wynnlabs.setting.updateCheck");
        LABELS.put("autoUpdate", "wynnlabs.setting.autoUpdate");
        LABELS.put("localApi", "wynnlabs.setting.localApi");
        LABELS.put("apiWrite", "wynnlabs.setting.apiWrite");
        LABELS.put("debug", "wynnlabs.setting.debug");
        LABELS.put("webFont", "wynnlabs.setting.webFont");
    }

    /** Setting → its settings-tab category (tips, glow, coach, quests, build, data). */
    static final Map<String, String> CATEGORY = new LinkedHashMap<>();
    /** Setting → the language key of its one-line description. */
    public static final Map<String, String> DESC = new LinkedHashMap<>();

    static {
        CATEGORY.put("webFont", "look");
        DESC.put("webFont", "wynnlabs.setting_desc.webFont");
        CATEGORY.put("itemAdvice", "tips");
        DESC.put("itemAdvice", "wynnlabs.setting_desc.itemAdvice");
        CATEGORY.put("altAdvice", "tips");
        DESC.put("altAdvice", "wynnlabs.setting_desc.altAdvice");
        CATEGORY.put("wornHint", "tips");
        DESC.put("wornHint", "wynnlabs.setting_desc.wornHint");
        CATEGORY.put("powderHint", "tips");
        DESC.put("powderHint", "wynnlabs.setting_desc.powderHint");
        CATEGORY.put("tooltipPrice", "tips");
        DESC.put("tooltipPrice", "wynnlabs.setting_desc.tooltipPrice");
        CATEGORY.put("boxPrices", "tips");
        DESC.put("boxPrices", "wynnlabs.setting_desc.boxPrices");
        CATEGORY.put("bankHint", "tips");
        DESC.put("bankHint", "wynnlabs.setting_desc.bankHint");
        CATEGORY.put("sourceHint", "tips");
        DESC.put("sourceHint", "wynnlabs.setting_desc.sourceHint");
        CATEGORY.put("glowTree", "glow");
        DESC.put("glowTree", "wynnlabs.setting_desc.glowTree");
        CATEGORY.put("glowUpgrade", "glow");
        DESC.put("glowUpgrade", "wynnlabs.setting_desc.glowUpgrade");
        CATEGORY.put("glowShop", "glow");
        DESC.put("glowShop", "wynnlabs.setting_desc.glowShop");
        CATEGORY.put("groundGlow", "glow");
        DESC.put("groundGlow", "wynnlabs.setting_desc.groundGlow");
        CATEGORY.put("groundBeam", "glow");
        DESC.put("groundBeam", "wynnlabs.setting_desc.groundBeam");
        CATEGORY.put("groundGlowShop", "glow");
        DESC.put("groundGlowShop", "wynnlabs.setting_desc.groundGlowShop");
        CATEGORY.put("groundGlowNormal", "glow");
        DESC.put("groundGlowNormal", "wynnlabs.setting_desc.groundGlowNormal");
        CATEGORY.put("groundGlowOther", "glow");
        DESC.put("groundGlowOther", "wynnlabs.setting_desc.groundGlowOther");
        CATEGORY.put("mobTrack", "glow");
        DESC.put("mobTrack", "wynnlabs.setting_desc.mobTrack");
        CATEGORY.put("mobTrackSound", "glow");
        DESC.put("mobTrackSound", "wynnlabs.setting_desc.mobTrackSound");
        CATEGORY.put("sellGlow", "glow");
        DESC.put("sellGlow", "wynnlabs.setting_desc.sellGlow");
        CATEGORY.put("verdictBadges", "glow");
        DESC.put("verdictBadges", "wynnlabs.setting_desc.verdictBadges");
        CATEGORY.put("hotbarBadges", "glow");
        DESC.put("hotbarBadges", "wynnlabs.setting_desc.hotbarBadges");
        CATEGORY.put("chatUpgrade", "coach");
        CATEGORY.put("chatSkills", "coach");
        DESC.put("chatSkills", "wynnlabs.setting_desc.chatSkills");
        DESC.put("chatUpgrade", "wynnlabs.setting_desc.chatUpgrade");
        CATEGORY.put("chatLevelUp", "coach");
        DESC.put("chatLevelUp", "wynnlabs.setting_desc.chatLevelUp");
        CATEGORY.put("dangerWarn", "coach");
        DESC.put("dangerWarn", "wynnlabs.setting_desc.dangerWarn");
        CATEGORY.put("survivalWarn", "coach");
        DESC.put("survivalWarn", "wynnlabs.setting_desc.survivalWarn");
        CATEGORY.put("coachToasts", "coach");
        DESC.put("coachToasts", "wynnlabs.setting_desc.coachToasts");
        CATEGORY.put("dpsHud", "coach");
        DESC.put("dpsHud", "wynnlabs.setting_desc.dpsHud");
        CATEGORY.put("sellSummary", "coach");
        DESC.put("sellSummary", "wynnlabs.setting_desc.sellSummary");
        CATEGORY.put("glowQuest", "quests");
        DESC.put("glowQuest", "wynnlabs.setting_desc.glowQuest");
        CATEGORY.put("miniQuests", "quests");
        DESC.put("miniQuests", "wynnlabs.setting_desc.miniQuests");
        CATEGORY.put("menuAdvice", "build");
        DESC.put("menuAdvice", "wynnlabs.setting_desc.menuAdvice");
        CATEGORY.put("autoTree", "build");
        DESC.put("autoTree", "wynnlabs.setting_desc.autoTree");
        CATEGORY.put("learning", "build");
        DESC.put("learning", "wynnlabs.setting_desc.learning");
        CATEGORY.put("manaMeter", "build");
        DESC.put("manaMeter", "wynnlabs.setting_desc.manaMeter");
        CATEGORY.put("treeBonuses", "build");
        DESC.put("treeBonuses", "wynnlabs.setting_desc.treeBonuses");
        CATEGORY.put("priceApi", "data");
        DESC.put("priceApi", "wynnlabs.setting_desc.priceApi");
        CATEGORY.put("questGuides", "data");
        DESC.put("questGuides", "wynnlabs.setting_desc.questGuides");
        CATEGORY.put("updateCheck", "data");
        DESC.put("updateCheck", "wynnlabs.setting_desc.updateCheck");
        CATEGORY.put("autoUpdate", "data");
        DESC.put("autoUpdate", "wynnlabs.setting_desc.autoUpdate");
        CATEGORY.put("localApi", "ai");
        DESC.put("localApi", "wynnlabs.setting_desc.localApi");
        CATEGORY.put("apiWrite", "ai");
        DESC.put("apiWrite", "wynnlabs.setting_desc.apiWrite");
        CATEGORY.put("debug", "data");
        DESC.put("debug", "wynnlabs.setting_desc.debug");
    }

    /**
     * Number settings with − and + on the settings tab: key → the values they step through, in
     * order. Their row sits under the switch they belong to ({@link #STEP_PARENT}).
     */
    public static final Map<String, int[]> STEPS = new LinkedHashMap<>();
    /** Number setting → its language key. */
    public static final Map<String, String> STEP_LABELS = new LinkedHashMap<>();
    /** Number setting → the switch its row sits under on the settings tab. */
    public static final Map<String, String> STEP_PARENT = new LinkedHashMap<>();
    /** Number setting → the language key that writes its value ("85%"); plain digits when absent. */
    public static final Map<String, String> STEP_FORMAT = new LinkedHashMap<>();
    private static final Map<String, Integer> STEP_DEFAULT = new LinkedHashMap<>();

    static {
        // tooltip size while a menu is open, in percent; 100 leaves tooltips as the game draws them
        STEPS.put("menuTooltipScale", new int[] {70, 75, 80, 85, 90, 95, 100});
        STEP_LABELS.put("menuTooltipScale", "wynnlabs.setting.menuTooltipScale");
        STEP_PARENT.put("menuTooltipScale", "tooltipPrice");
        STEP_FORMAT.put("menuTooltipScale", "wynnlabs.settings.percent");
        STEP_DEFAULT.put("menuTooltipScale", 85);
        DESC.put("menuTooltipScale", "wynnlabs.setting_desc.menuTooltipScale");
    }

    /** A stepped number setting's value (its default when unset). */
    public static int stepValue(String key) {
        return number(key, stepDefault(key));
    }

    /** What a stepped number setting is before the player touches it. */
    public static int stepDefault(String key) {
        Integer d = STEP_DEFAULT.get(key);
        return d == null ? 0 : d;
    }

    /** One step down (−1) or up (+1) through a number setting's values, stopping at the ends. */
    public static synchronized void step(String key, int dir) {
        int[] options = STEPS.get(key);
        if (options == null) {
            return;
        }
        int v = stepValue(key);
        int at = 0;                             // the option nearest the stored value
        for (int i = 1; i < options.length; i++) {
            if (Math.abs(options[i] - v) < Math.abs(options[at] - v)) {
                at = i;
            }
        }
        if (options[at] == v || dir > 0 && options[at] < v || dir < 0 && options[at] > v) {
            at += dir;                          // on an option, or past it the way we step: move on
        }
        setNumber(key, options[Math.max(0, Math.min(options.length - 1, at))]);
    }

    public static String category(String key) {
        return CATEGORY.getOrDefault(key, "tips");
    }

    /**
     * Keys of features that were removed (loot chest glow and labels, far chest markers, party and
     * nameless glow). An old settings.json may still hold them: they are dropped on load, so the
     * next save no longer writes them.
     */
    static final java.util.Set<String> REMOVED = java.util.Set.of("chestGlow", "chestGlowUnknown", "chestLabel",
            "chestMinStars", "chestFar", "chestFarTier", "chestFarDistance", "partyGlow", "namelessGlow");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Boolean> VALUES = new LinkedHashMap<>();
    private static volatile boolean loaded;
    /**
     * What {@link #on} and {@link #number} read: immutable copies of VALUES and NUMBERS, swapped
     * after every change. Readers never lock (they run per entity and per slot every frame);
     * writers change the maps under the class lock and publish new copies.
     */
    private static volatile Map<String, Boolean> valuesNow = Map.of();
    private static volatile Map<String, Integer> numbersNow = Map.of();

    private Settings() {}

    private static Path file() {
        return Dirs.config().resolve("settings.json");
    }

    private static void load() {
        if (!loaded) {
            loadLocked();
        }
    }

    private static synchronized void loadLocked() {
        if (loaded) {
            return;
        }
        try {
            if (Files.exists(file())) {
                read(GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class));
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read settings.json: " + e);
        }
        publish();
        loaded = true;
    }

    /** A settings file's switches and numbers into VALUES and NUMBERS, skipping removed features. */
    private static void read(JsonObject o) {
        if (o == null) {
            return;
        }
        o.entrySet().forEach(e -> {
            if (REMOVED.contains(e.getKey()) || !e.getValue().isJsonPrimitive()) {
                return;                         // a feature that is gone, or a value we never wrote
            }
            var p = e.getValue().getAsJsonPrimitive();
            if (p.isBoolean()) {
                VALUES.put(e.getKey(), p.getAsBoolean());
            } else if (p.isNumber()) {
                NUMBERS.put(e.getKey(), p.getAsInt());
            }
        });
    }

    /** New read copies after a change; call with the class lock held. */
    private static void publish() {
        valuesNow = Map.copyOf(VALUES);
        numbersNow = Map.copyOf(NUMBERS);
    }

    private static final Map<String, Integer> NUMBERS = new LinkedHashMap<>();

    /** Tests only: these values instead of settings.json, never saved. */
    public static synchronized void use(JsonObject o) {
        VALUES.clear();
        NUMBERS.clear();
        read(o);
        publish();
        loaded = true;
        Rules.version++;
    }

    /** A small number setting (e.g. the language picked or the API port). */
    public static int number(String key, int fallback) {
        load();
        Integer v = numbersNow.get(key);
        return v == null ? fallback : v;
    }

    /**
     * Switches that start off; everything else starts on. Past the second line: the chattier and
     * busier extras, so a first run shows the mod's main work and the rest is a switch away.
     */
    private static final java.util.Set<String> OFF_BY_DEFAULT = java.util.Set.of("altAdvice", "groundGlowNormal", "priceApi",
            "localApi", "apiWrite", "debug", "dpsHud", "autoUpdate",
            "chatUpgrade", "coachToasts", "mobTrackSound", "groundBeam", "groundGlowOther", "hotbarBadges", "learning",
            "powderHint");

    public static boolean on(String key) {
        load();
        Boolean v = valuesNow.get(key);
        return v != null ? v : byDefault(key);
    }

    /** Whether the player set this switch (it is in settings.json), on or off. */
    public static boolean saved(String key) {
        load();
        return valuesNow.containsKey(key);
    }

    /** What a switch is before the player touches it. */
    public static boolean byDefault(String key) {
        return !OFF_BY_DEFAULT.contains(key);
    }

    /** Whether a switch differs from its default (the settings tab marks it with a gold dot). */
    public static boolean changed(String key) {
        return on(key) != byDefault(key);
    }

    /** A switch back to its default: it is no longer written to settings.json. */
    public static synchronized void reset(String key) {
        load();
        VALUES.remove(key);
        save();
    }

    /** A number setting back to its default (the fallback its readers give). */
    public static synchronized void resetNumber(String key) {
        load();
        NUMBERS.remove(key);
        save();
    }

    public static synchronized void setNumber(String key, int value) {
        load();
        NUMBERS.put(key, value);
        save();
    }

    public static synchronized void set(String key, boolean value) {
        load();
        VALUES.put(key, value);
        save();
    }

    public static synchronized void toggle(String key) {
        load();
        VALUES.put(key, !on(key));
        save();
    }

    private static void save() {
        publish();
        Rules.version++;                        // cached verdicts and glows rebuild
        try {
            JsonObject o = new JsonObject();
            VALUES.forEach(o::addProperty);
            NUMBERS.forEach(o::addProperty);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save settings.json: " + e);
        }
    }

    public static JsonObject json() {
        JsonObject o = new JsonObject();
        LABELS.keySet().forEach(k -> o.addProperty(k, on(k)));
        return o;
    }
}
