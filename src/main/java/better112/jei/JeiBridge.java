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
