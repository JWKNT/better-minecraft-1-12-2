package better112.cosmetic;

import java.util.Random;

import better112.config.BetterConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleDigging;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Leaves drifting down from trees, as the Falling Leaves mod (and, for cherry trees, Minecraft 1.20) shows
 * them: a scrap of the tree's own leaf texture, in the tree's own colour, that sways as it falls and settles
 * on the ground for a moment before fading.
 */
public final class FallingLeaves {

    private static final Random RANDOM = new Random();
    private static final BlockPos.MutableBlockPos PROBE = new BlockPos.MutableBlockPos();
    public static int spawned;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        int level = BetterConfig.fallingLeaves;
        if (event.phase != TickEvent.Phase.END || level <= 0 || mc.world == null || mc.player == null || mc.isGamePaused()
                || mc.gameSettings.particleSetting >= 2) {
            return;
        }
        // Random places around the player, as the game picks blocks for their own ambient particles.
        int samples = level >= 2 ? 240 : 120;
        int chance = level >= 2 ? 12 : 30;
        int px = MathHelper.floor(mc.player.posX), py = MathHelper.floor(mc.player.posY), pz = MathHelper.floor(mc.player.posZ);
        for (int i = 0; i < samples; i++) {
            PROBE.setPos(px + RANDOM.nextInt(33) - 16, py + RANDOM.nextInt(25) - 8, pz + RANDOM.nextInt(33) - 16);
            IBlockState state = mc.world.getBlockState(PROBE);
            if ((state.getMaterial() == Material.LEAVES || state.getBlock() instanceof BlockLeaves) && RANDOM.nextInt(chance) == 0) {
                BlockPos below = PROBE.down();
                if (mc.world.isAirBlock(below)) {
                    spawn(mc, state, PROBE.toImmutable());
                }
            }
        }
    }

    static void spawn(Minecraft mc, IBlockState state, BlockPos pos) {
        mc.effectRenderer.addEffect(new Leaf(mc.world, pos.getX() + RANDOM.nextDouble(), pos.getY() - 0.05, pos.getZ() + RANDOM.nextDouble(), state, pos));
        spawned++;
    }

    /** Self-test: drop a leaf from every leaf block within this radius that has air under it. */
    public static int testDrop(int radius) {
        Minecraft mc = Minecraft.getMinecraft();
        BlockPos at = new BlockPos(mc.player);
        int count = 0;
        for (BlockPos pos : BlockPos.getAllInBoxMutable(at.add(-radius, -4, -radius), at.add(radius, 12, radius))) {
            IBlockState state = mc.world.getBlockState(pos);
            if (state.getMaterial() == Material.LEAVES && mc.world.isAirBlock(pos.down()) && RANDOM.nextInt(3) == 0) {
                spawn(mc, state, pos.toImmutable());
                count++;
            }
        }
        return count;
    }

    static final class Leaf extends ParticleDigging {
        /** Particles only move on the client thread. */
        private static final BlockPos.MutableBlockPos AT = new BlockPos.MutableBlockPos();
        private final float phase = RANDOM.nextFloat() * 6.28F;
        private final double driftX = (RANDOM.nextDouble() - 0.5) * 0.02, driftZ = (RANDOM.nextDouble() - 0.5) * 0.02;
        private int restTicks;

        Leaf(World world, double x, double y, double z, IBlockState state, BlockPos pos) {
            super(world, x, y, z, 0.0, 0.0, 0.0, state);
            // Block-break bits start at 60% grey; a leaf is the tree's own colour at full strength.
            particleRed = particleGreen = particleBlue = 1.0F;
            setBlockPos(pos); // the tree's own colour where it grows
            particleScale = 1.2F + RANDOM.nextFloat() * 0.6F;
            particleMaxAge = 160 + RANDOM.nextInt(120);
            particleGravity = 0.0F;
            motionX = driftX;
            motionY = -0.01;
            motionZ = driftZ;
        }

        @Override
        public void onUpdate() {
            prevPosX = posX;
            prevPosY = posY;
            prevPosZ = posZ;
            if (particleAge++ >= particleMaxAge || restTicks > 40) {
                setExpired();
                return;
            }
            if (onGround) {
                restTicks++;
                particleAlpha = Math.max(0.0F, 1.0F - restTicks / 40.0F);
                return;
            }
            float t = particleAge * 0.12F + phase;
            motionY = Math.max(motionY - 0.0012, -0.045);
            motionX = driftX + MathHelper.sin(t) * 0.025;
            motionZ = driftZ + MathHelper.cos(t * 0.8F) * 0.018;
            // A leaf only drifts and falls, so the blocks at its next spot are all it can meet. The game's own
            // move() asks the world for every collision box near the particle, which made most of the
            // garbage of falling leaves.
            double x = posX, y = posY + motionY, z = posZ;
            double top = topBelow(world, x, y, z);
            if (y <= top && posY >= top - 0.001D) {
                y = top;
                onGround = true;
                motionX = motionY = motionZ = 0.0D;
            } else if (!solidAt(world, x + motionX, y, z + motionZ)) {
                x += motionX;
                z += motionZ;
            }
            AxisAlignedBB box = getBoundingBox();
            setBoundingBox(box.offset(x - posX, y - posY, z - posZ));
            posX = x;
            posY = y;
            posZ = z;
            if (world.getBlockState(AT.setPos(posX, posY, posZ)).getMaterial().isLiquid()) {
                setExpired(); // a leaf in water or lava is simply gone
            }
        }

        /** The height a leaf at this spot lands on: the top of the solid part of the block it is in, or below everything. */
        private static double topBelow(World world, double x, double y, double z) {
            AT.setPos(x, y, z);
            IBlockState state = world.getBlockState(AT);
            AxisAlignedBB solid = state.getCollisionBoundingBox(world, AT);
            return solid == null || solid == Block.NULL_AABB ? Double.NEGATIVE_INFINITY : AT.getY() + solid.maxY;
        }

        private static boolean solidAt(World world, double x, double y, double z) {
            AT.setPos(x, y, z);
            IBlockState state = world.getBlockState(AT);
            AxisAlignedBB solid = state.getCollisionBoundingBox(world, AT);
            return solid != null && solid != Block.NULL_AABB && y < AT.getY() + solid.maxY && y >= AT.getY() + solid.minY;
        }
    }
}
