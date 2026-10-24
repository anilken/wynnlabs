package io.github.anilken.wynnlabs.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Puts a verified download in place of the running jar when the game closes. The jar is still
 * open while the game runs, so this happens in a JVM shutdown hook, registered once and only
 * after a verified download. The step re-checks the pending jar's hash, keeps a backup in
 * {@code updates/previous/}, tries an atomic replace and, when Windows refuses it, writes the
 * bytes over the old file; the target is hashed afterwards and the backup goes back on a
 * mismatch, so a failed install leaves a mod that still starts. Every end is one log line and
 * {@code updates/outcome.json}, which the next run reads once to tell the player.
 */
public final class UpdateInstall {

    /** What the last install did: the release, whether it worked, why not, and when. */
    record Outcome(String tag, boolean ok, String reason, long time) {}

    /** What the hook installs: the verified jar, its hash, the jar to replace, the release. */
    record Plan(Path pending, String sha256, Path target, Path dir, String tag) {}

    /** The two ways of putting the new bytes in place; tests make either one fail. */
    interface Writer {
        /** Copy to a sibling ".wlnew" (a name the loader never loads), then an atomic move onto the target. */
        void replace(Path from, Path target) throws IOException;

        /** Write the bytes into the existing file (allowed on Windows where a rename often is not). */
        void overwrite(Path from, Path target) throws IOException;
    }

