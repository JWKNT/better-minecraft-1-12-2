package better112.test;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;

/** Self-test screen: every item of the given mods in a grid, so a screenshot shows their textures. */
final class ItemGrid extends GuiScreen {

    private final List<ItemStack> stacks = new ArrayList<ItemStack>();
    private final String title;
    private final int page;
    private int perPage;

    ItemGrid(String domains, int page) {
        this.page = page;
        java.util.Set<String> wanted = new java.util.HashSet<String>(java.util.Arrays.asList(domains.split(",")));
        for (Item item : Item.REGISTRY) {
            ResourceLocation name = Item.REGISTRY.getNameForObject(item);
            if (name == null || !wanted.contains(name.getNamespace())) {
                continue;
            }
            NonNullList<ItemStack> subs = NonNullList.create();
            try {
                item.getSubItems(CreativeTabs.SEARCH, subs);
            } catch (Throwable t) {
                // some items need a world to list their kinds
            }
            if (subs.isEmpty()) {
                subs.add(new ItemStack(item));
            }
            stacks.addAll(subs);
        }
        title = domains + " page " + page;
    }

    int count() {
        return stacks.size();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF30303A);
        drawString(fontRenderer, title + " (" + stacks.size() + " items)", 4, 4, 0xFFFFFF);
        int cell = 18, cols = Math.max(1, (width - 8) / cell), rows = Math.max(1, (height - 18) / cell);
        perPage = cols * rows;
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= stacks.size()) {
                break;
            }
            try {
                itemRender.renderItemAndEffectIntoGUI(stacks.get(index), 4 + (i % cols) * cell, 16 + (i / cols) * cell);
            } catch (Throwable t) {
                // an item that cannot draw without a world is skipped
            }
        }
        RenderHelper.disableStandardItemLighting();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
