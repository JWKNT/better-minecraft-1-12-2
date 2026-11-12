package better112.qol;

import better112.config.BetterConfig;
import better112.tree.Ing;
import better112.tree.RecipeTreeKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Item Zoom, as modern packs have it: in an inventory, Z switches on a large picture of the item under the
 * mouse beside the screen, to look at its texture properly; Z again switches it off.
 */
public final class ItemZoom {

    public static final KeyBinding KEY = new KeyBinding("better112.key.item_zoom", KeyConflictContext.GUI, Keyboard.KEY_Z, "better112.key.category");

    private static boolean on;
    /** Self-test: show this stack as if it were hovered. */
    public static ItemStack testStack = ItemStack.EMPTY;

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    @SubscribeEvent
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (!BetterConfig.itemZoom || !Keyboard.getEventKeyState() || event.getGui() == null) {
            return;
        }
        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_NONE || !KEY.isActiveAndMatches(key) || RecipeTreeKeys.source().hasTextFocus()
                || RecipeTreeKeys.isTyping(event.getGui())) {
            return;
        }
        on = !on;
    }

    static ItemStack hovered(GuiScreen gui) {
        if (!testStack.isEmpty()) {
            return testStack;
        }
        Ing ing = RecipeTreeKeys.source().hovered(gui);
        if (ing != null && ing.item != null && !ing.item.isEmpty()) {
            return ing.item;
        }
        if (gui instanceof GuiContainer) {
            Slot slot = ((GuiContainer) gui).getSlotUnderMouse();
            if (slot != null && slot.getHasStack()) {
                return slot.getStack();
            }
        }
        return ItemStack.EMPTY;
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!on || !BetterConfig.itemZoom) {
            return;
        }
        GuiScreen gui = event.getGui();
        ItemStack stack = hovered(gui);
        if (stack.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // Left of the inventory, as large as the space there allows.
        int room = gui instanceof GuiContainer ? ((GuiContainer) gui).getGuiLeft() : gui.width / 3;
        int size = Math.max(32, Math.min(Math.min(room - 16, gui.height - 60), 160)) / 16 * 16;
        int x = Math.max(4, (room - size) / 2), y = (gui.height - size) / 2 - 8;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 400.0F);
        Gui.drawRect(x - 6, y - 6, x + size + 6, y + size + 18, 0xC0101018);
        String name = stack.getDisplayName();
        int nameWidth = mc.fontRenderer.getStringWidth(name);
        if (nameWidth > size + 8) {
            name = mc.fontRenderer.trimStringToWidth(name, size + 2) + "...";
            nameWidth = mc.fontRenderer.getStringWidth(name);
        }
        mc.fontRenderer.drawStringWithShadow(name, x + (size - nameWidth) / 2.0F, y + size + 5, 0xFFFFFF);
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(size / 16.0F, size / 16.0F, 1.0F);
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.popMatrix();
    }

    /** Self-test: switch the zoom on or off. */
    public static void testSet(boolean value) {
        on = value;
    }
}
