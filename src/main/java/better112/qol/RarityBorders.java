package better112.qol;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.inventory.Slot;
import net.minecraft.item.EnumRarity;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiContainerEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Item rarity at a glance, as Item Borders and Legendary Tooltips give modern packs: uncommon, rare and epic
 * items (and items a mod names in a colour) get a thin frame of that colour in inventory slots and on the
 * hotbar, and their tooltip's border takes the same colour.
 */
public final class RarityBorders {

    /** Colours already worked out in this game tick, by stack: slots are drawn every frame, names change in ticks. */
    private static final java.util.IdentityHashMap<ItemStack, Integer> KEPT = new java.util.IdentityHashMap<ItemStack, Integer>();
    private static long keptAt = Long.MIN_VALUE;

    /** {@link #colorOf}, worked out once per stack and game tick (it reads the item's name, a translation). */
    static int keptColorOf(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || stack.isEmpty()) {
            return colorOf(stack);
        }
        long now = mc.world.getTotalWorldTime();
        if (now != keptAt) {
            KEPT.clear();
            keptAt = now;
        }
        Integer kept = KEPT.get(stack);
        if (kept == null) {
            kept = colorOf(stack);
            KEPT.put(stack, kept);
        }
        return kept;
    }

    /** The colour an item is marked with, or 0 for plain items. */
    static int colorOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        char code = 0;
        try {
            // Forge's rarity, which mods can give colours of their own.
            net.minecraftforge.common.IRarity rarity = stack.getItem().getForgeRarity(stack);
            if (rarity != null && rarity != EnumRarity.COMMON && rarity.getColor() != null) {
                code = rarity.getColor().toString().charAt(1);
            } else {
                // A mod that colours the name itself (§b...) without setting a rarity.
                String name = stack.getDisplayName();
                if (name.length() > 1 && name.charAt(0) == '§') {
                    code = Character.toLowerCase(name.charAt(1));
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        if (code == 0 || "0123456789abcdef".indexOf(code) < 0 || code == 'f' || code == '7' || code == '8' || code == '0') {
            return 0; // white and greys are the plain name colours
        }
        return Minecraft.getMinecraft().fontRenderer.getColorCode(code) & 0xFFFFFF;
    }

    @SubscribeEvent
    public void onForeground(GuiContainerEvent.DrawForeground event) {
        if (!BetterConfig.rarityBorders) {
            return;
        }
        GuiContainer gui = event.getGuiContainer();
        begin();
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            if (slot.isEnabled() && slot.getHasStack()) {
                frame(slot.xPos, slot.yPos, keptColorOf(slot.getStack()));
            }
        }
        end();
    }

    @SubscribeEvent
    public void onHotbar(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR || !BetterConfig.rarityBorders || mc.player == null) {
            return;
        }
        ScaledResolution res = event.getResolution();
        int left = res.getScaledWidth() / 2 - 90, top = res.getScaledHeight() - 19;
        begin();
        for (int i = 0; i < 9; i++) {
            frame(left + i * 20 + 2, top, keptColorOf(mc.player.inventory.mainInventory.get(i)));
        }
        end();
    }

    @SubscribeEvent
    public void onTooltipColor(RenderTooltipEvent.Color event) {
        if (!BetterConfig.rarityBorders) {
            return;
        }
        int color = keptColorOf(event.getStack());
        if (color != 0) {
            event.setBorderStart(0xF0000000 | color);
            event.setBorderEnd(0xF0000000 | darker(color));
        }
    }

    private static int darker(int color) {
        return (color >> 16 & 255) / 2 << 16 | (color >> 8 & 255) / 2 << 8 | (color & 255) / 2;
    }

    private static void begin() {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 290.0F); // over the items, under the new-item sparkle
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
    }

    private static void end() {
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    /** A one-pixel frame just inside the slot, brighter at the top, fading at the bottom. */
    private static void frame(int x, int y, int color) {
        if (color == 0) {
            return;
        }
        int top = 0xE0000000 | color, bottom = 0x70000000 | color;
        Gui.drawRect(x, y, x + 16, y + 1, top);
        Gui.drawRect(x, y + 15, x + 16, y + 16, bottom);
        drawGradient(x, y + 1, x + 1, y + 15, top, bottom);
        drawGradient(x + 15, y + 1, x + 16, y + 15, top, bottom);
    }

    private static void drawGradient(int left, int top, int right, int bottom, int from, int to) {
        // Gui.drawGradientRect is protected; this is the same thing for a one-pixel column.
        float steps = bottom - top;
        for (int y = top; y < bottom; y++) {
            float t = (y - top) / steps;
            int a = (int) ((from >>> 24) + ((to >>> 24) - (from >>> 24)) * t);
            Gui.drawRect(left, y, right, y + 1, a << 24 | from & 0xFFFFFF);
        }
    }

    /** Self-test: the colour an item would be framed in. */
    public static String testColor(ItemStack stack) {
        return stack.getDisplayName() + " -> " + String.format("%06X", colorOf(stack));
    }
}
