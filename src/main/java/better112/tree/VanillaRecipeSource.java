package better112.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/** Crafting table and furnace recipes straight from the game, for packs without JEI. */
public final class VanillaRecipeSource implements RecipeSource {

    private Map<String, List<TreeRecipe>> index;

    private Map<String, List<TreeRecipe>> index() {
        if (index != null) {
            return index;
        }
        Map<String, List<TreeRecipe>> built = new HashMap<String, List<TreeRecipe>>();
        ItemStack table = new ItemStack(Blocks.CRAFTING_TABLE);
        for (IRecipe craft : ForgeRegistries.RECIPES) {
            try {
                Ing output = Ing.of(craft.getRecipeOutput());
                if (output == null) {
                    continue;
                }
                TreeRecipe recipe = new TreeRecipe("minecraft.crafting", "Crafting", table);
                for (Ingredient ingredient : craft.getIngredients()) {
                    List<Ing> options = new ArrayList<Ing>();
                    Set<String> seen = new HashSet<String>();
                    for (ItemStack stack : ingredient.getMatchingStacks()) {
                        Ing ing = Ing.of(stack);
                        if (ing != null && seen.add(ing.key) && options.size() < 48) {
                            options.add(ing);
                        }
                    }
                    recipe.addInput(options, 1);
                }
                if (recipe.inputs.isEmpty()) {
                    continue;
                }
                recipe.addOutput(output, craft.getRecipeOutput().getCount());
                put(built, output.key, recipe);
            } catch (Throwable ignored) {
                // a mod recipe that cannot describe itself
            }
        }
        ItemStack furnace = new ItemStack(Blocks.FURNACE);
        for (Map.Entry<ItemStack, ItemStack> smelt : FurnaceRecipes.instance().getSmeltingList().entrySet()) {
            Ing input = Ing.of(smelt.getKey());
            Ing output = Ing.of(smelt.getValue());
            if (input == null || output == null) {
                continue;
            }
            TreeRecipe recipe = new TreeRecipe("minecraft.smelting", "Smelting", furnace);
            List<Ing> options = new ArrayList<Ing>();
            options.add(input);
            recipe.addInput(options, 1);
            recipe.addOutput(output, smelt.getValue().getCount());
            put(built, output.key, recipe);
        }
        index = built;
        return built;
    }

    private static void put(Map<String, List<TreeRecipe>> map, String key, TreeRecipe recipe) {
        List<TreeRecipe> list = map.get(key);
        if (list == null) {
            list = new ArrayList<TreeRecipe>();
            map.put(key, list);
        }
        list.add(recipe);
    }

    @Override
    public List<TreeRecipe> recipesFor(Ing target) {
        List<TreeRecipe> found = target == null ? null : index().get(target.key);
        return found == null ? new ArrayList<TreeRecipe>() : found;
    }

    @Override
    public Ing hovered(GuiScreen screen) {
        return null;
    }

    @Override
    public boolean hasTextFocus() {
        return false;
    }

    @Override
    public void showRecipes(Ing ing) {}

    @Override
    public void showUses(Ing ing) {}
}
