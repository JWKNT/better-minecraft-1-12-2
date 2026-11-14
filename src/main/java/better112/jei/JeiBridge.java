package better112.jei;

import better112.tree.RecipeSource;

/** The only door from the rest of the mod into JEI code; call it only when JEI is loaded. */
public final class JeiBridge {

    private JeiBridge() {}

    public static boolean ready() {
        return JeiRecipeSource.ready();
    }

    public static RecipeSource createSource() {
        return new JeiRecipeSource();
    }

    /** Ore dictionary names accepted by the JEI recipe slot under the mouse, or null. */
    public static java.util.List<String> acceptedOres() {
        return JeiTooltips.acceptedOres();
    }

    /** Self-test: every item JEI shows in its list now (hidden items left out), or an empty list. */
    public static java.util.List<net.minecraft.item.ItemStack> shownItems() {
        java.util.List<net.minecraft.item.ItemStack> out = new java.util.ArrayList<net.minecraft.item.ItemStack>();
        if (BetterJeiPlugin.runtime != null) {
            mezz.jei.api.IIngredientFilter filter = BetterJeiPlugin.runtime.getIngredientFilter();
            String typed = filter.getFilterText();
            filter.setFilterText("");
            for (Object each : filter.getFilteredIngredients()) {
                if (each instanceof net.minecraft.item.ItemStack) {
                    out.add((net.minecraft.item.ItemStack) each);
                }
            }
            filter.setFilterText(typed);
        }
        return out;
    }

    /**
     * Self-test: for each search (comma-separated), how many ingredients JEI finds and a checksum of which ones, in
     * order, to compare one build of the mod with another.
     */
    public static String searchCounts(String searches) {
        if (BetterJeiPlugin.runtime == null) {
            return "JEI not ready";
        }
        mezz.jei.api.IIngredientFilter filter = BetterJeiPlugin.runtime.getIngredientFilter();
        String typed = filter.getFilterText();
        StringBuilder out = new StringBuilder();
        for (String search : searches.split(",")) {
            filter.setFilterText(search);
            long ordered = 0;
            long tags = 0;
            int count = 0;
            for (Object each : filter.getFilteredIngredients()) {
                String key;
                if (each instanceof net.minecraft.item.ItemStack) {
                    net.minecraft.item.ItemStack stack = (net.minecraft.item.ItemStack) each;
                    key = stack.getItem().getRegistryName() + "@" + stack.getMetadata();
                    if (stack.hasTagCompound()) {
                        tags += stack.getTagCompound().toString().hashCode();
                    }
                } else if (each instanceof net.minecraftforge.fluids.FluidStack) {
                    key = "fluid:" + ((net.minecraftforge.fluids.FluidStack) each).getFluid().getName();
                } else {
                    key = each.getClass().getName();
                }
                ordered = ordered * 31 + key.hashCode();
                count++;
            }
            // In order without tags; the tags as a sum (a tag may hold a number made at random).
            out.append(out.length() == 0 ? "" : "; ").append('"').append(search).append("\" ").append(count).append(" #")
                    .append(Long.toHexString(ordered)).append(" tags #").append(Long.toHexString(tags));
        }
        filter.setFilterText(typed);
        return out.toString();
    }

    /** Recipe bookmarks listen for their key and draw their preview through this. */
    public static Object recipeMarkEvents() {
        return new RecipeMarks.Events();
    }

    /** True while the player is typing in JEI's search box. */
    public static Object searchHighlight() {
        return new SearchHighlight();
    }

    /** Self-test: type this into JEI's search, draw as if its box had the keyboard, and count the matches. */
    public static String testSearch(String text, boolean focused) {
        if (BetterJeiPlugin.runtime == null) {
            return "JEI not ready";
        }
        SearchHighlight.testFocused = focused;
        return focused ? SearchHighlight.test(text) : "highlight released";
    }

    public static boolean searching() {
        try {
            return BetterJeiPlugin.runtime != null && BetterJeiPlugin.runtime.getIngredientListOverlay().hasKeyboardFocus();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Self-test for recipe bookmarks: mark:ITEM, open, preview, state. */
    public static String testMarks(String op, String item) {
        if (BetterJeiPlugin.runtime == null) {
            return "JEI is not ready";
        }
        if (op.equals("mark")) {
            net.minecraft.item.Item found = net.minecraft.item.Item.getByNameOrId(item);
            if (found == null) {
                return "no item " + item;
            }
            mezz.jei.api.IRecipeRegistry registry = BetterJeiPlugin.runtime.getRecipeRegistry();
            BetterJeiPlugin.runtime.getRecipesGui().show(registry.createFocus(mezz.jei.api.recipe.IFocus.Mode.OUTPUT, new net.minecraft.item.ItemStack(found)));
            return RecipeMarks.testMarkFirst();
        }
        if (op.equals("open")) {
            return RecipeMarks.testOpenFirst();
        }
        if (op.equals("preview")) {
            RecipeMarks.testPreview = !RecipeMarks.testPreview;
            return "preview " + RecipeMarks.testPreview;
        }
        return RecipeMarks.state();
    }
}
