package better112.jei;

import java.util.HashSet;
import java.util.Set;

import better112.config.BetterConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiContainerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * JEI's search, in the open inventory too, as EMI and REI do it: while JEI's search box has the keyboard, the
 * items around you that the search does not match are dimmed and the ones it does are outlined. The search
 * keeps JEI's own syntax (@mod, #tooltip, $ore).
 */
public final class SearchHighlight {

    private static String forText;
    private static final Set<String> MATCHES = new HashSet<String>();
    private static final Set<net.minecraft.item.Item> MATCHED_ITEMS = new HashSet<net.minecraft.item.Item>();

    /** Item and damage value, which is how JEI's list tells kinds of an item apart. */
    private static String key(ItemStack stack) {
        return stack.getItem().getRegistryName() + "@" + stack.getMetadata();
    }

    private static void refresh(String text) {
        if (text.equals(forText)) {
            return;
        }
        forText = text;
        MATCHES.clear();
        MATCHED_ITEMS.clear();
        for (Object ingredient : BetterJeiPlugin.runtime.getIngredientFilter().getFilteredIngredients()) {
            if (ingredient instanceof ItemStack && !((ItemStack) ingredient).isEmpty()) {
                ItemStack stack = (ItemStack) ingredient;
                MATCHES.add(key(stack));
                MATCHED_ITEMS.add(stack.getItem());
            }
        }
    }

    static boolean matches(ItemStack stack) {
        // A worn tool has a damage value JEI's list never shows: match damageable items by item alone.
        return MATCHES.contains(key(stack)) || stack.isItemStackDamageable() && MATCHED_ITEMS.contains(stack.getItem());
    }

    @SubscribeEvent
    public void onForeground(GuiContainerEvent.DrawForeground event) {
        if (!BetterConfig.jeiSearchHighlight || BetterJeiPlugin.runtime == null || !(testFocused || JeiBridge.searching())) {
            return;
        }
        String text = BetterJeiPlugin.runtime.getIngredientFilter().getFilterText().trim();
        if (text.isEmpty()) {
            return;
        }
        refresh(text);
        GuiContainer gui = event.getGuiContainer();
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 280.0F);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            if (!slot.isEnabled() || !slot.getHasStack()) {
                continue;
            }
            int x = slot.xPos, y = slot.yPos;
            if (matches(slot.getStack())) {
                int c = 0xFFFFE066;
                Gui.drawRect(x - 1, y - 1, x + 17, y, c);
                Gui.drawRect(x - 1, y + 16, x + 17, y + 17, c);
                Gui.drawRect(x - 1, y, x, y + 16, c);
                Gui.drawRect(x + 16, y, x + 17, y + 16, c);
            } else {
                Gui.drawRect(x, y, x + 16, y + 16, 0xB0202020);
            }
        }
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    /** Self-test: with this search typed, how many of the open screen's items match. */
    public static String test(String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        BetterJeiPlugin.runtime.getIngredientFilter().setFilterText(text);
        refresh(text);
        int match = 0, other = 0;
        if (mc.currentScreen instanceof GuiContainer) {
            for (Slot slot : ((GuiContainer) mc.currentScreen).inventorySlots.inventorySlots) {
                if (slot.getHasStack()) {
                    if (matches(slot.getStack())) {
                        match++;
                    } else {
                        other++;
                    }
                }
            }
        }
        return "'" + text + "': " + MATCHES.size() + " kinds listed, " + match + " items in the screen match, " + other + " dimmed";
    }

    /** Self-test: draw as if JEI's search box had the keyboard. */
    public static boolean testFocused;
}
