package better112.hud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import better112.config.BetterConfig;
import better112.tree.Ing;

import com.google.common.collect.Ordering;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Small heads-up helpers that modern packs ship as a handful of separate mods: a list of
 * what you just picked up, the time left on each status effect, how worn your gear is, and
 * durability numbers in tooltips. Each has its own switch under Extras.
 */
public final class Hud {

    private Hud() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.register(new Hud());
    }

    // ------------------------------------------------------------------------------------
    // Pick-up notifier
    // ------------------------------------------------------------------------------------

    private static final class Pickup {
        String key;
        ItemStack stack;
        int count;
        long shownAt;
    }

    private static final long PICKUP_VISIBLE_MS = 4000L;
    private static final int PICKUP_LINES = 6;
    private static final List<Pickup> PICKUPS = new ArrayList<Pickup>();
    private static final Map<String, Integer> COUNTS = new HashMap<String, Integer>();
    private static final Map<String, ItemStack> SAMPLES = new HashMap<String, ItemStack>();
    /** Set by the self-test, which keeps a blank screen open so the game never grabs the mouse. */
    public static Class<?> testScreen;
    private static EntityPlayerSP countedPlayer;
    private static boolean resync = true;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (player == null || !BetterConfig.pickupNotifier) {
            countedPlayer = null;
            PICKUPS.clear();
            return;
        }
        // Moving things around in a container is not picking them up: skip while a screen is
        // open and start counting afresh once it closes.
        boolean screenOpen = mc.currentScreen != null && mc.currentScreen.getClass() != testScreen;
        if (screenOpen || player != countedPlayer) {
            countedPlayer = player;
            resync = true;
            return;
        }
        Map<String, Integer> now = new HashMap<String, Integer>();
        count(now, player.inventory.mainInventory);
        count(now, player.inventory.offHandInventory);
        if (!resync) {
            long time = System.currentTimeMillis();
            for (Map.Entry<String, Integer> entry : now.entrySet()) {
                Integer before = COUNTS.get(entry.getKey());
                int gained = entry.getValue() - (before == null ? 0 : before);
                if (gained > 0) {
                    add(entry.getKey(), SAMPLES.get(entry.getKey()), gained, time);
                }
            }
        }
        resync = false;
        COUNTS.clear();
        COUNTS.putAll(now);
    }

    private static void count(Map<String, Integer> counts, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            Ing ing = Ing.of(stack);
            if (ing == null) {
                continue;
            }
            Integer old = counts.get(ing.key);
            counts.put(ing.key, (old == null ? 0 : old) + stack.getCount());
            if (!SAMPLES.containsKey(ing.key)) {
                SAMPLES.put(ing.key, ing.item);
            }
        }
    }

    private static void add(String key, ItemStack stack, int gained, long time) {
        if (stack == null) {
            return;
        }
        for (Pickup pickup : PICKUPS) {
            if (pickup.key.equals(key)) {
                pickup.count += gained;
                pickup.shownAt = time;
                return;
            }
        }
        Pickup pickup = new Pickup();
        pickup.key = key;
        pickup.stack = stack;
        pickup.count = gained;
        pickup.shownAt = time;
        PICKUPS.add(pickup);
        while (PICKUPS.size() > PICKUP_LINES) {
            PICKUPS.remove(0);
        }
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.gameSettings.hideGUI) {
            return;
        }
        if (event.getType() == RenderGameOverlayEvent.ElementType.POTION_ICONS) {
            if (BetterConfig.effectTimers) {
                drawEffectTimers(mc, event.getResolution());
            }
        } else if (event.getType() == RenderGameOverlayEvent.ElementType.TEXT) {
            if (BetterConfig.pickupNotifier && !mc.gameSettings.showDebugInfo) {
                drawPickups(mc, event.getResolution());
            }
            if (BetterConfig.equipmentHud && !mc.gameSettings.showDebugInfo) {
                drawEquipment(mc, event.getResolution());
            }
        }
    }

    private static void drawPickups(Minecraft mc, ScaledResolution resolution) {
        if (PICKUPS.isEmpty()) {
            return;
        }
        long time = System.currentTimeMillis();
        for (Iterator<Pickup> it = PICKUPS.iterator(); it.hasNext();) {
            if (time - it.next().shownAt > PICKUP_VISIBLE_MS) {
                it.remove();
            }
        }
        FontRenderer font = mc.fontRenderer;
        int right = resolution.getScaledWidth() - 4;
        int y = resolution.getScaledHeight() - 24 - PICKUPS.size() * 18;
        for (Pickup pickup : PICKUPS) {
            long age = time - pickup.shownAt;
            int alpha = age > PICKUP_VISIBLE_MS - 500L ? (int) (255 * (PICKUP_VISIBLE_MS - age) / 500L) : 255;
            if (alpha > 8) {
                String name;
                try {
                    name = pickup.stack.getDisplayName();
                } catch (Throwable t) {
                    name = "?";
                }
                String text = "+" + pickup.count + " " + name;
                int width = font.getStringWidth(text);
                GlStateManager.enableBlend();
                font.drawStringWithShadow(text, right - 18 - width, y + 5, (alpha << 24) | 0xFFFFFF);
                drawItem(mc, pickup.stack, right - 16, y);
            }
            y += 18;
        }
    }

    private static void drawEffectTimers(Minecraft mc, ScaledResolution resolution) {
        java.util.Collection<PotionEffect> effects = mc.player.getActivePotionEffects();
        if (effects.isEmpty()) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        int good = 0, bad = 0;
        // Same order and layout the game uses for the icons themselves.
        for (PotionEffect effect : Ordering.natural().reverse().sortedCopy(effects)) {
            Potion potion = effect.getPotion();
            if (!potion.shouldRenderHUD(effect) || !effect.doesShowParticles()) {
                continue;
            }
            int x = resolution.getScaledWidth();
            int y = mc.isDemo() ? 16 : 1;
            if (potion.isBeneficial()) {
                x -= 25 * ++good;
            } else {
                x -= 25 * ++bad;
                y += 26;
            }
            String text = duration(effect);
            GlStateManager.pushMatrix();
            GlStateManager.translate(x + 12.0F, y + 16.0F, 0.0F);
            GlStateManager.scale(0.75F, 0.75F, 1.0F);
            font.drawStringWithShadow(text, -font.getStringWidth(text) / 2.0F, 0.0F, 0xFFFFFFFF);
            GlStateManager.popMatrix();
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static String duration(PotionEffect effect) {
        int ticks = effect.getDuration();
        if (effect.getIsPotionDurationMax() || ticks > 20 * 60 * 60 * 10) {
            return "**";
        }
        int seconds = ticks / 20;
        if (seconds >= 3600) {
            return seconds / 3600 + "h";
        }
        if (seconds >= 60) {
            return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
        }
        return seconds + "s";
    }

    private static final EntityEquipmentSlot[] SLOTS = {
        EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET,
        EntityEquipmentSlot.MAINHAND, EntityEquipmentSlot.OFFHAND,
    };

    private static void drawEquipment(Minecraft mc, ScaledResolution resolution) {
        List<ItemStack> worn = new ArrayList<ItemStack>(6);
        for (EntityEquipmentSlot slot : SLOTS) {
            ItemStack stack = mc.player.getItemStackFromSlot(slot);
            if (!stack.isEmpty() && stack.isItemStackDamageable()) {
                worn.add(stack);
            }
        }
        if (worn.isEmpty()) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        int y = resolution.getScaledHeight() / 2 - worn.size() * 9;
        for (ItemStack stack : worn) {
            int max = stack.getMaxDamage();
            int left = max - stack.getItemDamage();
            float share = max <= 0 ? 1.0F : left / (float) max;
            int color = share > 0.5F ? 0xFF55FF55 : share > 0.2F ? 0xFFFFDD55 : 0xFFFF5555;
            drawItem(mc, stack, 2, y);
            font.drawStringWithShadow(String.valueOf(left), 21, y + 5, color);
            y += 18;
        }
    }

    private static void drawItem(Minecraft mc, ItemStack stack, int x, int y) {
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        try {
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        } catch (Throwable ignored) {
            // an item that cannot render should not break the HUD
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------------------------
    // Tooltips
    // ------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        if (BetterConfig.cellContents && stack.hasTagCompound()) {
            CellContents.append(stack, event.getToolTip(), net.minecraft.client.gui.GuiScreen.isShiftKeyDown());
        }
        // The advanced (F3+H) tooltip already has the durability line.
        if (BetterConfig.durabilityTooltip && stack.isItemStackDamageable() && !event.getFlags().isAdvanced()) {
            int max = stack.getMaxDamage();
            if (max > 0) {
                event.getToolTip().add(TextFormatting.GRAY + I18n.format("better112.tooltip.durability", max - stack.getItemDamage(), max));
            }
        }
    }
}
