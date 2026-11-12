package better112.tree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * The recipe tree: the goal at the top, what each step needs underneath, and the full
 * shopping list on the left. Click an ingredient to choose how it is made.
 */
public class RecipeTreeScreen extends GuiScreen {

    private static final int SLOT = 22;
    private static final int HGAP = 6;
    private static final int VGAP = 48;
    private static final int PANEL = 128;
    private static final int ROW = 18;
    private static final int ACCENT = 0xFF94E4D3;

    private final GuiScreen parent;
    private final RecipeTree tree;

    private RecipeTree.Node rootNode;
    private RecipeTree.Totals totals = new RecipeTree.Totals();
    private final List<RecipeTree.Node> nodes = new ArrayList<RecipeTree.Node>();
    private final Map<String, Long> have = new HashMap<String, Long>();
    /** Stock of the ME network, when the tree was opened from an AE2 terminal. */
    private final NetworkStock network;
    private int inventoryAge;

    private float zoom = 1.0F;
    private float panX, panY;
    private boolean centered;
    private boolean pressed;
    private boolean dragMoved;
    private int pressX, pressY, pressButton;
    private float pressPanX, pressPanY;
    private RecipeTree.Node pressedNode;

    private int panelScroll;
    private boolean showSteps;

    private Chooser chooser;

    private Ing hoveredIng;
    private List<String> hoveredExtra;

    private static final class Chooser {
        RecipeTree.Node node;
        List<TreeRecipe> recipes;
        int scroll;
        int x, y, w, h;
    }

