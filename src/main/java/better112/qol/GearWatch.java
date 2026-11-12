package better112.qol;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.world.DimensionType;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Two things modern packs add for safety: a warning with a sound when held or worn gear is about to break,
 * and the place of death, on the death screen and in chat, ready to be clicked into a teleport command.
 */
public final class GearWatch {

    // --- Gear about to break ---

    /** Fractions of durability left at which a warning is given: once at a tenth, once more at the very end. */
    private static final double LOW = 0.10, CRITICAL = 0.03;
    private static final Map<EntityEquipmentSlot, String> KEYS = new HashMap<>();
    private static final Map<EntityEquipmentSlot, Integer> LEFT = new HashMap<>();
    public static String lastWarning = "";

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            KEYS.clear();
            LEFT.clear();
            return;
        }
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            watch(mc, slot, mc.player.getItemStackFromSlot(slot));
        }
    }

    private static void watch(Minecraft mc, EntityEquipmentSlot slot, ItemStack stack) {
        if (stack.isEmpty() || !stack.isItemStackDamageable() || stack.getMaxDamage() < 20) {
            KEYS.remove(slot);
            LEFT.remove(slot);
            return;
        }
        int max = stack.getMaxDamage(), left = max - stack.getItemDamage();
        // The same item in the same place: the main hand counts each hotbar slot apart, so switching is not wear.
        String key = Item.getIdFromItem(stack.getItem()) + "@" + (slot == EntityEquipmentSlot.MAINHAND ? mc.player.inventory.currentItem : -1);
        Integer before = key.equals(KEYS.get(slot)) ? LEFT.get(slot) : null;
        KEYS.put(slot, key);
        LEFT.put(slot, left);
        if (before == null || left >= before || !BetterConfig.durabilityWarning) {
            return;
        }
        boolean critical = crossed(before, left, max, CRITICAL);
        if (critical || crossed(before, left, max, LOW)) {
            String text = I18n.format(critical ? "better112.durability.critical" : "better112.durability.low", stack.getDisplayName(), left);
            mc.ingameGUI.setOverlayMessage((critical ? TextFormatting.RED : TextFormatting.GOLD) + text, false);
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.BLOCK_NOTE_PLING, critical ? 0.5F : 0.8F));
            lastWarning = text;
        }
    }

    private static boolean crossed(int before, int left, int max, double fraction) {
        int line = Math.max(1, (int) Math.ceil(max * fraction));
        return before > line && left <= line;
    }

    // --- Where you died ---

    private static String deathText;

    @SubscribeEvent
    public void onOpen(GuiOpenEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(event.getGui() instanceof GuiGameOver) || mc.player == null || !BetterConfig.deathCoords) {
            return;
        }
        EntityPlayer player = mc.player;
        int x = (int) Math.floor(player.posX), y = (int) Math.floor(player.posY), z = (int) Math.floor(player.posZ);
        String place = dimensionName(player.dimension);
        deathText = I18n.format("better112.death.at", x + ", " + y + ", " + z, place);
        ITextComponent where = new TextComponentString("[" + x + ", " + y + ", " + z + "]");
        where.getStyle().setColor(TextFormatting.GREEN)
                .setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @p " + x + " " + y + " " + z))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentTranslation("chat.coordinates.tooltip")));
        mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("better112.death.chat", where, place));
    }

    private static String dimensionName(int id) {
        try {
            String name = DimensionType.getById(id).getName();
            StringBuilder text = new StringBuilder();
            for (String word : name.replace('_', ' ').split(" ")) {
                if (!word.isEmpty() && !word.equals("the")) {
                    text.append(text.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
            }
            return text.length() == 0 ? name : text.toString();
        } catch (Throwable t) {
            return String.format(Locale.ROOT, "DIM%d", id);
        }
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (event.getGui() instanceof GuiGameOver && deathText != null && BetterConfig.deathCoords) {
            event.getGui().drawCenteredString(Minecraft.getMinecraft().fontRenderer, deathText, event.getGui().width / 2, 112, 0xFFAAAAAA);
        }
    }
}
