package better112.content;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * Where the modern jars are: first the jars put in better112/modern_mods, then, in the game,
 * the launchers on this computer. A dedicated server has no launcher, so it needs the same
 * Minecraft client jar and Biomes O' Plenty jar the players' games read in that folder, so that
 * both sides make the same blocks and biomes.
 */
public final class GameJars {

    private GameJars() {}

    /** A file every modern Minecraft jar with data-driven terrain has. */
    public static final String WORLDGEN_PROBE = "data/minecraft/worldgen/density_function/overworld/sloped_cheese.json";
    private static final String ASSETS_PROBE = "assets/minecraft/blockstates/stone.json";

    public static File dropFolder(File gameDir) {
        return new File(gameDir, "better112/modern_mods");
    }

    /**
     * Modern Minecraft jars, the one to read first. A Minecraft client jar in better112/modern_mods
     * comes first on both sides, so a server and its players can agree on one version; in the
     * game the launchers' versions follow, newest first.
     */
    public static List<File> games(File gameDir) {
        List<File> found = new ArrayList<File>();
        File[] files = dropFolder(gameDir).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".jar") && has(file, ASSETS_PROBE) && has(file, WORLDGEN_PROBE)) {
                    found.add(file);
                }
            }
        }
        Collections.sort(found, Collections.reverseOrder());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            found.addAll(better112.textures.ModernTextures.modernGameJars(gameDir));
        }
        return found;
    }

    /** Folders that may hold the jar of a modern mod: the drop folder first, then other launcher instances. */
    public static List<File> modFolders(File gameDir) {
        List<File> folders = new ArrayList<File>();
        folders.add(dropFolder(gameDir));
        if (FMLCommonHandler.instance().getSide().isClient()) {
            folders.addAll(better112.textures.ModernTextures.modernModFolders(gameDir));
        }
        return folders;
    }

    public static boolean has(File jar, String entry) {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getEntry(entry) != null;
        } catch (IOException e) {
            return false;
        }
    }
}
