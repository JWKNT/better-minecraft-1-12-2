package better112.jei;

import better112.tree.Ing;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.ISubtypeRegistry;
import mezz.jei.api.JEIPlugin;

/** Found by JEI when it is installed; never loaded otherwise. */
@JEIPlugin
public class BetterJeiPlugin implements IModPlugin {

    static volatile IJeiRuntime runtime;

    @Override
    public void registerItemSubtypes(ISubtypeRegistry subtypeRegistry) {
        // Lets the tree tell apart items that differ only by NBT, the same way JEI does.
        Ing.subtype = subtypeRegistry::getSubtypeInfo;
    }

    @Override
    public void registerIngredients(mezz.jei.api.ingredients.IModIngredientRegistration registration) {
        RecipeMarks.registerType(registration);
    }

    @Override
    public void register(mezz.jei.api.IModRegistry registry) {
        RecipeMarks.register(registry);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }
}
