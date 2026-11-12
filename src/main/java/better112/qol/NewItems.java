package better112.qol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiContainerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * New items get a small star, as the Item Highlighter mod gives modern packs: whatever lands in your
 * inventory while no screen is open (picked up, given by a machine or a command) is starred in every
 * inventory screen until the mouse passes over it. Moving things around inside screens marks nothing.
 */
public final class NewItems {

    private static final Map<String, Integer> COUNTS = new HashMap<String, Integer>();
    private static final Set<String> NEW = new HashSet<String>();
    private static EntityPlayerSP countedPlayer;
    private static boolean resync = true;
    /** Set by the self-test, whose blank screen is not a real screen. */
    public static Class<?> testScreen;

    static String key(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return stack.getItem().getRegistryName() + "@" + stack.getMetadata() + "#" + (stack.getTagCompound() == null ? 0 : stack.getTagCompound().hashCode());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (player == null || !BetterConfig.newItemMarks) {
            countedPlayer = null;
            NEW.clear();
            return;
        }
        if (player != countedPlayer) {
            // A new world, a respawn or another dimension: what the player has now is not new.
            countedPlayer = player;
            NEW.clear();
            resync = true;
        }
        boolean screenOpen = mc.currentScreen != null && mc.currentScreen.getClass() != testScreen;
        if (screenOpen) {
            resync = true;
            return;
        }
        Map<String, Integer> now = new HashMap<String, Integer>();
        count(now, player.inventory.mainInventory);
        count(now, player.inventory.offHandInventory);
        if (!resync) {
            for (Map.Entry<String, Integer> entry : now.entrySet()) {
                Integer before = COUNTS.get(entry.getKey());
                if (entry.getValue() > (before == null ? 0 : before)) {
                    NEW.add(entry.getKey());
                }
            }
        }
        NEW.retainAll(now.keySet()); // gone from the inventory: nothing left to mark
        resync = false;
        COUNTS.clear();
        COUNTS.putAll(now);
    }

    private static void count(Map<String, Integer> counts, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            String key = key(stack);
            if (key != null) {
                Integer old = counts.get(key);
                counts.put(key, (old == null ? 0 : old) + stack.getCount());
            }
        }
    }

    @SubscribeEvent
    public void onForeground(GuiContainerEvent.DrawForeground event) {
        if (!BetterConfig.newItemMarks || NEW.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GuiContainer gui = event.getGuiContainer();
        Slot hovered = gui.getSlotUnderMouse();
        if (hovered != null && hovered.inventory == mc.player.inventory && hovered.getHasStack()) {
            NEW.remove(key(hovered.getStack()));
        }
        float pulse = 0.75F + 0.25F * (float) Math.sin(System.currentTimeMillis() / 250.0);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 300.0F); // above the items, which are drawn with depth
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            if (slot.inventory == mc.player.inventory && slot.getHasStack() && NEW.contains(key(slot.getStack()))) {
                star(slot.xPos - 1, slot.yPos - 1, pulse);
            }
        }
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    /** A five-pixel star in the slot's top left corner. */
    private static void star(int x, int y, float pulse) {
        int a = (int) (255 * pulse) << 24;
        int gold = a | 0xFFC233, light = a | 0xFFF6D5;
        Gui.drawRect(x + 2, y, x + 3, y + 5, gold);
        Gui.drawRect(x, y + 2, x + 5, y + 3, gold);
        Gui.drawRect(x + 1, y + 1, x + 4, y + 4, gold);
        Gui.drawRect(x + 2, y + 2, x + 3, y + 3, light);
    }

    /** Self-test: what is marked new now. */
    public static String testState() {
        return NEW.size() + " new: " + NEW;
    }
}
