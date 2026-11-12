package better112.tree;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import better112.Better112;

import net.minecraftforge.fml.common.Loader;

/**
 * The recipe the player last chose for each item. Once you have said "iron plates come from
 * the press", every later tree uses the press without asking.
 */
public final class RecipeDefaults {

    private RecipeDefaults() {}

    private static Map<String, String> signatures;

    private static File file() {
        return new File(Loader.instance().getConfigDir(), "better112/recipe_defaults.txt");
    }

    private static Map<String, String> map() {
        if (signatures == null) {
            signatures = new HashMap<String, String>();
            File file = file();
            if (file.isFile()) {
                try {
                    for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                        int tab = line.indexOf('\t');
                        if (tab > 0) {
                            signatures.put(line.substring(0, tab), line.substring(tab + 1));
                        }
                    }
                } catch (Exception e) {
                    Better112.LOG.warn("Could not read {}", file, e);
                }
            }
        }
        return signatures;
    }

    public static boolean has(String key) {
        return map().containsKey(key);
    }

    /** The remembered recipe among the candidates, or null. */
    public static TreeRecipe find(String key, List<TreeRecipe> candidates) {
        String wanted = map().get(key);
        if (wanted == null) {
            return null;
        }
        for (TreeRecipe recipe : candidates) {
            if (recipe.signature().equals(wanted)) {
                return recipe;
            }
        }
        return null;
    }

    public static void set(String key, TreeRecipe recipe) {
        if (recipe == null) {
            if (map().remove(key) == null) {
                return;
            }
        } else {
            map().put(key, recipe.signature());
        }
        save();
    }

    private static void save() {
        try {
            File file = file();
            file.getParentFile().mkdirs();
            List<String> lines = new ArrayList<String>();
            for (Map.Entry<String, String> e : new java.util.TreeMap<String, String>(signatures).entrySet()) {
                lines.add(e.getKey() + "\t" + e.getValue());
            }
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Better112.LOG.warn("Could not save recipe defaults", e);
        }
    }
}
