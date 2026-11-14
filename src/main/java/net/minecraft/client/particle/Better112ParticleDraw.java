package net.minecraft.client.particle;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;

/**
 * Particle.renderParticle for particles that do not turn, without the four corner objects (and their array) the game
 * makes for every particle in every frame: over a gigabyte of garbage in a minute and a half beside the sea, where
 * thousands of underwater particles are drawn. The same sums in the same order, so the same quad.
 *
 * Part of Better 1.12.2. It sits in the game's particle package only to read the protected particle fields; it is
 * called from the head of Particle.renderParticle (patch particle_corners).
 */
public final class Better112ParticleDraw {

    private Better112ParticleDraw() {}

    /** Set only by the self-test, to draw with the game's own code for comparison. */
    public static boolean bypass;

    public static boolean draw(Particle p, BufferBuilder buffer, Entity entity, float partialTicks, float rotationX,
            float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
        if (bypass || p.particleAngle != 0.0F) {
            return false; // turning particles keep the game's own code
        }
        float minU = (float) p.particleTextureIndexX / 16.0F;
        float maxU = minU + 0.0624375F;
        float minV = (float) p.particleTextureIndexY / 16.0F;
        float maxV = minV + 0.0624375F;
        float size = 0.1F * p.particleScale;
        if (p.particleTexture != null) {
            minU = p.particleTexture.getMinU();
            maxU = p.particleTexture.getMaxU();
            minV = p.particleTexture.getMinV();
            maxV = p.particleTexture.getMaxV();
        }
        float x = (float) (p.prevPosX + (p.posX - p.prevPosX) * (double) partialTicks - Particle.interpPosX);
        float y = (float) (p.prevPosY + (p.posY - p.prevPosY) * (double) partialTicks - Particle.interpPosY);
        float z = (float) (p.prevPosZ + (p.posZ - p.prevPosZ) * (double) partialTicks - Particle.interpPosZ);
        int light = p.getBrightnessForRender(partialTicks);
        int sky = light >> 16 & 65535, block = light & 65535;
        float r = p.particleRed, g = p.particleGreen, b = p.particleBlue, a = p.particleAlpha;
        buffer.pos((double) x + corner(-rotationX * size - rotationXY * size), (double) y + corner(-rotationZ * size),
                (double) z + corner(-rotationYZ * size - rotationXZ * size)).tex((double) maxU, (double) maxV).color(r, g, b, a)
                .lightmap(sky, block).endVertex();
        buffer.pos((double) x + corner(-rotationX * size + rotationXY * size), (double) y + corner(rotationZ * size),
                (double) z + corner(-rotationYZ * size + rotationXZ * size)).tex((double) maxU, (double) minV).color(r, g, b, a)
                .lightmap(sky, block).endVertex();
        buffer.pos((double) x + corner(rotationX * size + rotationXY * size), (double) y + corner(rotationZ * size),
                (double) z + corner(rotationYZ * size + rotationXZ * size)).tex((double) minU, (double) minV).color(r, g, b, a)
                .lightmap(sky, block).endVertex();
        buffer.pos((double) x + corner(rotationX * size - rotationXY * size), (double) y + corner(-rotationZ * size),
                (double) z + corner(rotationYZ * size - rotationXZ * size)).tex((double) minU, (double) maxV).color(r, g, b, a)
                .lightmap(sky, block).endVertex();
        return true;
    }

    /** One position reused for every particle's light lookup; particles are drawn on the game's thread only. */
    private static final net.minecraft.util.math.BlockPos.MutableBlockPos AT = new net.minecraft.util.math.BlockPos.MutableBlockPos();

    /**
     * Particle.getBrightnessForRender without a new BlockPos per particle per frame: the same block (each coordinate
     * rounded down), the same two world calls, which only read the position.
     */
    public static int light(Particle p) {
        if (bypass) {
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(p.posX, p.posY, p.posZ);
            return p.world.isBlockLoaded(pos) ? p.world.getCombinedLight(pos, 0) : 0;
        }
        AT.setPos(p.posX, p.posY, p.posZ);
        return p.world.isBlockLoaded(AT) ? p.world.getCombinedLight(AT, 0) : 0;
    }

    /** A corner offset as the game's Vec3d keeps it: widened to double, with -0 made 0. */
    private static double corner(float v) {
        double d = v;
        return d == -0.0D ? 0.0D : d;
    }
}
