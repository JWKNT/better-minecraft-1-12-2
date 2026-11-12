package better112.tree;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;

/** Where recipes come from: JEI when it is installed, the vanilla recipe list otherwise. */
public interface RecipeSource {

    /** Every known way to make the ingredient, best guesses first. */
    List<TreeRecipe> recipesFor(Ing target);

    /** The ingredient under the mouse in this source's own overlays, or null. */
    Ing hovered(GuiScreen screen);

    /** True while the player is typing into this source's search box. */
    boolean hasTextFocus();

    void showRecipes(Ing ing);

    void showUses(Ing ing);
}
