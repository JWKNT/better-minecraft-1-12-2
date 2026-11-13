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
    private boolean written;

    ItemGrid(String domains, int page) {
        this.page = page;
        java.util.Set<String> wanted = new java.util.HashSet<String>(java.util.Arrays.asList(domains.split(",")));
        if (wanted.contains("jei") && net.minecraftforge.fml.common.Loader.isModLoaded("jei")) {
            // What a player sees: JEI's own list, without the items the pack hides.
            stacks.addAll(better112.jei.JeiBridge.shownItems());
            wanted.clear();
        }
        for (Item item : Item.REGISTRY) {
            ResourceLocation name = Item.REGISTRY.getNameForObject(item);
            // A mod's namespace, or one item's full name written with a slash ("appliedenergistics2/facade").
            if (name == null || !wanted.contains("*") && !wanted.contains(name.getNamespace()) && !wanted.contains(name.toString().replace(':', '/'))) {
                continue;
            }
            NonNullList<ItemStack> subs = NonNullList.create();
            try {
                // As JEI lists them: what each of the item's own creative tabs shows.
                for (CreativeTabs tab : item.getCreativeTabs()) {
                    if (tab != null) {
                        item.getSubItems(tab, subs);
                    }
                }
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
        if (!written) {
            written = true;
            writeLayout(cell, cols);
        }
    }

    /**
     * Writes where each item of this page sits, in screen pixels, so a script can find the cells
     * of a screenshot that show the placeholder texture and name the items in them.
     */
    private void writeLayout(int cell, int cols) {
        int factor = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        StringBuilder out = new StringBuilder();
        out.append("factor ").append(factor).append(" cell ").append(cell).append(" cols ").append(cols).append(" x0 4 y0 16\n");
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= stacks.size()) {
                break;
            }
            ItemStack stack = stacks.get(index);
            String id = String.valueOf(stack.getItem().getRegistryName()) + "@" + stack.getMetadata();
            try {
                id += stack.hasTagCompound() ? " [" + stack.getDisplayName() + "]" : "";
            } catch (Throwable t) {
                // a name that needs a world
            }
            out.append(i).append('\t').append(id).append('\n');
        }
        java.io.File file = new java.io.File(mc.gameDir, "better112-autotest/itemgrid_" + (better112.config.BetterConfig.modernTextures ? "on" : "off")
                + "_p" + page + ".txt");
        try {
            file.getParentFile().mkdirs();
            java.nio.file.Files.write(file.toPath(), out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // the self-test reports the missing file
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
