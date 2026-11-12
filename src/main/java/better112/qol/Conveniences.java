package better112.qol;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/** Two small things from later versions: the "Saving world" note and the off-hand key in inventories. */
public final class Conveniences {

    // --- "Saving world", 1.18 ---

    private static final long SHOWN_MS = 2000L;
    /** Set from the server thread of a single-player world each time it is written to disk. */
    private static volatile long savedAt;

    @SubscribeEvent
    public void onSave(WorldEvent.Save event) {
        if (!event.getWorld().isRemote) {
            savedAt = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        long age = System.currentTimeMillis() - savedAt;
        if (!BetterConfig.saveIndicator || event.getType() != RenderGameOverlayEvent.ElementType.TEXT || age < 0 || age >= SHOWN_MS) {
            return;
        }
        // In over a quarter of a second, out over half a second.
        int alpha = (int) (255 * Math.min(1.0, Math.min(age / 250.0, (SHOWN_MS - age) / 500.0)));
        if (alpha < 10) {
            return;
        }
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String text = I18n.format("better112.saving");
        GlStateManager.enableBlend();
        font.drawStringWithShadow(text, event.getResolution().getScaledWidth() - font.getStringWidth(text) - 4,
                event.getResolution().getScaledHeight() - 14, (alpha << 24) | 0xFFFFFF);
    }

    /** Self-test: show the note as if the world had just been saved. */
    public static void testSaved() {
        savedAt = System.currentTimeMillis() - 400L;
    }

    // --- Off-hand key over a slot, 1.16 ---

    private static final boolean JEI = Loader.isModLoaded("jei");
    private static final Map<Class<?>, Field[]> TEXT_FIELDS = new HashMap<>();

    @SubscribeEvent
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!BetterConfig.offhandSwapInGui || !Keyboard.getEventKeyState() || mc.player == null
                || !(event.getGui() instanceof GuiContainer) || event.getGui() instanceof GuiContainerCreative) {
            return;
        }
        int key = mc.gameSettings.keyBindSwapHands.getKeyCode();
        if (key <= 0 || Keyboard.getEventKey() != key || GuiScreen.isCtrlKeyDown() || GuiScreen.isAltKeyDown() || typing(event.getGui())) {
            return;
        }
        swap(mc, (GuiContainer) event.getGui(), ((GuiContainer) event.getGui()).getSlotUnderMouse());
    }

    /** True while a text box of the screen, or JEI's search box, takes the keys. */
    private static boolean typing(GuiScreen gui) {
        if (JEI && better112.jei.JeiBridge.searching()) {
            return true;
        }
        Field[] fields = TEXT_FIELDS.get(gui.getClass());
        if (fields == null) {
            List<Field> found = new ArrayList<>();
            for (Class<?> type = gui.getClass(); type != null && type != GuiScreen.class; type = type.getSuperclass()) {
                try {
                    for (Field field : type.getDeclaredFields()) {
                        if (GuiTextField.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            found.add(field);
                        }
                    }
                } catch (Throwable t) {
                    // a class that cannot be looked into has no text boxes we can ask
                }
            }
            fields = found.toArray(new Field[0]);
            TEXT_FIELDS.put(gui.getClass(), fields);
        }
        for (Field field : fields) {
            try {
                GuiTextField box = (GuiTextField) field.get(gui);
                if (box != null && box.isFocused() && box.getVisible()) {
                    return true;
                }
            } catch (Throwable t) {
                // not readable: treat as not typing
            }
        }
        return false;
    }

    /**
     * Swaps a slot of the player's own inventory with the off hand. 1.12 servers know no such click, so it
     * goes by way of the held hotbar slot: slot to hotbar, hands swapped, hotbar back to slot.
     */
    public static boolean swap(Minecraft mc, GuiContainer gui, Slot slot) {
        EntityPlayer player = mc.player;
        if (slot == null || slot.inventory != player.inventory || slot.getSlotIndex() >= 36 || player.isSpectator()
                || !player.inventory.getItemStack().isEmpty()) {
            return false;
        }
        int held = player.inventory.currentItem;
        ItemStack inHand = player.inventory.getStackInSlot(held);
        ItemStack offhand = player.getHeldItemOffhand();
        if (slot.getStack().isEmpty() && offhand.isEmpty()) {
            return false;
        }
        boolean viaHotbar = slot.getSlotIndex() != held;
        if (viaHotbar && (!slot.canTakeStack(player) || (!inHand.isEmpty() && !slot.isItemValid(inHand))
                || (!offhand.isEmpty() && !slot.isItemValid(offhand)))) {
            return false;
        }
        int window = gui.inventorySlots.windowId;
        if (viaHotbar) {
            mc.playerController.windowClick(window, slot.slotNumber, held, ClickType.SWAP, player);
        }
        mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.SWAP_HELD_ITEMS, BlockPos.ORIGIN, EnumFacing.DOWN));
        // The server tells a client nothing about its own off hand while a container is open, so do the same here.
        ItemStack main = player.getHeldItem(EnumHand.MAIN_HAND);
        player.setHeldItem(EnumHand.MAIN_HAND, player.getHeldItem(EnumHand.OFF_HAND));
        player.setHeldItem(EnumHand.OFF_HAND, main);
        if (viaHotbar) {
            mc.playerController.windowClick(window, slot.slotNumber, held, ClickType.SWAP, player);
        }
        return true;
    }
}
