package io.github.anilken.wynnlabs.core;

import java.util.Locale;

/** Release numbers: which versions are worth comparing and their semver order. */
public final class Versions {

    private Versions() {}

    /**
     * Whether this jar's version is worth comparing: a release number, not a dev build or 0.0.0.
     * A local build after a release ("0.2.1+dev", build metadata) compares as that release.
     */
    /**
     * A jar built locally from the source ("0.2.4+dev": the last release plus "+dev"): it never
     * looks for or installs a release, which would replace the work in progress.
     */
    public static boolean localBuild(String version) {
        return version != null && version.toLowerCase(Locale.ROOT).contains("+dev");
    }

    public static boolean checkable(String version) {
        String core = core(version);
        if (core == null) {
            return false;
        }
        String v = core.toLowerCase(Locale.ROOT);
        if (v.contains("dev") || v.contains("snapshot")) {
            return false;
        }
        int[] n = numbers(version);
        return n != null && (n[0] | n[1] | n[2]) != 0;
    }

    /** Whether {@code latest} is a newer version than {@code mine} (a leading "v" ignored). */
    public static boolean newer(String latest, String mine) {
        Integer c = compare(latest, mine);
        return c != null && c > 0;
    }

    /**
     * Semver order of two versions, numbers compared as numbers ("0.10.0" after "0.9.0"), a
     * pre-release ("1.0.0-beta") before its release, build metadata ("+mc1.21") ignored; null
     * when either is not a version.
     */
    public static Integer compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        if (x == null || y == null) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        String pa = pre(a);
        String pb = pre(b);
        if (pa.isEmpty() || pb.isEmpty()) {
            return Boolean.compare(pa.isEmpty(), pb.isEmpty());  // a release after its pre-releases
        }
        return comparePre(pa, pb);
    }

    /** The three version numbers ("v1.2" is 1.2.0), or null when it does not start with a number. */
    static int[] numbers(String version) {
        String core = core(version);
        if (core == null) {
            return null;
        }
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.", -1);
        if (parts.length == 0 || parts.length > 3) {
            return null;
        }
        int[] n = new int[3];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 9 || !parts[i].chars().allMatch(Character::isDigit)) {
                return null;
            }
            n[i] = Integer.parseInt(parts[i]);
        }
        return n;
    }

    /** The version without spaces, a leading "v" and build metadata; null when blank. */
    static String core(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        return v.isEmpty() ? null : v;
    }

    /** The pre-release part ("beta.2" of "1.0.0-beta.2"), empty for a release. */
    private static String pre(String version) {
        String core = core(version);
        int dash = core == null ? -1 : core.indexOf('-');
        return dash < 0 ? "" : core.substring(dash + 1);
    }

    /** Semver pre-release order: dot-separated, numbers as numbers and before words, shorter first. */
    private static int comparePre(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean nx = !x[i].isEmpty() && x[i].length() <= 9 && x[i].chars().allMatch(Character::isDigit);
            boolean ny = !y[i].isEmpty() && y[i].length() <= 9 && y[i].chars().allMatch(Character::isDigit);
            int c;
            if (nx && ny) {
                c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            } else if (nx != ny) {
                c = nx ? -1 : 1;
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }
}
