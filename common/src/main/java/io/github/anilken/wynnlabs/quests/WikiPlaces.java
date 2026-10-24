package io.github.anilken.wynnlabs.quests;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ways a quest page writes a place, past the plain "[x, y, z]" that {@link QuestWiki} reads
 * itself: the map templates ({{RenderLocation}}, {{Location}}, {{MapLink}}) with their values in
 * any order or over several lines, "x y z" without commas, and x and z alone ("at (685, -407)",
 * "X: 1320 Z: -417"). A place without a height takes the one of the point before it. Kept narrow:
 * a wrong pin is worse than none, so loose numbers count only after "at" or in brackets.
 */
final class WikiPlaces {

    /** A map template, its values on one line or several. */
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*(?:RenderLocation|Location|MapLink)\\b([^{}]*)}}");
    private static final Pattern PARAM = Pattern.compile("\\|\\s*(\\w+)\\s*=\\s*([^|}]*)");
    /** "at 465 28 -7920", "[-570 40 -3543]", "(19955 28 -4490)": three numbers without commas. */
    private static final Pattern SPACED = Pattern.compile(
            "(?i)(?:\\bat\\s+(?:coordinates\\s+)?[\\[(]?|[\\[(])\\s*(-?\\d{1,5})\\s+(-?\\d{1,3})\\s+(-?\\d{1,5})(?!\\d|[.,]\\d|\\s*,?\\s*-?\\d)");
    /** "at (685, -407)", "at -285, -4513", "at [503 -1628]", "(-16045, 20770)": x and z only. */
    private static final Pattern PAIR = Pattern.compile("(?i)(?:\\bat\\s+(?:coordinates\\s+)?([\\[(])?|([\\[(]))\\s*"
            + "(-?\\d{2,5})(\\s*,(?:\\s+|(?=-))|\\s+)(-?\\d{2,5})\\s*([\\])])?(?!\\d|[.,]\\d|\\s*,?\\s*-?\\d)");
    /** "X: 1320 Z: -417". */
    private static final Pattern XZ = Pattern.compile("\\bX:\\s*(-?\\d{1,5}),?\\s*Z:\\s*(-?\\d{1,5})");
    /** A map template starting a line. */
    private static final Pattern BLOCK = Pattern.compile("(?m)^[ \\t]*\\{\\{\\s*(?:RenderLocation|Location|MapLink)\\b[^{}]*}}");
    private static final Pattern LEFT_OVER = Pattern.compile("<br\\s*/?>|[\\s*:.]");

    private WikiPlaces() {}

    /**
     * Each map template that starts a line and runs over the lines below ({{Location\n| x = 305\n| y = 69 …}})
     * put on one line, so its place is read. One that starts after a line's text stays as it is.
     */
    static String joinTemplates(String body) {
        return BLOCK.matcher(body).replaceAll(m -> Matcher.quoteReplacement(m.group().replaceAll("\\s*\\n\\s*", " ")));
    }

    /** True when a line holds nothing but map templates (and a line break or a full stop). */
    static boolean onlyTemplates(String line) {
        return LEFT_OVER.matcher(TEMPLATE.matcher(line).replaceAll("")).replaceAll("").isEmpty();
    }

    /** A map template's place and its "location" name (null when it gives none); null without x and z. */
    record Template(int[] at, String name) {}

    /** Every map template of a line that gives a place, in order; one with no height takes {@code lastY}. */
    static List<Template> templates(String line, int lastY) {
        List<Template> out = new ArrayList<>();
        Matcher m = TEMPLATE.matcher(line);
        while (m.find()) {
            Integer x = null;
            Integer y = null;
            Integer z = null;
            String name = null;
            Matcher p = PARAM.matcher(m.group(1));
            while (p.find()) {
                String v = p.group(2).trim();
                switch (p.group(1).toLowerCase(java.util.Locale.ROOT)) {
                    case "x" -> x = num(v);
                    case "y" -> y = num(v);
                    case "z" -> z = num(v);
                    case "location" -> name = v.isEmpty() ? null : v;
                    case "coordinates" -> {
                        String[] c = v.split("\\s*,\\s*");
                        if (c.length == 3) {
                            x = num(c[0]);
                            y = num(c[1]);
                            z = num(c[2]);
                        }
                    }
                    default -> { }
                }
            }
            if (x != null && z != null) {
                out.add(new Template(new int[] {x, y != null ? y : lastY, z}, name));
            }
        }
        return out;
    }

    /**
     * The places a cleaned line names without brackets and commas, or with x and z only, in the
     * order they appear; those that cannot be a place (near the origin, a height outside the
     * world) are left out. Lines with "[x, y, z]" or "x, y, z" are read by {@link QuestWiki} first.
     */
    static List<int[]> loose(String line, int lastY) {
        List<Found> found = new ArrayList<>();
        Matcher s = SPACED.matcher(line);
        while (s.find()) {
            add(found, s, new int[] {Integer.parseInt(s.group(1)), Integer.parseInt(s.group(2)), Integer.parseInt(s.group(3))});
        }
        Matcher p = PAIR.matcher(line);
        while (p.find()) {
            // "x z" without a comma only in brackets: "[503 -1628]", never "at 20 30"
            boolean open = p.group(1) != null || p.group(2) != null;
            if (p.group(4).contains(",") || open && p.group(6) != null) {
                add(found, p, new int[] {Integer.parseInt(p.group(3)), lastY, Integer.parseInt(p.group(5))});
            }
        }
        Matcher xz = XZ.matcher(line);
        while (xz.find()) {
            add(found, xz, new int[] {Integer.parseInt(xz.group(1)), lastY, Integer.parseInt(xz.group(2))});
        }
        found.sort((a, b) -> Integer.compare(a.start(), b.start()));
        List<int[]> out = new ArrayList<>();
        for (Found f : found) {
            if (f.at()[1] >= -64 && f.at()[1] <= 320 && Math.abs(f.at()[0]) + Math.abs(f.at()[2]) >= 100) {
                out.add(f.at());
            }
        }
        return out;
    }

    private record Found(int start, int end, int[] at) {}

    private static void add(List<Found> found, Matcher m, int[] at) {
        for (Found f : found) {
            if (m.start() < f.end() && f.start() < m.end()) {
                return;                                 // already read as another form
            }
        }
        found.add(new Found(m.start(), m.end(), at));
    }

    private static Integer num(String s) {
        try {
            return s.isEmpty() ? null : Integer.valueOf(s.replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
