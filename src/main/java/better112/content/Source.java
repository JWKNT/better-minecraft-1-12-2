package better112.content;

import java.io.File;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.creativetab.CreativeTabs;

/**
 * Where imported blocks come from: a modern mod's jar, or a modern Minecraft itself for the
 * blocks the game has gained since 1.12.2. Each source has the namespace its files use and
 * a short prefix that goes in front of its blocks' names here ("bop_fir_planks",
 * "mc_cherry_planks"), so two sources can both have a "moss" without a clash.
 */
final class Source {

    final String namespace, prefix, title;
    /** The jar the blocks were read from; null while the source is absent or switched off. */
    File jar;
    /** The woods of this source ("fir", "cherry"), found by their planks. */
    final Set<String> woods = new TreeSet<String>();
    CreativeTabs tab;
    int blocks;

    Source(String namespace, String prefix, String title) {
        this.namespace = namespace;
        this.prefix = prefix;
        this.title = title;
    }

    boolean vanilla() {
        return namespace.equals("minecraft");
    }

    /** The name a block of this source is registered under, after "better112:". */
    String key(String name) {
        return prefix + "_" + name;
    }
}
