package better112.qol;

import java.util.Locale;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Arrival titles, as Traveler's Titles gives modern packs: the name of a new dimension, or of a biome you have
 * walked into and stayed in for a moment, fades in above the middle of the screen and out again.
 */
public final class Titles {

    /** Ticks a new biome must stay under your feet before it is named, so a border walked along stays quiet. */
    private static final int SETTLE = 30;
    private static final long SHOW_MS = 3500L, FADE_MS = 600L;

    private static int dimension = Integer.MIN_VALUE;
    private static Biome pending, shown;
    private static int pendingTicks;
    private static String title, subtitle;
    private static long shownAt;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            dimension = Integer.MIN_VALUE;
            shown = pending = null;
            return;
        }
        BlockPos pos = new BlockPos(mc.player);
        if (!mc.world.isBlockLoaded(pos)) {
            return;
        }
        Biome biome = mc.world.getBiome(pos);
        if (mc.player.dimension != dimension) {
            // A new world or another dimension: its name, with the biome below it.
            dimension = mc.player.dimension;
            shown = biome;
            pending = null;
            show(dimensionName(dimension), biomeName(biome));
            return;
        }
        if (biome == shown) {
            pending = null;
            return;
        }
        if (biome != pending) {
            pending = biome;
            pendingTicks = 0;
        } else if (++pendingTicks >= SETTLE) {
            shown = biome;
            pending = null;
            show(biomeName(biome), null);
        }
    }

    private static void show(String main, String sub) {
        if (!BetterConfig.biomeTitles) {
            return;
        }
        title = main;
        subtitle = sub;
        shownAt = 0L; // the clock starts once the title can be seen (not under the loading screen)
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || title == null || !BetterConfig.biomeTitles) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof net.minecraft.client.gui.GuiDownloadTerrain) {
            return;
        }
        if (shownAt == 0L) {
            shownAt = System.currentTimeMillis();
        }
        long age = System.currentTimeMillis() - shownAt;
        if (age > SHOW_MS) {
            title = null;
            return;
        }
        if (mc.gameSettings.hideGUI || mc.gameSettings.showDebugInfo) {
            return;
        }
        float alpha = Math.min(1.0F, Math.min(age, SHOW_MS - age) / (float) FADE_MS);
        if (alpha <= 0.02F) {
            return;
        }
        ScaledResolution res = event.getResolution();
        FontRenderer font = mc.fontRenderer;
        int a = Math.max(4, (int) (alpha * 255)) << 24;
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.translate(res.getScaledWidth() / 2.0F, res.getScaledHeight() / 4.0F, 0.0F);
        GlStateManager.pushMatrix();
        GlStateManager.scale(2.0F, 2.0F, 2.0F);
        font.drawStringWithShadow(title, -font.getStringWidth(title) / 2.0F, 0.0F, a | 0xFFFFFF);
        GlStateManager.popMatrix();
        if (subtitle != null) {
            font.drawStringWithShadow(subtitle, -font.getStringWidth(subtitle) / 2.0F, 22.0F, a | 0xC8C8C8);
        }
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /** Biome names as written, with the missing spaces of some old names put back (ForestHills). */
    static String biomeName(Biome biome) {
        String name = biome.getBiomeName();
        return name == null ? "" : name.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    static String dimensionName(int id) {
        try {
            String name = DimensionType.getById(id).getName();
            StringBuilder text = new StringBuilder();
            for (String word : name.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").split(" ")) {
                if (!word.isEmpty()) {
                    String lower = word.toLowerCase(Locale.ROOT);
                    boolean small = text.length() > 0 && (lower.equals("the") || lower.equals("of"));
                    text.append(text.length() == 0 ? "" : " ").append(small ? lower : Character.toUpperCase(word.charAt(0)) + word.substring(1));
                }
            }
            return text.toString();
        } catch (Throwable t) {
            return "Dimension " + id;
        }
    }

    /** Self-test: what is being shown now. */
    public static String testState() {
        Minecraft mc = Minecraft.getMinecraft();
        return "showing '" + title + "' / '" + subtitle + "', biome here " + biomeName(mc.world.getBiome(new BlockPos(mc.player)));
    }

    /** Self-test: as if the player had just walked into this biome and stayed. */
    public static void testShow(String main, String sub) {
        show(main, sub);
    }
}
