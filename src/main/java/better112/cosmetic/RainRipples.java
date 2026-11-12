package better112.cosmetic;

import java.util.Random;

import better112.config.BetterConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Rain on water, as the Effective and Particle Rain mods draw it: where rain falls on open water, small rings
 * spread across the surface and fade. The ring is the mod's own picture, stitched into the block atlas.
 */
public final class RainRipples {

    private static final ResourceLocation RING = new ResourceLocation("better112", "particle/ripple");
    private static final Random RANDOM = new Random();
    private static final BlockPos.MutableBlockPos PROBE = new BlockPos.MutableBlockPos();
    static TextureAtlasSprite sprite;
    public static int spawned, drawnLastFrame;

    @SubscribeEvent
    public void onStitch(TextureStitchEvent.Pre event) {
        if (event.getMap() == Minecraft.getMinecraft().getTextureMapBlocks()) {
            sprite = event.getMap().registerSprite(RING);
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END || !BetterConfig.rainRipples || sprite == null || mc.world == null || mc.player == null
                || mc.isGamePaused() || mc.gameSettings.particleSetting >= 2) {
            return;
        }
        float rain = mc.world.getRainStrength(1.0F);
        if (rain <= 0.0F) {
            return;
        }
        int tries = (int) (60 * rain) / (mc.gameSettings.particleSetting + 1);
        int px = MathHelper.floor(mc.player.posX), pz = MathHelper.floor(mc.player.posZ);
        for (int i = 0; i < tries; i++) {
            int x = px + RANDOM.nextInt(25) - 12, z = pz + RANDOM.nextInt(25) - 12;
            BlockPos top = mc.world.getPrecipitationHeight(new BlockPos(x, 0, z));
            if (Math.abs(top.getY() - mc.player.posY) > 24 || !mc.world.isRainingAt(top)) {
                continue;
            }
            PROBE.setPos(x, top.getY() - 1, z);
            IBlockState water = mc.world.getBlockState(PROBE);
            if (water.getMaterial() == Material.WATER) {
                spawn(mc, x + 0.1 + RANDOM.nextDouble() * 0.8, surface(mc.world, PROBE), z + 0.1 + RANDOM.nextDouble() * 0.8);
            }
        }
    }

    /** Rings now on the water: x, y, z, age in ticks, lifetime in ticks. */
    private static final java.util.List<double[]> RINGS = new java.util.ArrayList<double[]>();

    static void spawn(Minecraft mc, double x, double y, double z) {
        if (RINGS.size() < 600) {
            RINGS.add(new double[] {x, y, z, 0, 8 + RANDOM.nextInt(5)});
            spawned++;
        }
    }

    /** Where a ring lies on water: the top of the water block, which a source fills to eight ninths. */
    static double surface(World world, BlockPos waterPos) {
        IBlockState state = world.getBlockState(waterPos);
        int level = state.getPropertyKeys().contains(net.minecraft.block.BlockLiquid.LEVEL) ? state.getValue(net.minecraft.block.BlockLiquid.LEVEL) : 0;
        return waterPos.getY() + 1.0 - net.minecraft.block.BlockLiquid.getLiquidHeightPercent(level) + 0.01;
    }

