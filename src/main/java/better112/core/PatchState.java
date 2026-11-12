package better112.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which bytecode patches were applied. Options whose patch is missing show as unavailable
 * in the menu instead of silently doing nothing.
 *
 * A pack that conflicts with a patch can switch it off by listing its id, one per line, in
 * config/better112/disabled_patches.txt (or "*" to switch all of them off).
 */
public final class PatchState {

    private static final Set<String> APPLIED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> FAILED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> DISABLED = new HashSet<String>();
    private static volatile boolean allDisabled = Boolean.getBoolean("better112.noasm");
    private static volatile File gameDir;

    private PatchState() {}

    static void init(File dir) {
        gameDir = dir;
        File file = new File(dir, "config/better112/disabled_patches.txt");
        if (!file.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.equals("*")) {
                    allDisabled = true;
                } else {
                    DISABLED.add(line);
                }
            }
        } catch (Exception e) {
            System.err.println("[Better112] Could not read " + file + ": " + e);
        }
    }

    public static File gameDir() {
        return gameDir;
    }

    static boolean enabled(String id) {
        return !allDisabled && !DISABLED.contains(id);
    }

    static void applied(String id) {
        APPLIED.add(id);
    }

    static void failed(String id) {
        FAILED.add(id);
    }

    /** True once the class holding this patch has loaded and the patch went in. */
    public static boolean isApplied(String id) {
        return APPLIED.contains(id);
    }

    public static boolean hasFailed(String id) {
        return FAILED.contains(id) || !enabled(id);
    }

    public static Set<String> appliedIds() {
        return Collections.unmodifiableSet(APPLIED);
    }

    public static Set<String> failedIds() {
        return Collections.unmodifiableSet(FAILED);
    }
}
