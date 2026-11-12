package better112.world.gen;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import better112.Better112;
import better112.textures.ModernTextures;
import better112.world.ModernTerrain;
import better112.world.density.WorldData;

import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

/**
 * Finds the modern game's worldgen data and keeps a copy of it next to the texture cache,
 * so a world made with it keeps generating the same way even if the modern version is later
 * removed from the launcher. Nothing of Minecraft's is shipped in the mod: the data comes
 * from the player's own installed copy.
 */
public final class ModernWorld {

    private ModernWorld() {}

    private static final String PROBE = "data/minecraft/worldgen/density_function/overworld/sloped_cheese.json";
    private static WorldData data;
    private static String source = "";

    /**
     * Makes sure the copy of the modern game's worldgen data exists and returns it, or null if
     * no modern Minecraft is installed. Safe to call early: biomes are registered before the
     * world type is.
     */
    public static synchronized File cache() {
        try {
            File cache = cacheFile();
            if (!cache.isFile()) {
                File jar = findJar();
                if (jar == null) {
                    return null;
                }
                copyWorldgen(jar, cache);
                Better112.LOG.info("Modern worldgen data taken from {}", jar);
            }
            return cache;
        } catch (IOException e) {
            Better112.LOG.warn("Modern worldgen data could not be copied", e);
            return null;
        }
    }

    /** Registers the world type if modern worldgen data can be found. */
    public static void init() {
        // Always registered, so a world of this type is never quietly opened as a default world.
        new ModernWorldType();
        try {
            File cache = cache();
            if (cache == null) {
                Better112.LOG.info("Modern Terrain world type not offered: no Minecraft 1.19 or newer found on this computer.");
                return;
            }
            WorldData found = new WorldData(Collections.singletonList(cache));
            try (ZipFile zip = new ZipFile(cache)) {
                source = zip.getComment() == null ? "" : zip.getComment();
            }
            // Build one terrain now: a data format this mod cannot read should show up here, in the
            // log, with the world type left out of the list, not as a crash when a world is made.
            ModernTerrain trial = new ModernTerrain(found, "minecraft:overworld", 0L);
            trial.startChunk(0, 0, 256);
            trial.density(8, 64, 8);
            trial.roughSurface(0, 0);
            data = found;
            Better112.LOG.info("Modern Terrain world type ready ({}).", source);
        } catch (Throwable t) {
            Better112.LOG.warn("Modern Terrain world type not available", t);
        }
    }

    /** The modern game's worldgen data with a mod's laid over it, or null if the game's is missing. */
    static WorldData dataWith(File modJar) throws IOException {
        File cache = cacheFile();
        List<File> sources = new java.util.ArrayList<File>();
        if (cache.isFile()) {
            sources.add(cache);
        }
        if (modJar != null) {
            sources.add(modJar); // enough by itself for the mod's own features: a sapling can still grow
        }
        return sources.isEmpty() ? null : new WorldData(sources);
    }

    public static boolean available() {
        return data != null;
    }

    /** Where the data came from, for the log and the menu. */
    public static String source() {
        return source;
    }

    static ModernTerrain terrain(World world) {
        if (data == null) {
            throw new IllegalStateException("This world uses the Modern Terrain world type of Better 1.12.2, which needs the worldgen data of "
                    + "Minecraft 1.19 or newer. Install a modern Minecraft version in your launcher (or restore better112/modern_worldgen.zip) "
                    + "and start the game again. The world was not opened so that it does not continue with different terrain.");
        }
        return new ModernTerrain(data, "minecraft:overworld", world.getSeed());
    }

    private static File cacheFile() {
        File dir = new File(Loader.instance().getConfigDir().getParentFile(), "better112");
        dir.mkdirs();
        return new File(dir, "modern_worldgen.zip");
    }

    /** The newest installed Minecraft whose jar has data-driven terrain (1.19+). */
    private static File findJar() {
        List<File> jars = ModernTextures.modernGameJars(Loader.instance().getConfigDir().getParentFile());
        for (File jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                if (zip.getEntry(PROBE) != null) {
                    return jar;
                }
            } catch (IOException ignored) {
                // not a readable jar; try the next
            }
        }
        return null;
    }

    private static void copyWorldgen(File jar, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        byte[] buffer = new byte[8192];
        try (ZipFile zip = new ZipFile(jar); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
            out.setComment("from " + jar.getName());
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("data/") || !name.contains("/worldgen/") || !name.endsWith(".json")) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = zip.getInputStream(entry)) {
                    for (int n; (n = in.read(buffer)) > 0;) {
                        out.write(buffer, 0, n);
                    }
                }
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("better112.txt"));
            out.write(("Worldgen data copied from " + jar.getAbsolutePath() + " by Better 1.12.2.\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.deleteIfExists(target.toPath());
        if (!temp.renameTo(target)) {
            throw new IOException("could not move " + temp + " into place");
        }
    }
}
