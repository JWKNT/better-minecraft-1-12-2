package better112.cosmetic;

import java.util.Random;

import better112.config.BetterConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Fireflies, as Minecraft 1.21.5's firefly bushes and the Effective mod show them: on warm, dry nights small
 * yellow-green lights drift over grass and plants in forests, swamps, plains and jungles, glowing on and off.
 */
public final class Fireflies {

    private static final Random RANDOM = new Random();
    private static final BlockPos.MutableBlockPos PROBE = new BlockPos.MutableBlockPos();
    public static int spawned;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        int level = BetterConfig.fireflies;
        if (event.phase != TickEvent.Phase.END || level <= 0 || mc.world == null || mc.player == null || mc.isGamePaused()
                || mc.gameSettings.particleSetting >= 2 || !night(mc.world) || mc.world.isRaining()) {
            return;
        }
        int tries = level >= 2 ? 6 : 2;
        int px = MathHelper.floor(mc.player.posX), py = MathHelper.floor(mc.player.posY), pz = MathHelper.floor(mc.player.posZ);
        for (int i = 0; i < tries; i++) {
            PROBE.setPos(px + RANDOM.nextInt(33) - 16, py + RANDOM.nextInt(13) - 6, pz + RANDOM.nextInt(33) - 16);
            if (RANDOM.nextInt(4) == 0 && fits(mc.world, PROBE)) {
                spawn(mc, PROBE.getX() + RANDOM.nextDouble(), PROBE.getY() + 0.3 + RANDOM.nextDouble() * 1.2, PROBE.getZ() + RANDOM.nextDouble());
            }
        }
    }

    /** Between dusk and dawn (the world clock from 13000 to 23000). */
    static boolean night(World world) {
        long time = world.getWorldTime() % 24000L;
        return time > 13000L && time < 23000L;
    }

    /** Open air just above grass or a plant, in a warm, leafy or marshy biome, away from the sky's light being blocked. */
    static boolean fits(World world, BlockPos pos) {
        IBlockState here = world.getBlockState(pos);
        if (!(here.getMaterial() == Material.AIR || here.getMaterial() == Material.PLANTS || here.getMaterial() == Material.VINE)) {
            return false;
        }
        IBlockState below = world.getBlockState(pos.down());
        if (below.getMaterial() != Material.GRASS && below.getMaterial() != Material.PLANTS && below.getMaterial() != Material.VINE
                && below.getMaterial() != Material.LEAVES) {
            return false;
        }
        if (!world.canSeeSky(pos)) {
            return false;
        }
        Biome biome = world.getBiome(pos);
        if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.COLD) || BiomeDictionary.hasType(biome, BiomeDictionary.Type.SNOWY)
                || BiomeDictionary.hasType(biome, BiomeDictionary.Type.DRY) || BiomeDictionary.hasType(biome, BiomeDictionary.Type.SANDY)) {
            return false;
        }
        return BiomeDictionary.hasType(biome, BiomeDictionary.Type.FOREST) || BiomeDictionary.hasType(biome, BiomeDictionary.Type.SWAMP)
                || BiomeDictionary.hasType(biome, BiomeDictionary.Type.PLAINS) || BiomeDictionary.hasType(biome, BiomeDictionary.Type.JUNGLE)
                || BiomeDictionary.hasType(biome, BiomeDictionary.Type.LUSH) || BiomeDictionary.hasType(biome, BiomeDictionary.Type.RIVER);
    }

    static void spawn(Minecraft mc, double x, double y, double z) {
        mc.effectRenderer.addEffect(new Firefly(mc.world, x, y, z));
        spawned++;
    }

    /** Self-test: let this many fireflies loose around the player, wherever they would be allowed. */
    public static int testRelease(int count) {
        Minecraft mc = Minecraft.getMinecraft();
        int made = 0;
        for (int i = 0; i < count * 40 && made < count; i++) {
            PROBE.setPos(mc.player.posX + RANDOM.nextInt(17) - 8, mc.player.posY + RANDOM.nextInt(5) - 2, mc.player.posZ + RANDOM.nextInt(17) - 8);
            if (fits(mc.world, PROBE)) {
                spawn(mc, PROBE.getX() + RANDOM.nextDouble(), PROBE.getY() + 0.3 + RANDOM.nextDouble(), PROBE.getZ() + RANDOM.nextDouble());
                made++;
            }
        }
        return made;
    }

    static final class Firefly extends Particle {
        private final float phase = RANDOM.nextFloat() * 6.28F;
        private final float blink = 0.06F + RANDOM.nextFloat() * 0.05F;
        private double targetX, targetY, targetZ;

        Firefly(World world, double x, double y, double z) {
            super(world, x, y, z, 0.0, 0.0, 0.0);
            setParticleTextureIndex(7); // the smallest soft dot
            particleScale = 0.55F + RANDOM.nextFloat() * 0.25F;
            particleMaxAge = 200 + RANDOM.nextInt(200);
            particleGravity = 0.0F;
            canCollide = true;
            setRBGColorF(0.78F, 1.0F, 0.32F);
            motionX = motionY = motionZ = 0.0;
            pickTarget();
        }

        private void pickTarget() {
            targetX = posX + (RANDOM.nextDouble() - 0.5) * 2.5;
            targetY = posY + (RANDOM.nextDouble() - 0.5) * 1.0;
            targetZ = posZ + (RANDOM.nextDouble() - 0.5) * 2.5;
        }

        @Override
        public void onUpdate() {
            prevPosX = posX;
            prevPosY = posY;
            prevPosZ = posZ;
            if (particleAge++ >= particleMaxAge || !night(world)) {
                setExpired();
                return;
            }
            if (RANDOM.nextInt(40) == 0) {
                pickTarget();
            }
            // Drift lazily towards a nearby point, changing its mind now and then.
            motionX = motionX * 0.9 + (targetX - posX) * 0.004;
            motionY = motionY * 0.9 + (targetY - posY) * 0.004;
            motionZ = motionZ * 0.9 + (targetZ - posZ) * 0.004;
            move(motionX, motionY, motionZ);
            float glow = 0.5F + 0.5F * MathHelper.sin(particleAge * blink + phase);
            float fade = Math.min(1.0F, Math.min(particleAge, particleMaxAge - particleAge) / 20.0F);
            particleAlpha = Math.max(0.0F, Math.min(1.0F, glow * glow * fade));
        }

        /** Its own light, not the dark around it. */
        @Override
        public int getBrightnessForRender(float partialTicks) {
            return 0xF000F0;
        }
    }
}