    @SubscribeEvent
    public void onAge(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getMinecraft().isGamePaused()) {
            return;
        }
        for (java.util.Iterator<double[]> it = RINGS.iterator(); it.hasNext();) {
            double[] ring = it.next();
            if (++ring[3] >= ring[4]) {
                it.remove();
            }
        }
        if (Minecraft.getMinecraft().world == null) {
            RINGS.clear();
        }
    }

    /**
     * Drawn after the world, water included: rings drawn with the particles would come before the water and
     * hide it where they lie, showing the ground under it instead.
     */
    @SubscribeEvent
    public void onRender(net.minecraftforge.client.event.RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (RINGS.isEmpty() || sprite == null || mc.getRenderViewEntity() == null) {
            return;
        }
        Entity eye = mc.getRenderViewEntity();
        float partial = event.getPartialTicks();
        double cx = eye.lastTickPosX + (eye.posX - eye.lastTickPosX) * partial;
        double cy = eye.lastTickPosY + (eye.posY - eye.lastTickPosY) * partial;
        double cz = eye.lastTickPosZ + (eye.posZ - eye.lastTickPosZ) * partial;
        // Whatever the last renderer left behind is unknown here, so every state the ring needs is set.
        net.minecraft.client.renderer.GlStateManager.enableTexture2D();
        net.minecraft.client.renderer.GlStateManager.disableLighting();
        net.minecraft.client.renderer.GlStateManager.enableDepth();
        net.minecraft.client.renderer.GlStateManager.enableAlpha();
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        net.minecraft.client.renderer.GlStateManager.blendFunc(net.minecraft.client.renderer.GlStateManager.SourceFactor.SRC_ALPHA,
                net.minecraft.client.renderer.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        net.minecraft.client.renderer.GlStateManager.depthMask(false);
        net.minecraft.client.renderer.GlStateManager.disableCull();
        net.minecraft.client.renderer.GlStateManager.alphaFunc(516, 0.003921569F);
        mc.entityRenderer.enableLightmap();
        net.minecraft.client.renderer.Tessellator tessellator = net.minecraft.client.renderer.Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        float u0 = sprite.getMinU(), u1 = sprite.getMaxU(), v0 = sprite.getMinV(), v1 = sprite.getMaxV();
        for (double[] ring : RINGS) {
            float age = (float) ((ring[3] + partial) / ring[4]);
            if (age >= 1.0F) {
                continue;
            }
            double r = 0.08 + age * 0.32;
            float alpha = 0.6F * (1.0F - age);
            int light = mc.world.getCombinedLight(new BlockPos(ring[0], ring[1] + 0.5, ring[2]), 0);
            int sky = light >> 16 & 65535, block = light & 65535;
            double x = ring[0] - cx, y = ring[1] - cy, z = ring[2] - cz;
            buffer.pos(x - r, y, z - r).tex(u0, v0).color(0.85F, 0.92F, 1.0F, alpha).lightmap(sky, block).endVertex();
            buffer.pos(x - r, y, z + r).tex(u0, v1).color(0.85F, 0.92F, 1.0F, alpha).lightmap(sky, block).endVertex();
            buffer.pos(x + r, y, z + r).tex(u1, v1).color(0.85F, 0.92F, 1.0F, alpha).lightmap(sky, block).endVertex();
            buffer.pos(x + r, y, z - r).tex(u1, v0).color(0.85F, 0.92F, 1.0F, alpha).lightmap(sky, block).endVertex();
        }
        tessellator.draw();
        drawnLastFrame = RINGS.size();
        mc.entityRenderer.disableLightmap();
        net.minecraft.client.renderer.GlStateManager.enableCull();
        net.minecraft.client.renderer.GlStateManager.depthMask(true);
        net.minecraft.client.renderer.GlStateManager.disableBlend();
    }

    public static String testSprite() {
        if (sprite == null) {
            return "no sprite";
        }
        net.minecraft.client.renderer.texture.TextureAtlasSprite live = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(RING.toString());
        return "alive " + RINGS.size() + ", drawn last frame " + drawnLastFrame + ", " + sprite.getIconName() + " " + sprite.getIconWidth() + "x" + sprite.getIconHeight() + " u " + sprite.getMinU() + "-" + sprite.getMaxU()
                + " v " + sprite.getMinV() + "-" + sprite.getMaxV() + ", atlas has " + live.getIconName() + (live == sprite ? " (same)" : " (different object)");
    }

    /** Self-test: rings on the water around the player, rain or not. */
    public static int testSplash(int count) {
        Minecraft mc = Minecraft.getMinecraft();
        if (sprite == null) {
            return -1; // the ring never made it into the atlas
        }
        int made = 0;
        for (int i = 0; i < count * 30 && made < count; i++) {
            int x = MathHelper.floor(mc.player.posX) + RANDOM.nextInt(13) - 6, z = MathHelper.floor(mc.player.posZ) + RANDOM.nextInt(13) - 6;
            BlockPos top = mc.world.getPrecipitationHeight(new BlockPos(x, 0, z));
            if (mc.world.getBlockState(top.down()).getMaterial() == Material.WATER) {
                spawn(mc, x + 0.5, surface(mc.world, top.down()), z + 0.5);
                made++;
            }
        }
        return made;
    }
}
