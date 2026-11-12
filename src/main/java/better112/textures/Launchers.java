package better112.textures;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Where launchers keep their files, so modern jars already on the machine can be found. */
final class Launchers {

    private Launchers() {}

    /** Launcher data folders, most likely first. Not all of them exist. */
    static Set<File> roots(File gameDir) {
        Set<File> roots = new LinkedHashSet<File>();
        // Prism Launcher / MultiMC keep instances under <root>/instances/<name>/(.)minecraft.
        File up = gameDir.getAbsoluteFile();
        for (int i = 0; i < 4 && up != null; i++) {
            roots.add(up);
            up = up.getParentFile();
        }
        String home = System.getProperty("user.home", "");
        String appData = System.getenv("APPDATA");
        String[] candidates = {
            home + "/Library/Application Support/minecraft",
            home + "/Library/Application Support/PrismLauncher",
            home + "/Library/Application Support/multimc",
            home + "/Library/Application Support/ATLauncher",
            home + "/Library/Application Support/gdlauncher_next/datastore",
            home + "/Library/Application Support/com.modrinth.theseus",
            home + "/Library/Application Support/ModrinthApp",
            home + "/.minecraft",
            home + "/.local/share/PrismLauncher",
            home + "/.local/share/multimc",
            home + "/.local/share/ModrinthApp",
            home + "/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher",
            home + "/.var/app/com.mojang.Minecraft/.minecraft",
            home + "/curseforge/minecraft/Install",
            home + "/curseforge/minecraft",
            home + "/Documents/curseforge/minecraft/Install",
            home + "/Documents/curseforge/minecraft",
            appData == null ? null : appData + "/.minecraft",
            appData == null ? null : appData + "/PrismLauncher",
            appData == null ? null : appData + "/ATLauncher",
            appData == null ? null : appData + "/gdlauncher_next/datastore",
            appData == null ? null : appData + "/ModrinthApp",
            appData == null ? null : appData + "/com.modrinth.theseus",
        };
        for (String candidate : candidates) {
            if (candidate != null) {
                roots.add(new File(candidate));
            }
        }
        return roots;
    }

    /** The mods folders of every other instance the launchers hold. */
    static List<File> modFolders(File gameDir) {
        List<File> folders = new ArrayList<File>();
        File own = new File(gameDir, "mods").getAbsoluteFile();
        for (File root : roots(gameDir)) {
            for (String container : new String[] {"instances", "Instances", "profiles"}) {
                File[] instances = new File(root, container).listFiles();
                if (instances == null) {
                    continue;
                }
                for (File instance : instances) {
                    for (String sub : new String[] {"minecraft/mods", ".minecraft/mods", "mods"}) {
                        File mods = new File(instance, sub);
                        if (mods.isDirectory() && !mods.getAbsoluteFile().equals(own) && !folders.contains(mods)) {
                            folders.add(mods);
                        }
                    }
                }
            }
        }
        return folders;
    }
}
