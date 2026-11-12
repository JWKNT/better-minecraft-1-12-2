package better112.jei;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import better112.Better112;
import better112.tree.Ing;
import better112.tree.RecipeSource;
import better112.tree.TreeRecipe;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.ingredients.Ingredients;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Recipes as JEI knows them, which covers every machine a pack has a JEI page for. */
public final class JeiRecipeSource implements RecipeSource {

    private static final int MAX_RECIPES = 400;
    private static final int MAX_OPTIONS = 48;

    public static boolean ready() {
        return BetterJeiPlugin.runtime != null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<TreeRecipe> recipesFor(Ing target) {
        List<TreeRecipe> result = new ArrayList<TreeRecipe>();
        IJeiRuntime runtime = BetterJeiPlugin.runtime;
        if (runtime == null || target == null) {
            return result;
        }
        try {
            IRecipeRegistry registry = runtime.getRecipeRegistry();
            IFocus<?> focus = target.isFluid() ? registry.createFocus(IFocus.Mode.OUTPUT, target.fluid)
                    : registry.createFocus(IFocus.Mode.OUTPUT, target.item);
            for (IRecipeCategory category : registry.getRecipeCategories(focus)) {
                ItemStack catalyst = ItemStack.EMPTY;
                for (Object object : registry.getRecipeCatalysts(category)) {
                    if (object instanceof ItemStack) {
                        catalyst = (ItemStack) object;
                        break;
                    }
                }
                List<IRecipeWrapper> wrappers = registry.getRecipeWrappers(category, focus);
                for (IRecipeWrapper wrapper : wrappers) {
                    if (result.size() >= MAX_RECIPES) {
                        return result;
                    }
                    try {
                        TreeRecipe recipe = convert(category, wrapper, catalyst);
                        if (recipe != null) {
                            result.add(recipe);
                        }
                    } catch (Throwable t) {
                        Better112.LOG.debug("Skipped a {} recipe: {}", category.getUid(), t.toString());
                    }
                }
            }
        } catch (Throwable t) {
            Better112.LOG.warn("Could not read recipes from JEI", t);
        }
        return result;
    }

    private static TreeRecipe convert(IRecipeCategory<?> category, IRecipeWrapper wrapper, ItemStack catalyst) {
        Ingredients ingredients = new Ingredients();
        wrapper.getIngredients(ingredients);
        TreeRecipe recipe = new TreeRecipe(category.getUid(), category.getTitle(), catalyst);

        for (List<ItemStack> slot : ingredients.getInputs(VanillaTypes.ITEM)) {
            List<Ing> options = new ArrayList<Ing>();
            Set<String> seen = new HashSet<String>();
            long count = 0;
            if (slot != null) {
                for (ItemStack stack : slot) {
                    Ing ing = Ing.of(stack);
                    if (ing == null) {
                        continue;
                    }
                    if (count == 0) {
                        count = stack.getCount();
                    }
                    if (seen.add(ing.key) && options.size() < MAX_OPTIONS) {
                        options.add(ing);
                    }
                }
            }
            recipe.addInput(options, count);
        }
        for (List<FluidStack> slot : ingredients.getInputs(VanillaTypes.FLUID)) {
            List<Ing> options = new ArrayList<Ing>();
            Set<String> seen = new HashSet<String>();
            long count = 0;
            if (slot != null) {
                for (FluidStack stack : slot) {
                    Ing ing = Ing.of(stack);
                    if (ing == null) {
                        continue;
                    }
                    if (count == 0) {
                        count = stack.amount;
                    }
                    if (seen.add(ing.key)) {
                        options.add(ing);
                    }
                }
            }
            recipe.addInput(options, count);
        }
        for (List<ItemStack> slot : ingredients.getOutputs(VanillaTypes.ITEM)) {
            if (slot == null) {
                continue;
            }
            for (ItemStack stack : slot) {
                Ing ing = Ing.of(stack);
                if (ing != null) {
                    recipe.addOutput(ing, stack.getCount());
                    break;
                }
            }
        }
        for (List<FluidStack> slot : ingredients.getOutputs(VanillaTypes.FLUID)) {
            if (slot == null) {
                continue;
            }
            for (FluidStack stack : slot) {
                Ing ing = Ing.of(stack);
                if (ing != null) {
                    recipe.addOutput(ing, stack.amount);
                    break;
                }
            }
        }
        return recipe.outputs.isEmpty() ? null : recipe;
    }

    @Override
    public Ing hovered(GuiScreen screen) {
        IJeiRuntime runtime = BetterJeiPlugin.runtime;
        if (runtime == null) {
            return null;
        }
        try {
            Object found = runtime.getIngredientListOverlay().getIngredientUnderMouse();
            if (found == null) {
                found = runtime.getBookmarkOverlay().getIngredientUnderMouse();
            }
            if (found == null && screen == runtime.getRecipesGui()) {
                found = runtime.getRecipesGui().getIngredientUnderMouse();
            }
            found = RecipeMarks.unwrap(found);
            if (found instanceof ItemStack) {
                return Ing.of((ItemStack) found);
            }
            if (found instanceof FluidStack) {
                return Ing.of((FluidStack) found);
            }
        } catch (Throwable t) {
            Better112.LOG.debug("Could not read the hovered JEI ingredient: {}", t.toString());
        }
        return null;
    }

    @Override
    public boolean hasTextFocus() {
        IJeiRuntime runtime = BetterJeiPlugin.runtime;
        try {
            return runtime != null && runtime.getIngredientListOverlay().hasKeyboardFocus();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void showRecipes(Ing ing) {
        show(ing, IFocus.Mode.OUTPUT);
    }

    @Override
    public void showUses(Ing ing) {
        show(ing, IFocus.Mode.INPUT);
    }

    private static void show(Ing ing, IFocus.Mode mode) {
        IJeiRuntime runtime = BetterJeiPlugin.runtime;
        if (runtime == null || ing == null) {
            return;
        }
        try {
            IRecipeRegistry registry = runtime.getRecipeRegistry();
            runtime.getRecipesGui().show(ing.isFluid() ? registry.createFocus(mode, ing.fluid) : registry.createFocus(mode, ing.item));
        } catch (Throwable t) {
            Better112.LOG.debug("Could not open JEI: {}", t.toString());
        }
    }
}
