package better112;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import better112.config.BetterConfig;
import better112.gui.SodiumScreen;
import better112.perf.Occlusion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

/** Everything that needs no bytecode patch: Forge events, key binds and the HUD overlay. */
public final class ClientTweaks {

    public static final KeyBinding KEY_ZOOM = new KeyBinding("better112.key.zoom", Keyboard.KEY_C, "better112.key.category");
    public static final KeyBinding KEY_MENU = new KeyBinding("better112.key.menu", Keyboard.KEY_NONE, "better112.key.category");

    public static final boolean QUESTS_LOADED = net.minecraftforge.fml.common.Loader.isModLoaded("betterquesting");

    private static boolean bypassVideoScreen;
    private static boolean zooming;
    private static boolean smoothCameraBeforeZoom;
    private static float zoomTarget = 4.0F, zoomNow = 1.0F;
    private static long zoomFrameAt;
    /** Self-test: hold the zoom key without a keyboard. */
    public static boolean testZoomHeld;

    private static long lastFrameNanos;
    private static final long[] FRAME_TIMES = new long[240];
    private static int frameTimeIndex;
    private static int frameTimeCount;
    private static String overlayStats = "";
    private static long overlayStatsAt;

    private ClientTweaks() {}

    static void init() {
        ClientRegistry.registerKeyBinding(KEY_ZOOM);
        ClientRegistry.registerKeyBinding(KEY_MENU);
        MinecraftForge.EVENT_BUS.register(new ClientTweaks());
        better112.hud.Hud.init();
        Minecraft.getMinecraft().getTextureManager().loadTexture(Hooks.EMPTY_TEXTURE,
                new DynamicTexture(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)));
        applyConfig();
    }

    /** Pushes settings that live in Minecraft's own static state. */
    public static void applyConfig() {
        Entity.setRenderDistanceWeight(BetterConfig.entityDistance / 100.0D);
    }

    /** Throws away the chunk builder threads so the next renderer reload starts new ones. */
    public static void resetChunkDispatcher() {
        try {
            RenderGlobal renderGlobal = Minecraft.getMinecraft().renderGlobal;
            Field field = ReflectionHelper.findField(RenderGlobal.class, "renderDispatcher", "field_174995_M");
            ChunkRenderDispatcher dispatcher = (ChunkRenderDispatcher) field.get(renderGlobal);
            if (dispatcher != null && Minecraft.getMinecraft().world != null) {
                dispatcher.stopWorkerThreads();
                field.set(renderGlobal, null);
            }
        } catch (Throwable t) {
            Better112.LOG.warn("Could not restart the chunk builder threads", t);
        }
    }

    public static void openVanillaVideoSettings(GuiScreen screen) {
        bypassVideoScreen = true;
        Minecraft.getMinecraft().displayGuiScreen(screen);
    }

    public static boolean isZooming() {
        return zooming;
    }

    // ------------------------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (QUESTS_LOADED) {
            try {
                GuiScreen replacement = better112.quests.QuestsBridge.replace(event.getGui(), Minecraft.getMinecraft().currentScreen);
                if (replacement != null) {
                    event.setGui(replacement);
                    return;
                }
            } catch (Throwable t) {
                Better112.LOG.warn("The quest book replacement failed; using Better Questing's own screen", t);
            }
        }
        if (!(event.getGui() instanceof GuiVideoSettings)) {
            return;
        }
        if (bypassVideoScreen) {
            bypassVideoScreen = false;
            return;
        }
        if (BetterConfig.replaceVideoSettings && !FMLClientHandler.instance().hasOptifine()) {
            event.setGui(new SodiumScreen(Minecraft.getMinecraft().currentScreen));
        }
    }

    // ------------------------------------------------------------------------------------
    // Ticks
    // ------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Hooks.frame++;
        long now = System.nanoTime();
        int cap = !Display.isActive() ? BetterConfig.backgroundFps : 0;
        if (better112.qol.Idle.isIdle()) {
            // Nobody at the keyboard for a while (Dynamic FPS): draw slowly; the game still ticks at full speed.
            cap = cap > 0 ? Math.min(cap, BetterConfig.idleFps) : BetterConfig.idleFps;
        }
        if (cap > 0 && lastFrameNanos != 0L) {
            long wait = lastFrameNanos + 1000000000L / cap - now;
            if (wait > 0L) {
                try {
                    Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                now = System.nanoTime();
            }
        }
        if (lastFrameNanos != 0L) {
            FRAME_TIMES[frameTimeIndex] = now - lastFrameNanos;
            frameTimeIndex = (frameTimeIndex + 1) % FRAME_TIMES.length;
            frameTimeCount = Math.min(FRAME_TIMES.length, frameTimeCount + 1);
        }
        lastFrameNanos = now;
    }

    private static final boolean[] TOGGLED = new boolean[2];
    private static final boolean[] WAS_DOWN = new boolean[2];

    /** Hold or toggle, as vanilla has offered for sneak and sprint since 1.15. */
    private static void toggleKey(Minecraft mc, int index, KeyBinding key, boolean toggleMode) {
        int code = key.getKeyCode();
        if (code == 0) {
            return;
        }
        boolean down = code > 0 ? Keyboard.isKeyDown(code) : org.lwjgl.input.Mouse.isButtonDown(code + 100);
        if (!toggleMode) {
            if (TOGGLED[index]) {
                TOGGLED[index] = false;
                KeyBinding.setKeyBindState(code, down);
            }
        } else if (mc.currentScreen == null) {
            if (down && !WAS_DOWN[index]) {
                TOGGLED[index] = !TOGGLED[index];
            }
            KeyBinding.setKeyBindState(code, TOGGLED[index]);
        }
        WAS_DOWN[index] = down;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Minecraft start = Minecraft.getMinecraft();
            if (start.player != null) {
                toggleKey(start, 0, start.gameSettings.keyBindSprint, BetterConfig.toggleSprint);
                toggleKey(start, 1, start.gameSettings.keyBindSneak, BetterConfig.toggleSneak);
            }
            return;
        }
        Occlusion.tick++;
        better112.perf.DynamicLights.tick();
        Minecraft mc = Minecraft.getMinecraft();
        if (!BetterConfig.toasts) {
            mc.getToastGui().clear();
        }
        boolean wantZoom = mc.world != null && (testZoomHeld || mc.currentScreen == null && KEY_ZOOM.isKeyDown());
        if (wantZoom != zooming) {
            if (wantZoom) {
                smoothCameraBeforeZoom = mc.gameSettings.smoothCamera;
                mc.gameSettings.smoothCamera = true;
                zoomTarget = 4.0F; // each press starts from the usual zoom, as Ok Zoomer does
            } else {
                mc.gameSettings.smoothCamera = smoothCameraBeforeZoom;
            }
            zooming = wantZoom;
        }
        if (KEY_MENU.isPressed() && mc.currentScreen == null) {
            mc.displayGuiScreen(new SodiumScreen(null));
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld().isRemote) {
            Hooks.refresh();
            Occlusion.clear();
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            Occlusion.forget();
        }
    }

    @SubscribeEvent
    public void onTextureStitch(TextureStitchEvent.Post event) {
        Hooks.clearSpriteCache();
        better112.cosmetic.BetterGrass.clear();
        better112.shader.Shaders.atlasStitched(event.getMap());
    }

    // ------------------------------------------------------------------------------------
    // Camera and fog
    // ------------------------------------------------------------------------------------

    /** Modern "FOV Effects": how much sprinting, speed effects and drawing a bow change the view. */
    @SubscribeEvent
    public void onFovUpdate(net.minecraftforge.client.event.FOVUpdateEvent event) {
        int percent = BetterConfig.fovEffects;
        if (percent != 100) {
            event.setNewfov(1.0F + (event.getNewfov() - 1.0F) * percent / 100.0F);
        }
    }

    @SubscribeEvent
    public void onFov(EntityViewRenderEvent.FOVModifier event) {
        if (!BetterConfig.zoomScroll) {
            zoomNow = 1.0F;
            if (zooming) {
                event.setFOV(event.getFOV() / 4.0F);
            }
            return;
        }
        // Ease towards the wanted zoom over about a tenth of a second, in and out (as Zoomify does).
        long now = System.nanoTime();
        float seconds = zoomFrameAt == 0L ? 0.0F : Math.min(0.25F, (now - zoomFrameAt) / 1.0E9F);
        zoomFrameAt = now;
        float goal = zooming ? zoomTarget : 1.0F;
        zoomNow += (goal - zoomNow) * (1.0F - (float) Math.exp(-seconds * 18.0F));
        if (Math.abs(goal - zoomNow) < 0.01F) {
            zoomNow = goal;
        }
        if (zoomNow != 1.0F) {
            event.setFOV(event.getFOV() / zoomNow);
        }
    }

    /** While zooming, the mouse wheel zooms further in or out instead of changing the held item. */
    @SubscribeEvent
    public void onMouse(net.minecraftforge.client.event.MouseEvent event) {
        if (zooming && BetterConfig.zoomScroll && event.getDwheel() != 0) {
            zoomBy(event.getDwheel() > 0 ? 1 : -1);
            event.setCanceled(true);
        }
    }

    /** One step of the wheel: up zooms in by a quarter, down out, between 1.5 and 50 times. */
    public static float zoomBy(int steps) {
        zoomTarget = Math.max(1.5F, Math.min(50.0F, zoomTarget * (float) Math.pow(1.25, steps)));
        return zoomTarget;
    }

    public static String testZoom() {
        return "zooming " + zooming + ", wanted " + zoomTarget + "x, now " + Math.round(zoomNow * 100) / 100.0F + "x";
    }

    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.RenderFogEvent event) {
        int mode = BetterConfig.fog;
        if (mode == 0 || event.getFogMode() == -1) {
            return; // the sky keeps its fog so the horizon still blends
        }
        float far = event.getFarPlaneDistance();
        if (mode == 1) {
            GlStateManager.setFogStart(far * 0.9F);
            GlStateManager.setFogEnd(far * 1.05F);
        } else {
            GlStateManager.setFogStart(far * 4.0F);
            GlStateManager.setFogEnd(far * 8.0F);
        }
    }

    // ------------------------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.VIGNETTE && !BetterConfig.vignette) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.TEXT) {
            return;
        }
        if (!BetterConfig.showFps && !BetterConfig.showCoords) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo || mc.gameSettings.hideGUI || mc.player == null) {
            return;
        }
        List<String> lines = new ArrayList<String>(2);
        if (BetterConfig.showFps) {
            String line = Minecraft.getDebugFPS() + " fps";
            if (BetterConfig.showFpsExtended) {
                line += frameStats();
            }
            lines.add(line);
        }
        if (BetterConfig.showCoords) {
            lines.add("X: " + (int) Math.floor(mc.player.posX) + " Y: " + (int) Math.floor(mc.player.getEntityBoundingBox().minY)
                    + " Z: " + (int) Math.floor(mc.player.posZ));
        }
        ScaledResolution resolution = event.getResolution();
        FontRenderer font = mc.fontRenderer;
        int corner = BetterConfig.overlayCorner;
        boolean right = (corner & 1) != 0, bottom = (corner & 2) != 0;
        int y = bottom ? resolution.getScaledHeight() - 2 - lines.size() * 10 : 2;
        for (String line : lines) {
            int x = right ? resolution.getScaledWidth() - 2 - font.getStringWidth(line) : 2;
            font.drawStringWithShadow(line, x, y, 0xFFFFFFFF);
            y += 10;
        }
    }

    /** Average and 1%-low over the last few seconds, refreshed twice a second. */
    private static String frameStats() {
        long now = System.nanoTime();
        if (now - overlayStatsAt > 500000000L && frameTimeCount > 10) {
            overlayStatsAt = now;
            long[] sorted = Arrays.copyOf(FRAME_TIMES, frameTimeCount);
            Arrays.sort(sorted);
            long total = 0L;
            for (long t : sorted) {
                total += t;
            }
            long worst = sorted[Math.max(0, (int) (sorted.length * 0.99D) - 1)];
            overlayStats = " (avg " + Math.round(1.0E9D * sorted.length / total) + ", low " + Math.round(1.0E9D / worst) + ")";
        }
        return overlayStats;
    }

    @SubscribeEvent
    public void onNameTag(RenderLivingEvent.Specials.Pre<?> event) {
        if (!BetterConfig.playerNameTags && event.getEntity() instanceof EntityPlayer) {
            event.setCanceled(true);
        }
    }
}
