package better112.jei;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import mezz.jei.gui.ingredients.GuiIngredient;
import mezz.jei.gui.recipes.RecipeLayout;
import mezz.jei.gui.recipes.RecipesGui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import org.lwjgl.input.Mouse;

/**
 * Modern JEI tells you when a recipe slot takes a whole tag rather than one item. This does
 * the same for 1.12.2: hover an ingredient that cycles through alternatives and the tooltip
 * names the ore dictionary entry they share.
 *
 * It reads one private field of JEI's recipe screen, so every step is guarded; if a JEI
 * fork has moved things, the extra line simply does not appear.
 */
final class JeiTooltips {

    private JeiTooltips() {}

    private static Field layoutsField;
    private static boolean broken;

    /** Ore names accepted by the recipe slot under the mouse, or null. */
    @SuppressWarnings("unchecked")
    static List<String> acceptedOres() {
        if (broken) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            GuiScreen screen = mc.currentScreen;
            if (!(screen instanceof RecipesGui)) {
                return null;
            }
            if (layoutsField == null) {
                layoutsField = RecipesGui.class.getDeclaredField("recipeLayouts");
                layoutsField.setAccessible(true);
            }
            int mouseX = Mouse.getX() * screen.width / mc.displayWidth;
            int mouseY = screen.height - Mouse.getY() * screen.height / mc.displayHeight - 1;
            for (RecipeLayout layout : (List<RecipeLayout>) layoutsField.get(screen)) {
                GuiIngredient<?> slot = layout.getGuiIngredientUnderMouse(mouseX, mouseY);
                if (slot == null) {
                    continue;
                }
                if (!slot.isInput()) {
                    return null;
                }
                List<?> all = slot.getAllIngredients();
                if (all.size() < 2) {
                    return null;
                }
                int[] common = null;
                for (Object object : all) {
                    if (!(object instanceof ItemStack) || ((ItemStack) object).isEmpty()) {
                        return null;
                    }
                    int[] ids = OreDictionary.getOreIDs((ItemStack) object);
                    common = common == null ? ids : intersect(common, ids);
                    if (common.length == 0) {
                        return null;
                    }
                }
                List<String> names = new ArrayList<String>();
                for (int id : common) {
                    names.add(OreDictionary.getOreName(id));
                }
                return names;
            }
        } catch (Throwable t) {
            broken = true;
        }
        return null;
    }

    private static int[] intersect(int[] a, int[] b) {
        int[] out = new int[Math.min(a.length, b.length)];
        int n = 0;
        for (int x : a) {
            for (int y : b) {
                if (x == y) {
                    out[n++] = x;
                    break;
                }
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
