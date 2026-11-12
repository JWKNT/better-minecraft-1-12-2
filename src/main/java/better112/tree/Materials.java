package better112.tree;

import net.minecraftforge.oredict.OreDictionary;

/** Rules of thumb that keep an automatically expanded tree from wandering off. */
final class Materials {

    private Materials() {}

    private static final String[] BASE_PREFIXES = {"ingot", "gem", "dust", "nugget", "ore", "crystal"};

    /**
     * Ingots, gems, dusts and the like. A pack has a dozen ways to make each one (ore
     * doubling, recycling, compressing nuggets), and none of them is what a player means by
     * "what do I need". They stay raw unless the player picks a recipe by hand.
     */
    static boolean isBase(Ing ing) {
        if (ing.isFluid()) {
            return true;
        }
        try {
            for (int id : OreDictionary.getOreIDs(ing.item)) {
                String name = OreDictionary.getOreName(id);
                for (String prefix : BASE_PREFIXES) {
                    if (name.startsWith(prefix) && name.length() > prefix.length()
                            && Character.isUpperCase(name.charAt(prefix.length()))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // an item the ore dictionary cannot look up is simply not a base material
        }
        return false;
    }

    /** Unpacking a storage block, packing nuggets back up, or melting down a tool. */
    static boolean isRoundabout(TreeRecipe recipe, String target) {
        if (recipe.inputs.size() != 1) {
            return false;
        }
        TreeRecipe.Input only = recipe.inputs.get(0);
        if (only.count == 1 && recipe.outputCount(target) >= 4) {
            return true;
        }
        if ((only.count == 9 || only.count == 4) && recipe.outputCount(target) == 1) {
            return true;
        }
        Ing first = only.options.get(0);
        return !first.isFluid() && first.item.getMaxStackSize() == 1;
    }
}