    static Writer writer = new Writer() {
        @Override
        public void replace(Path from, Path target) throws IOException {
            Path tmp = target.resolveSibling(target.getFileName() + ".wlnew");
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Override
        public void overwrite(Path from, Path target) throws IOException {
            writeInto(from, target);
        }
    };
    /** Registers the shutdown hook; tests only count. */
    static Consumer<Thread> hooks = t -> Runtime.getRuntime().addShutdownHook(t);

    private static volatile Plan plan;
    private static boolean hooked;
    private static boolean greeted;
    /** The release whose install failed last run, for the About page; null when none. */
    private static volatile String failedTag;

    private UpdateInstall() {}

    /**
     * Whether {@code jar} may be replaced: a regular {@code .jar} file inside {@code mods}
     * (never a folder, a jar elsewhere or a file of another kind).
     */
    static boolean installable(Path jar, Path mods) {
        if (jar == null || mods == null) {
            return false;
        }
        try {
            Path j = jar.toAbsolutePath().normalize();
            Path m = mods.toAbsolutePath().normalize();
            return Files.isRegularFile(j) && j.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    && j.startsWith(m) && !j.equals(m);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * After a verified download: the install of {@code tag} when the game closes. Nothing for a
     * dev build or without a jar to replace. The hook is registered once; a later call only
     * changes what it installs.
     */
    static synchronized boolean schedule(String version, String tag, String sha256, Optional<Path> jar) {
        if (!Versions.checkable(version) || Versions.localBuild(version) || jar == null || jar.isEmpty()) {
            return false;
        }
        Path dir = UpdatePrefs.folder();
        plan = new Plan(dir.resolve(UpdateDownload.PENDING), sha256, jar.get(), dir, tag);
        // the classes the hook uses are loaded now: later they would be read from the very jar it overwrites
        java.util.Objects.requireNonNull(new Outcome(tag, false, "", 0).tag());
        if (!hooked) {
            hooked = true;
            hooks.accept(new Thread(UpdateInstall::onExit, "wynnlabs-update-install"));
        }
        return true;
    }

    /** Whether an install waits for the game to close. */
    public static boolean scheduled() {
        return plan != null;
    }

    private static void onExit() {
        Plan p = plan;
        if (p != null) {
            install(p, writer);
        }
    }

    /** The install step itself, on files only (the hook calls it; tests call it on temp files). */
    static Outcome install(Plan p, Writer w) {
        Path backup = p.dir().resolve("previous").resolve(p.target().getFileName().toString());
        Outcome o;
        try {
            if (!Files.isRegularFile(p.pending()) || !p.sha256().equals(UpdateDownload.sha256(p.pending()))) {
                o = new Outcome(p.tag(), false, "hash", System.currentTimeMillis());
            } else {
                Files.createDirectories(backup.getParent());
                Files.copy(p.target(), backup, StandardCopyOption.REPLACE_EXISTING);
                String how = "move";
                try {
                    w.replace(p.pending(), p.target());
                } catch (IOException moveFailed) {
                    how = "copy-over";
                    try {
                        w.overwrite(p.pending(), p.target());
                    } catch (IOException writeFailed) {
                        how = "none";
                    }
                }
                if (p.sha256().equals(UpdateDownload.sha256(p.target()))) {
                    Files.deleteIfExists(p.pending());
                    Files.deleteIfExists(backup);
                    o = new Outcome(p.tag(), true, how, System.currentTimeMillis());
                } else {
                    if (!UpdateDownload.sha256(backup).equals(UpdateDownload.sha256(p.target()))) {
                        writeInto(backup, p.target());  // a half-written jar: the old bytes go back
                    }
                    o = new Outcome(p.tag(), false, "locked", System.currentTimeMillis());
                }
            }
        } catch (IOException | RuntimeException e) {
            o = new Outcome(p.tag(), false, "io", System.currentTimeMillis());
        }
        System.out.println("[WynnLabs] update: " + (o.ok() ? "installed " + p.tag() + " (" + o.reason() + ")"
                : "could not install " + p.tag() + " (" + o.reason() + "); the old jar stays, the new one is in " + p.dir()));
        write(p.dir(), o);
        return o;
    }

    /** Writes {@code from}'s bytes into {@code target}, keeping the file itself. */
    static void writeInto(Path from, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            Files.copy(from, out);
        }
    }

    private static void write(Path dir, Outcome o) {
        try {
            JsonObject j = new JsonObject();
            j.addProperty("tag", o.tag());
            j.addProperty("result", o.ok() ? "ok" : "failed");
            j.addProperty("reason", o.reason());
            j.addProperty("time", o.time());
            Files.createDirectories(dir);
            Path tmp = dir.resolve("outcome.json.tmp");
            Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dir.resolve("outcome.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dir.resolve("outcome.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[WynnLabs] update: could not write the outcome: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The last install's outcome, read once and then removed; null when there is none or it does
     * not concern this run (an "ok" for another version than the one running).
     */
    static Outcome readOutcome(Path dir, String running) {
        Path f = dir.resolve("outcome.json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            JsonObject j = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            Outcome o = new Outcome(j.get("tag").getAsString(), "ok".equals(j.get("result").getAsString()),
                    j.has("reason") ? j.get("reason").getAsString() : "", j.has("time") ? j.get("time").getAsLong() : 0);
            Integer same = Versions.compare(o.tag(), running);
            return o.ok() && (same == null || same != 0) ? null : o;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignored) {
                // read again next run: harmless
            }
        }
    }

    /** On the first tick in a world: "Updated to v0.2.5", or that last run's install failed. Once per run. */
    public static void greet(Minecraft mc) {
        if (greeted) {
            return;
        }
        greeted = true;
        Outcome o = readOutcome(UpdatePrefs.folder(), UpdateCheck.version.get());
        if (o == null) {
            return;
        }
        if (o.ok()) {
            mc.gui.getChat().addMessage(L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD)
                    .append(L.c("wynnlabs.update.installed", o.tag()).withStyle(ChatFormatting.GREEN)));
        } else {
            failedTag = o.tag();
            mc.gui.getChat().addMessage(L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD)
                    .append(L.c("wynnlabs.update.install_failed", o.tag()).withStyle(ChatFormatting.RED)));
        }
    }

    /** The release whose install failed last run (the About page offers the updates folder), else null. */
    public static String failedTag() {
        return failedTag;
    }

    /** Opens the updates folder in the system's file browser, so the jar can be copied by hand. */
    public static void openFolder() {
        try {
            Path dir = UpdatePrefs.folder();
            Files.createDirectories(dir);
            net.minecraft.util.Util.getPlatform().openPath(dir);
        } catch (Throwable e) {
            System.out.println("[WynnLabs] update: could not open the updates folder: " + e.getClass().getSimpleName());
        }
    }

    /** The preview's About shots: a failed install of {@code tag} (null: none). */
    public static void preview(String tag) {
        failedTag = tag;
    }

    /** Tests: as a fresh run. */
    static synchronized void reset() {
        plan = null;
        hooked = false;
        greeted = false;
        failedTag = null;
    }
}
