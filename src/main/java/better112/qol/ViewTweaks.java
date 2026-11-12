package better112.qol;

import better112.config.BetterConfig;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;

/**
 * Three small things later versions or popular mods do: F5 can skip the view of your own face, lava is easier
 * to see through with Fire Resistance (as since 1.17), and the game turns its sound down while its window is
 * in the background (as Dynamic FPS does).
 */
public final class ViewTweaks {

    private static int lastView;
    private static float appliedVolume = -1.0F;
    private static int volumeTicks;
    /** Self-test: act as if the window were in the background. */
    public static boolean testBackground;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc.gameSettings.thirdPersonView;
        if (BetterConfig.skipFrontView && view == 2 && lastView == 1 && !Freelook.isActive()) {
            // The perspective key just went from the back view to the front one: carry on to first person.
            mc.gameSettings.thirdPersonView = view = 0;
            mc.entityRenderer.loadEntityShader(mc.getRenderViewEntity());
            mc.renderGlobal.setDisplayListEntitiesDirty();
        }
        lastView = view;
        volume(mc);
        // 1.13's water vision: eyes adjust over half a minute under water, and quickly lose it above.
        if (mc.player != null && mc.player.isInsideOfMaterial(Material.WATER)) {
            waterTicks = Math.min(600, waterTicks + (mc.player.isSpectator() ? 10 : 1));
        } else {
            waterTicks = Math.max(0, waterTicks - 10);
        }
    }

    private static int waterTicks;

    /** How far the eyes have adjusted to water, 0 to 1 (1.13's getWaterVision). */
    static float waterVision() {
        if (waterTicks >= 600) {
            return 1.0F;
        }
        float early = Math.min(1.0F, waterTicks / 100.0F);
        float late = waterTicks < 100 ? 0.0F : Math.min(1.0F, (waterTicks - 100) / 500.0F);
        return early * 0.6F + late * 0.4F;
    }

    public static String testWater() {
        return "under water for " + waterTicks + " ticks, eyes adjusted " + Math.round(waterVision() * 100) + "%";
    }

    public static void testWaterTicks(int ticks) {
        waterTicks = ticks;
    }

    private static void volume(Minecraft mc) {
        boolean background = testBackground || !Display.isActive();
        float master = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
        float wanted = background ? master * Math.max(0, Math.min(100, BetterConfig.backgroundVolume)) / 100.0F : master;
        if (wanted != appliedVolume) {
            if (appliedVolume >= 0.0F || wanted != master) { // nothing to undo at startup
                mc.getSoundHandler().setSoundLevel(SoundCategory.MASTER, wanted);
            }
            appliedVolume = wanted;
        } else if (background && wanted != master && ++volumeTicks % 40 == 0) {
            // Set again now and then: a sound system restart puts the full level back.
            mc.getSoundHandler().setSoundLevel(SoundCategory.MASTER, wanted);
        }
    }

    /**
     * Better Mount HUD: riding no longer hides your food bar (the mount's hearts sit above it), and a horse's jump
     * bar only replaces the experience bar while a jump is charging.
     */
    @SubscribeEvent
    public void onOverlay(net.minecraftforge.client.event.RenderGameOverlayEvent.Pre event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.getType() != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL || !BetterConfig.betterMountHud
                || mc.player == null || !mc.player.isRiding()) {
            return;
        }
        net.minecraftforge.client.GuiIngameForge.renderFood = true;
        if (mc.player.isRidingHorse()) {
            net.minecraftforge.client.GuiIngameForge.renderJumpBar = mc.player.getHorseJumpPower() > 0.0F || mc.gameSettings.keyBindJump.isKeyDown();
        }
    }

    public static String testMountHud() {
        Minecraft mc = Minecraft.getMinecraft();
        return "riding " + (mc.player.getRidingEntity() == null ? "nothing" : mc.player.getRidingEntity().getName()) + ": food bar "
                + net.minecraftforge.client.GuiIngameForge.renderFood + ", mount hearts " + net.minecraftforge.client.GuiIngameForge.renderHealthMount
                + ", jump bar " + net.minecraftforge.client.GuiIngameForge.renderJumpBar;
    }

    public static String testVolume() {
        Minecraft mc = Minecraft.getMinecraft();
        float master = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
        return "master " + master + ", playing at " + appliedVolume + (master > 0.0F ? " (" + Math.round(appliedVolume / master * 100) + "% of it)"
                : " (test instance is muted; in the background it would play at " + BetterConfig.backgroundVolume + "% of the master volume)");
    }

    /** 1.13's underwater view: a gentler fog that thins as the eyes adjust; Respiration and Water Breathing still help. */
    private static void water(EntityViewRenderEvent.FogDensity event) {
        if (!BetterConfig.clearerWater || !(event.getEntity() instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) event.getEntity();
        float vision = waterVision();
        float density = 0.05F - vision * vision * 0.03F;
        density -= net.minecraft.enchantment.EnchantmentHelper.getRespirationModifier(entity) * 0.004F;
        if (entity.isPotionActive(MobEffects.WATER_BREATHING)) {
            density *= 0.5F;
        }
        GlStateManager.setFog(GlStateManager.FogMode.EXP2);
        event.setDensity(Math.max(0.008F, density));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onFogDensity(EntityViewRenderEvent.FogDensity event) {
        if (event.getState().getMaterial() == Material.WATER) {
            water(event);
            return;
        }
        if (!BetterConfig.clearLavaFog || event.getState().getMaterial() != Material.LAVA) {
            return;
        }
        boolean clear = event.getEntity() instanceof EntityPlayer && ((EntityPlayer) event.getEntity()).isSpectator()
                || event.getEntity() instanceof EntityLivingBase && ((EntityLivingBase) event.getEntity()).isPotionActive(MobEffects.FIRE_RESISTANCE);
        if (clear) {
            // 1.17 lets you see about five blocks into lava like this, instead of under one.
            GlStateManager.setFog(GlStateManager.FogMode.EXP);
            event.setDensity(0.35F);
            event.setCanceled(true);
        }
    }
}
