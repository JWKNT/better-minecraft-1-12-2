package better112.qol;

import java.lang.reflect.Field;
import java.util.List;

import better112.Better112;
import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSleepMP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * The menu background blur of 1.21: while a screen is open over the world, the world behind it is blurred.
 * It is the game's own blur shader, run twice in each direction so it comes out soft rather than boxy.
 * It never replaces a shader another mod or the spectator view has put up.
 */
public final class MenuBlur {

    private static final ResourceLocation SHADER = new ResourceLocation("better112", "shaders/post/menu_blur.json");
    private static final long FADE_MS = 150L;

    private static ShaderGroup ours;
    private static GuiScreen failedFor;
    private static long openedAt;
    private static Field shaders;

    private static boolean wanted(Minecraft mc) {
        GuiScreen screen = mc.currentScreen;
        return BetterConfig.menuBlur > 0 && mc.world != null && screen != null && OpenGlHelper.shadersSupported
                && !(screen instanceof GuiChat) && !(screen instanceof GuiGameOver) && !(screen instanceof GuiDownloadTerrain)
                && !(screen instanceof GameModeSwitcher) && !screen.getClass().getName().startsWith("better112.test.");
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ShaderGroup current = mc.entityRenderer.getShaderGroup();
        if (ours != null && current != ours) {
            ours = null; // replaced by someone else, or cleared when the view entity changed
        }
        if (!wanted(mc)) {
            if (ours != null) {
                mc.entityRenderer.stopUseShader();
                ours = null;
            }
            failedFor = null;
            return;
        }
        if (ours == null) {
            if (current != null || failedFor == mc.currentScreen) {
                return; // another shader is up; leave it alone
            }
            try {
                mc.entityRenderer.loadShader(SHADER);
            } catch (Throwable t) {
                Better112.LOG.warn("Menu blur could not start: {}", t.toString());
            }
            ours = mc.entityRenderer.getShaderGroup();
            if (ours == null) {
                failedFor = mc.currentScreen;
                return;
            }
            openedAt = Minecraft.getSystemTime();
        }
        float fade = Math.min(1f, (Minecraft.getSystemTime() - openedAt) / (float) FADE_MS);
        setRadius(ours, radius(mc) * fade);
    }

    /** Pixels for each of the four passes: the setting times the interface scale, so it looks alike on any screen. */
    private static float radius(Minecraft mc) {
        int scale = new ScaledResolution(mc).getScaleFactor();
        return BetterConfig.menuBlur * Math.max(1, scale) * 0.5f;
    }

    @SuppressWarnings("unchecked")
    private static void setRadius(ShaderGroup group, float radius) {
        try {
            if (shaders == null) {
                shaders = ReflectionHelper.findField(ShaderGroup.class, "listShaders", "field_148031_d");
            }
            for (Shader shader : (List<Shader>) shaders.get(group)) {
                shader.getShaderManager().getShaderUniform("Radius").set(Math.max(0.5f, radius));
            }
        } catch (Throwable t) {
            // the blur stays at the strength in its file
        }
    }

    /** Self-test: whether the blur is showing now. */
    public static String testState() {
        Minecraft mc = Minecraft.getMinecraft();
        ShaderGroup current = mc.entityRenderer.getShaderGroup();
        return "screen " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName()) + ", blur "
                + (ours != null && current == ours ? "on (radius " + radius(mc) + ")" : current != null ? "off, another shader: " + current.getShaderGroupName() : "off");
    }
}