    public RecipeTreeScreen(GuiScreen parent, RecipeTree tree) {
        this.parent = parent;
        this.tree = tree;
        this.network = NetworkStock.read(parent);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Self-test: what was read from the ME network behind this tree. */
    public String testNetwork() {
        if (network == null) {
            return "no ME network behind this screen (" + (parent == null ? "none" : parent.getClass().getName()) + ")";
        }
        StringBuilder out = new StringBuilder(network.amounts.size() + " kinds stored, " + network.craftable.size() + " craftable:");
        int shown = 0;
        for (Map.Entry<String, Long> entry : network.amounts.entrySet()) {
            if (shown++ < 6) {
                out.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        out.append("; raw materials:");
        if (totals != null) {
            for (RecipeTree.Line line : totals.raw) {
                out.append(' ').append(line.ing.key).append(" need ").append(line.amount).append(" have ").append(have.get(line.ing.key));
            }
        }
        return out.toString();
    }

    @Override
    public void initGui() {
        refresh();
        if (!centered && rootNode != null) {
            centered = true;
            panX = PANEL + (width - PANEL) / 2.0F - (rootNode.x + SLOT / 2.0F) * zoom;
            panY = 30;
        }
    }

    private void refresh() {
        // Keep the goal where it is on screen when the tree under it changes shape.
        int oldRootX = rootNode == null ? Integer.MIN_VALUE : rootNode.x;
        countInventory();
        tree.owned = new java.util.HashSet<String>(have.keySet());
        rootNode = tree.build();
        totals = tree.totals();
        nodes.clear();
        if (rootNode != null) {
            measure(rootNode);
            place(rootNode, 0, 0);
            if (oldRootX != Integer.MIN_VALUE) {
                panX += (oldRootX - rootNode.x) * zoom;
            }
        }
        countInventory();
    }

    private int measure(RecipeTree.Node node) {
        if (node.children.isEmpty()) {
            return node.width = SLOT + 8;
        }
        int sum = HGAP * (node.children.size() - 1);
        for (RecipeTree.Node child : node.children) {
            sum += measure(child);
        }
        return node.width = Math.max(SLOT + 8, sum);
    }

    private void place(RecipeTree.Node node, int left, int depth) {
        node.x = left + (node.width - SLOT) / 2;
        node.y = depth * VGAP;
        nodes.add(node);
        int childrenWidth = HGAP * Math.max(0, node.children.size() - 1);
        for (RecipeTree.Node child : node.children) {
            childrenWidth += child.width;
        }
        int x = left + (node.width - childrenWidth) / 2;
        for (RecipeTree.Node child : node.children) {
            place(child, x, depth + 1);
            x += child.width + HGAP;
        }
    }

    private void countInventory() {
        have.clear();
        if (mc == null || mc.player == null) {
            return;
        }
        for (ItemStack stack : mc.player.inventory.mainInventory) {
            Ing ing = Ing.of(stack);
            if (ing != null) {
                Long old = have.get(ing.key);
                have.put(ing.key, (old == null ? 0L : old) + stack.getCount());
            }
        }
        if (network != null) {
            for (Map.Entry<String, Long> entry : network.amounts.entrySet()) {
                Long old = have.get(entry.getKey());
                have.put(entry.getKey(), (old == null ? 0L : old) + entry.getValue());
            }
        }
    }

    private boolean craftableInNetwork(Ing ing) {
        return network != null && network.craftable.contains(ing.key);
    }

    @Override
    public void updateScreen() {
        if (++inventoryAge >= 20) {
            inventoryAge = 0;
            countInventory();
            if (!have.keySet().equals(tree.owned)) {
                refresh(); // something was picked up or used up: interchangeable ingredients may now resolve differently
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        hoveredIng = null;
        hoveredExtra = null;
        boolean overCanvas = chooser == null && mouseX > PANEL + 4;

        if (rootNode == null) {
            String text = I18n.format("better112.tree.empty", RecipeTreeKeys.KEY_TREE.getDisplayName());
            drawCenteredString(fontRenderer, text, PANEL + (width - PANEL) / 2, height / 2 - 4, 0xFFAAAAAA);
        } else {
            scissor(PANEL + 4, 0, width - PANEL - 4, height);
            GlStateManager.pushMatrix();
            GlStateManager.translate(panX, panY, 0.0F);
            GlStateManager.scale(zoom, zoom, 1.0F);
            float cx = (mouseX - panX) / zoom, cy = (mouseY - panY) / zoom;
            for (RecipeTree.Node node : nodes) {
                drawLinks(node);
            }
            RecipeTree.Node hovered = null;
            for (RecipeTree.Node node : nodes) {
                boolean hover = overCanvas && cx >= node.x && cx < node.x + SLOT && cy >= node.y && cy < node.y + SLOT;
                if (hover) {
                    hovered = node;
                }
                drawNode(node, hover);
            }
            GlStateManager.popMatrix();
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (hovered != null) {
                hoveredIng = hovered.ing;
                hoveredExtra = describe(hovered);
            }
        }

        drawPanel(mouseX, mouseY);
        drawHeader(mouseX, mouseY);
        if (chooser != null) {
            drawChooser(mouseX, mouseY);
        }
        if (hoveredIng != null) {
            List<String> lines = new ArrayList<String>();
            if (hoveredIng.isFluid()) {
                lines.add(hoveredIng.name());
            } else {
                lines.addAll(getItemToolTip(hoveredIng.item));
            }
            if (hoveredExtra != null) {
                lines.addAll(hoveredExtra);
            }
            drawHoveringText(lines, mouseX, mouseY);
            RenderHelper.disableStandardItemLighting();
        }
    }

    private List<String> describe(RecipeTree.Node node) {
        List<String> lines = new ArrayList<String>();
        lines.add("");
        lines.add(TextFormatting.GRAY + I18n.format("better112.tree.need", amountText(node.ing, node.amount)));
        stockLines(lines, node.ing);
        if (node.recipe != null) {
            lines.add(TextFormatting.GRAY + I18n.format("better112.tree.makes", node.crafts, node.recipe.categoryTitle));
        } else if (node.cycle) {
            lines.add(TextFormatting.RED + I18n.format("better112.tree.cycle"));
        } else if (node.truncated) {
            lines.add(TextFormatting.RED + I18n.format("better112.tree.truncated"));
        } else {
            lines.add(TextFormatting.DARK_GRAY + I18n.format("better112.tree.raw"));
        }
        return lines;
    }

    private void stockLines(List<String> lines, Ing ing) {
        if (network == null) {
            return;
        }
        Long stored = network.amounts.get(ing.key);
        if (stored != null && stored > 0) {
            lines.add(TextFormatting.GRAY + I18n.format("better112.tree.in_network", amountText(ing, stored)));
        }
        if (network.craftable.contains(ing.key)) {
            lines.add(TextFormatting.AQUA + I18n.format("better112.tree.network_craftable"));
        }
    }

    private void drawLinks(RecipeTree.Node node) {
        if (node.children.isEmpty()) {
            return;
        }
        int color = 0xFF8A8A8A;
        int px = node.x + SLOT / 2;
        int top = node.y + SLOT + 11;
        int mid = node.y + VGAP - 8;
        Gui.drawRect(px, top, px + 1, mid, color);
        int first = node.children.get(0).x + SLOT / 2;
        int last = node.children.get(node.children.size() - 1).x + SLOT / 2;
        Gui.drawRect(Math.min(first, px), mid, Math.max(last, px) + 1, mid + 1, color);
        for (RecipeTree.Node child : node.children) {
            int cx = child.x + SLOT / 2;
            Gui.drawRect(cx, mid, cx + 1, child.y, color);
        }
        if (!node.recipe.catalyst.isEmpty()) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(px + 3, top, 0.0F);
            GlStateManager.scale(0.5F, 0.5F, 1.0F);
            drawItem(node.recipe.catalyst, 0, 0);
            GlStateManager.popMatrix();
        }
    }

    private void drawNode(RecipeTree.Node node, boolean hover) {
        boolean raw = node.recipe == null;
        Long owned = have.get(node.ing.key);
        boolean enough = owned != null && owned >= node.amount;
        int border = node.cycle || node.truncated ? 0xFFE06060
                : raw ? (enough ? 0xFF55DD55 : craftableInNetwork(node.ing) ? 0xFF55CCFF : 0xFF707070) : ACCENT;
        Gui.drawRect(node.x, node.y, node.x + SLOT, node.y + SLOT, hover ? 0xE0000000 : 0xA0000000);
        Gui.drawRect(node.x, node.y, node.x + SLOT, node.y + 1, border);
        Gui.drawRect(node.x, node.y + SLOT - 1, node.x + SLOT, node.y + SLOT, border);
        Gui.drawRect(node.x, node.y, node.x + 1, node.y + SLOT, border);
        Gui.drawRect(node.x + SLOT - 1, node.y, node.x + SLOT, node.y + SLOT, border);
        drawIng(node.ing, node.x + 3, node.y + 3);
        String text = amountText(node.ing, node.amount);
        fontRenderer.drawStringWithShadow(text, node.x + SLOT / 2.0F - fontRenderer.getStringWidth(text) / 2.0F, node.y + SLOT + 2,
                raw && enough ? 0xFF55FF55 : 0xFFFFFFFF);
    }

    private void drawIng(Ing ing, int x, int y) {
        if (ing.isFluid()) {
            try {
                TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite(ing.fluid.getFluid().getStill(ing.fluid).toString());
                int color = ing.fluid.getFluid().getColor(ing.fluid);
                mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
                GlStateManager.enableBlend();
                GlStateManager.color(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
                drawTexturedModalRect(x, y, sprite, 16, 16);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            } catch (Throwable t) {
                Gui.drawRect(x, y, x + 16, y + 16, 0xFF3F76E4);
            }
        } else {
            drawItem(ing.item, x, y);
        }
    }

    private void drawItem(ItemStack stack, int x, int y) {
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        try {
            itemRender.renderItemAndEffectIntoGUI(stack, x, y);
        } catch (Throwable ignored) {
            // an item whose renderer fails here should not take the screen down
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    static String amountText(Ing ing, long amount) {
        if (ing.isFluid()) {
            return amount >= 10000 ? compact(amount / 1000.0D) + " B" : amount + " mB";
        }
        return amount >= 10000 ? compact(amount) : String.valueOf(amount);
    }

    private static String compact(double value) {
        if (value >= 1.0E9D) {
            return String.format("%.1fB", value / 1.0E9D);
        }
        if (value >= 1.0E6D) {
            return String.format("%.1fM", value / 1.0E6D);
        }
        if (value >= 1.0E4D) {
            return String.format("%.1fk", value / 1.0E3D);
        }
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.1f", value);
    }

    private void drawPanel(int mouseX, int mouseY) {
        Gui.drawRect(4, 4, PANEL, height - 4, 0xA0000000);
        String title = I18n.format(showSteps ? "better112.tree.steps" : "better112.tree.base");
        fontRenderer.drawStringWithShadow(title, 9, 9, 0xFFFFFFFF);
        String tab = "[Tab]";
        fontRenderer.drawStringWithShadow(tab, PANEL - 4 - fontRenderer.getStringWidth(tab), 9, 0xFF707070);
        Gui.drawRect(4, 20, PANEL, 21, ACCENT);

        int top = 23, bottom = height - 6;
        scissor(4, top, PANEL - 4, bottom - top);
        int y = top - panelScroll;
        boolean over = chooser == null && mouseX >= 4 && mouseX < PANEL && mouseY >= top && mouseY < bottom;
        if (showSteps) {
            for (RecipeTree.Line line : totals.steps) {
                y = drawLine(line, y, mouseX, mouseY, over, true);
            }
        } else {
            for (RecipeTree.Line line : totals.raw) {
                y = drawLine(line, y, mouseX, mouseY, over, false);
            }
            if (!totals.leftover.isEmpty()) {
                y += 4;
                fontRenderer.drawStringWithShadow(I18n.format("better112.tree.leftover"), 9, y + 2, 0xFFAAAAAA);
                y += 12;
                for (RecipeTree.Line line : totals.leftover) {
                    y = drawLine(line, y, mouseX, mouseY, over, false);
                }
            }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        int content = y + panelScroll - top;
        panelScroll = Math.max(0, Math.min(Math.max(0, content - (bottom - top)), panelScroll));
    }

    private int drawLine(RecipeTree.Line line, int y, int mouseX, int mouseY, boolean over, boolean step) {
        if (y + ROW > 0 && y < height) {
            boolean hover = over && mouseY >= y && mouseY < y + ROW;
            if (hover) {
                Gui.drawRect(4, y, PANEL, y + ROW, 0x40FFFFFF);
                hoveredIng = line.ing;
                hoveredExtra = null;
                if (step) {
                    hoveredExtra = new ArrayList<String>();
                    hoveredExtra.add("");
                    hoveredExtra.add(TextFormatting.GRAY + I18n.format("better112.tree.makes", line.crafts, line.recipe.categoryTitle));
                }
            }
            drawIng(line.ing, 8, y + 1);
            String text = amountText(line.ing, line.amount);
            int color = 0xFFFFFFFF;
            if (!step) {
                Long owned = have.get(line.ing.key);
                if (owned != null && owned > 0) {
                    color = owned >= line.amount ? 0xFF55FF55 : 0xFFFFDD55;
                    text += TextFormatting.GRAY + " / " + amountText(line.ing, owned);
                }
                if (craftableInNetwork(line.ing) && (owned == null || owned < line.amount)) {
                    color = 0xFF55CCFF;
                }
                if (hover) {
                    hoveredExtra = new ArrayList<String>();
                    stockLines(hoveredExtra, line.ing);
                }
            } else {
                String where = line.recipe.categoryTitle;
                text = fontRenderer.trimStringToWidth(text + TextFormatting.GRAY + "  " + where, PANEL - 32);
            }
            fontRenderer.drawStringWithShadow(text, 28, y + 5, color);
        }
        return y + ROW;
    }

    private void drawHeader(int mouseX, int mouseY) {
        if (tree.root() == null) {
            return;
        }
        int x = PANEL + 10, y = 8;
        String label = I18n.format("better112.tree.amount", tree.amount());
        drawSmallButton(x, y - 2, "-", mouseX, mouseY);
        fontRenderer.drawStringWithShadow(label, x + 16, y, 0xFFFFFFFF);
        drawSmallButton(x + 20 + fontRenderer.getStringWidth(label), y - 2, "+", mouseX, mouseY);
        String hint = fontRenderer.trimStringToWidth(I18n.format("better112.tree.hint"), width - PANEL - 16);
        fontRenderer.drawStringWithShadow(hint, PANEL + 10, height - 13, 0xFF808080);
    }

    private void drawSmallButton(int x, int y, String text, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + 12 && mouseY >= y && mouseY < y + 12;
        Gui.drawRect(x, y, x + 12, y + 12, hover ? 0xE0000000 : 0x90000000);
        fontRenderer.drawStringWithShadow(text, x + 6 - fontRenderer.getStringWidth(text) / 2.0F, y + 2, 0xFFFFFFFF);
    }

    private int plusButtonX() {
        return PANEL + 10 + 20 + fontRenderer.getStringWidth(I18n.format("better112.tree.amount", tree.amount()));
    }

    // ------------------------------------------------------------------------------------
    // Recipe chooser
    // ------------------------------------------------------------------------------------

    private static final int CHOICE_ROW = 22;

    private void openChooser(RecipeTree.Node node) {
        List<TreeRecipe> recipes = tree.source.recipesFor(node.ing);
        if (recipes.isEmpty() && node.recipe == null) {
            return;
        }
        Chooser c = new Chooser();
        c.node = node;
        c.recipes = recipes;
        c.w = Math.min(360, width - 24);
        int rows = Math.min(recipes.size() + 1, Math.max(3, (height - 70) / CHOICE_ROW));
        c.h = 22 + rows * CHOICE_ROW + 4;
        c.x = (width - c.w) / 2;
        c.y = (height - c.h) / 2;
        chooser = c;
    }

    private int chooserRows() {
        return (chooser.h - 26) / CHOICE_ROW;
    }

    private void drawChooser(int mouseX, int mouseY) {
        Chooser c = chooser;
        Gui.drawRect(0, 0, width, height, 0x80000000);
        Gui.drawRect(c.x - 1, c.y - 1, c.x + c.w + 1, c.y + c.h + 1, ACCENT);
        Gui.drawRect(c.x, c.y, c.x + c.w, c.y + c.h, 0xFF101014);
        String title = fontRenderer.trimStringToWidth(I18n.format("better112.tree.choose", c.node.ing.name()), c.w - 60);
        fontRenderer.drawStringWithShadow(title, c.x + 6, c.y + 7, 0xFFFFFFFF);
        int total = c.recipes.size() + 1;
        int rows = chooserRows();
        c.scroll = Math.max(0, Math.min(Math.max(0, total - rows), c.scroll));
        if (total > rows) {
            String page = (c.scroll + 1) + "-" + Math.min(total, c.scroll + rows) + " / " + total;
            fontRenderer.drawStringWithShadow(page, c.x + c.w - 6 - fontRenderer.getStringWidth(page), c.y + 7, 0xFF808080);
        }
        TreeRecipe current = tree.recipeOf(c.node.ing);
        for (int i = 0; i < rows && c.scroll + i < total; i++) {
            int index = c.scroll + i;
            int y = c.y + 22 + i * CHOICE_ROW;
            boolean hover = mouseX >= c.x && mouseX < c.x + c.w && mouseY >= y && mouseY < y + CHOICE_ROW;
            TreeRecipe recipe = index == 0 ? null : c.recipes.get(index - 1);
            boolean selected = recipe == null ? current == null
                    : current != null && current.signature().equals(recipe.signature());
            Gui.drawRect(c.x + 2, y, c.x + c.w - 2, y + CHOICE_ROW - 1, hover ? 0xFF2C2C34 : 0xFF1A1A20);
            if (selected) {
                Gui.drawRect(c.x + 2, y, c.x + 4, y + CHOICE_ROW - 1, ACCENT);
            }
            if (recipe == null) {
                fontRenderer.drawStringWithShadow(I18n.format("better112.tree.raw"), c.x + 28, y + 7, 0xFFBBBBBB);
                continue;
            }
            int x = c.x + 7;
            if (!recipe.catalyst.isEmpty()) {
                drawItem(recipe.catalyst, x, y + 2);
                if (hover && mouseX >= x && mouseX < x + 16) {
                    hoveredIng = Ing.of(recipe.catalyst);
                    hoveredExtra = new ArrayList<String>();
                    hoveredExtra.add(TextFormatting.GRAY + recipe.categoryTitle);
                }
            }
            x += 22;
            int outputsWidth = Math.min(3, recipe.outputs.size()) * 19 + 16;
            int room = (c.x + c.w - 8 - outputsWidth - x) / 19;
            int shown = Math.min(room, recipe.inputs.size());
            if (shown < recipe.inputs.size()) {
                shown = Math.max(0, shown - 1);
            }
            for (int k = 0; k < shown; k++) {
                TreeRecipe.Input input = recipe.inputs.get(k);
                Ing ing = tree.pick(input);
                x = drawChoiceStack(ing, input.count, x, y, mouseX, mouseY, hover,
                        input.options.size() > 1 ? TextFormatting.GRAY + I18n.format("better112.tree.options", input.options.size()) : null);
            }
            if (shown < recipe.inputs.size()) {
                fontRenderer.drawStringWithShadow("+" + (recipe.inputs.size() - shown), x + 2, y + 7, 0xFFAAAAAA);
                x += 19;
            }
            fontRenderer.drawStringWithShadow(">", x + 4, y + 7, 0xFFAAAAAA);
            x += 16;
            for (int k = 0; k < Math.min(3, recipe.outputs.size()); k++) {
                TreeRecipe.Output output = recipe.outputs.get(k);
                x = drawChoiceStack(output.ing, output.count, x, y, mouseX, mouseY, hover, null);
            }
        }
    }

    private int drawChoiceStack(Ing ing, long count, int x, int y, int mouseX, int mouseY, boolean rowHover, String extra) {
        drawIng(ing, x, y + 2);
        if (count != 1 || ing.isFluid()) {
            String text = ing.isFluid() ? compact(count / 1000.0D) : compact(count);
            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0F, 0.0F, 200.0F);
            fontRenderer.drawStringWithShadow(text, x + 17 - fontRenderer.getStringWidth(text), y + 11, 0xFFFFFFFF);
            GlStateManager.popMatrix();
        }
        if (rowHover && mouseX >= x && mouseX < x + 18) {
            hoveredIng = ing;
            hoveredExtra = new ArrayList<String>();
            hoveredExtra.add(TextFormatting.GRAY + amountText(ing, count));
            if (extra != null) {
                hoveredExtra.add(extra);
            }
        }
        return x + 19;
    }

    private void scissor(int x, int y, int w, int h) {
        double scale = mc.displayWidth / (double) width;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * scale), (int) (mc.displayHeight - (y + h) * scale), (int) (w * scale), (int) (h * scale));
    }

    // ------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------

    private RecipeTree.Node nodeAt(int mouseX, int mouseY) {
        if (mouseX <= PANEL + 4) {
            return null;
        }
        float cx = (mouseX - panX) / zoom, cy = (mouseY - panY) / zoom;
        for (RecipeTree.Node node : nodes) {
            if (cx >= node.x && cx < node.x + SLOT && cy >= node.y && cy < node.y + SLOT) {
                return node;
            }
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (chooser != null) {
            Chooser c = chooser;
            if (mouseX < c.x || mouseX >= c.x + c.w || mouseY < c.y || mouseY >= c.y + c.h) {
                chooser = null;
                return;
            }
            int row = (mouseY - c.y - 22) / CHOICE_ROW;
            if (button == 0 && mouseY >= c.y + 22 && row < chooserRows()) {
                int index = c.scroll + row;
                if (index <= c.recipes.size()) {
                    tree.choose(c.node.ing, index == 0 ? null : c.recipes.get(index - 1));
                    chooser = null;
                    refresh();
                }
            }
            return;
        }
        if (tree.root() != null && mouseY >= 6 && mouseY < 18) {
            int minus = PANEL + 10, plus = plusButtonX();
            int step = isShiftKeyDown() ? 16 : isCtrlKeyDown() ? 64 : 1;
            if (mouseX >= minus && mouseX < minus + 12) {
                tree.setAmount(tree.amount() - step);
                refresh();
                return;
            }
            if (mouseX >= plus && mouseX < plus + 12) {
                tree.setAmount(tree.amount() == 1 && step > 1 ? step : tree.amount() + step);
                refresh();
                return;
            }
        }
        if (mouseX <= PANEL + 4) {
            return;
        }
        pressed = true;
        dragMoved = false;
        pressX = mouseX;
        pressY = mouseY;
        pressButton = button;
        pressPanX = panX;
        pressPanY = panY;
        pressedNode = nodeAt(mouseX, mouseY);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        if (!pressed) {
            return;
        }
        if (Math.abs(mouseX - pressX) > 3 || Math.abs(mouseY - pressY) > 3) {
            dragMoved = true;
        }
        if (dragMoved) {
            panX = pressPanX + mouseX - pressX;
            panY = pressPanY + mouseY - pressY;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (!pressed) {
            return;
        }
        pressed = false;
        if (dragMoved || pressedNode == null) {
            return;
        }
        RecipeTree.Node node = pressedNode;
        if (pressButton == 0) {
            if (isShiftKeyDown()) {
                tree.expandAll(node);
                refresh();
            } else {
                openChooser(node);
            }
        } else if (pressButton == 1 && node.recipe != null) {
            tree.choose(node.ing, null);
            refresh();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int direction = Integer.signum(wheel);
        if (chooser != null) {
            chooser.scroll -= direction;
        } else if (mouseX <= PANEL + 4) {
            panelScroll = Math.max(0, panelScroll - direction * ROW * 2);
        } else {
            float next = Math.max(0.25F, Math.min(3.0F, zoom * (direction > 0 ? 1.25F : 0.8F)));
            panX = mouseX - (mouseX - panX) * next / zoom;
            panY = mouseY - (mouseY - panY) * next / zoom;
            zoom = next;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (chooser != null) {
                chooser = null;
            } else {
                mc.displayGuiScreen(parent);
            }
            return;
        }
        if (chooser != null) {
            return;
        }
        int step = isShiftKeyDown() ? 16 : isCtrlKeyDown() ? 64 : 1;
        if (keyCode == Keyboard.KEY_EQUALS || keyCode == Keyboard.KEY_ADD) {
            tree.setAmount(tree.amount() == 1 && step > 1 ? step : tree.amount() + step);
            refresh();
        } else if (keyCode == Keyboard.KEY_MINUS || keyCode == Keyboard.KEY_SUBTRACT) {
            tree.setAmount(tree.amount() - step);
            refresh();
        } else if (keyCode == Keyboard.KEY_TAB) {
            showSteps = !showSteps;
            panelScroll = 0;
        } else if (keyCode == Keyboard.KEY_R && hoveredIng != null) {
            tree.source.showRecipes(hoveredIng);
        } else if (keyCode == Keyboard.KEY_U && hoveredIng != null) {
            tree.source.showUses(hoveredIng);
        } else if (RecipeTreeKeys.KEY_TREE.isActiveAndMatches(keyCode) || mc.gameSettings.keyBindInventory.isActiveAndMatches(keyCode)) {
            mc.displayGuiScreen(parent);
        }
    }

    /** Self-test hooks. */
    public void testChooseRoot() {
        if (rootNode != null) {
            openChooser(rootNode);
        }
    }

    public void testExpandAll() {
        if (rootNode != null) {
            tree.expandAll(rootNode);
            refresh();
        }
    }

    public void testShowSteps(boolean steps) {
        showSteps = steps;
    }
}
