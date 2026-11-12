package better112.qol;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Equipment comparison, as modern packs get from Equipment Compare: the tooltip of armor, a weapon or a
 * tool is joined by the tooltip of what you wear or hold in its place, so the two can be read side by side.
 */
public final class EquipmentCompare {

    private static boolean drawing;
    /** Self-test: draw this stack's tooltip at this place on the next frame, as if hovered. */
    private static ItemStack testStack = ItemStack.EMPTY;
    private static int testX, testY;
    public static String testResult = "";

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onTooltip(RenderTooltipEvent.PostText event) {
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack hovered = event.getStack();
        GuiScreen screen = mc.currentScreen;
        if (drawing || !BetterConfig.equipmentCompare || hovered.isEmpty() || mc.player == null || screen == null) {
            return;
        }
        ItemStack worn = counterpart(mc.player, hovered);
        if (worn.isEmpty()) {
            return;
        }
        List<String> lines;
        try {
            lines = new ArrayList<>(screen.getItemToolTip(worn));
        } catch (Throwable t) {
            return;
        }
        lines.add(0, TextFormatting.GRAY + I18n.format("better112.compare.equipped"));
        FontRenderer font = event.getFontRenderer();
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.getStringWidth(line));
        }
        // To the left of the hovered item's tooltip if it fits there, else to its right.
        int x = event.getX() - width - 14;
        if (x < 4) {
            x = event.getX() + event.getWidth() + 14;
            if (x + width + 4 > screen.width) {
                return;
            }
        }
        drawing = true;
        try {
            GuiUtils.preItemToolTip(worn);
            GuiUtils.drawHoveringText(worn, lines, x - 12, event.getY() + 12, screen.width, screen.height, -1, font);
            GuiUtils.postItemToolTip();
            testResult = "drawn beside " + hovered.getDisplayName() + ": " + worn.getDisplayName() + " (" + lines.size() + " lines)";
        } finally {
            drawing = false;
        }
    }

    /** What the player wears or holds where the hovered item would go, or EMPTY when there is nothing to compare. */
    static ItemStack counterpart(EntityPlayer player, ItemStack hovered) {
        if (hovered.getItem() instanceof ItemBlock) {
            return ItemStack.EMPTY; // pumpkins and heads go on the head, but are not armor
        }
        EntityEquipmentSlot slot = EntityLiving.getSlotForItemStack(hovered);
        ItemStack worn = player.getItemStackFromSlot(slot);
        if (worn.isEmpty() || worn == hovered || ItemStack.areItemStacksEqual(worn, hovered)) {
            return ItemStack.EMPTY;
        }
        if (slot.getSlotType() == EntityEquipmentSlot.Type.ARMOR) {
            return worn;
        }
        if (slot == EntityEquipmentSlot.OFFHAND) {
            return worn.getItem().isShield(worn, player) ? worn : ItemStack.EMPTY;
        }
        Set<String> a = kinds(hovered), b = kinds(worn);
        a.retainAll(b);
        return a.isEmpty() ? ItemStack.EMPTY : worn;
    }

    /** What sort of gear an item is: its tool classes, sword, bow or hoe, or a weapon when it adds attack damage. */
    private static Set<String> kinds(ItemStack stack) {
        Set<String> kinds = new HashSet<>();
        try {
            kinds.addAll(stack.getItem().getToolClasses(stack));
        } catch (Throwable t) {
            // some tools ask for NBT that is not there yet
        }
        if (stack.getItem() instanceof ItemSword) {
            kinds.add("sword");
            kinds.add("weapon");
        }
        if (stack.getItem() instanceof ItemBow) {
            kinds.add("bow");
        }
        if (stack.getItem() instanceof ItemHoe) {
            kinds.add("hoe");
        }
        if (kinds.isEmpty()) {
            try {
                Collection<AttributeModifier> damage = stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                        .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName());
                for (AttributeModifier modifier : damage) {
                    if (modifier.getAmount() > 1.0) {
                        kinds.add("weapon");
                    }
                }
            } catch (Throwable t) {
                // no attributes: not gear
            }
        }
        return kinds;
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (testStack.isEmpty()) {
            return;
        }
        if (!(event.getGui() instanceof net.minecraft.client.gui.inventory.GuiContainer)) {
            testStack = ItemStack.EMPTY; // the screen it was hovered on has closed
            return;
        }
        ItemStack stack = testStack;
        try {
            Method render = ReflectionHelper.findMethod(GuiScreen.class, "renderToolTip", "func_146285_a", ItemStack.class, int.class, int.class);
            render.invoke(event.getGui(), stack, testX, testY);
        } catch (Throwable t) {
            testResult = "failed: " + t;
        }
    }

    /** Self-test: hover the item in that slot of the player's inventory at the given place. */
    public static String testHover(int slot, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        testStack = mc.player.inventory.getStackInSlot(slot);
        testX = x;
        testY = y;
        testResult = "nothing drawn";
        return testStack.getDisplayName() + " vs " + counterpart(mc.player, testStack).getDisplayName();
    }
}
