package better112.perf;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * World.rayTraceBlocks with the same steps and the same sums, without a new point object at every block the ray
 * crosses and without a new "missed" result at every block it passes through (only the last one is ever
 * returned). Mekanism traces 40 blocks from the player every frame; mobs trace to see each other. The point is
 * made as an object only where a block's own code is handed it. Runs on both sides; loads no client classes.
 */
public final class RayTrace {

    private RayTrace() {}

    /** Set only by the self-test, to compare with the game's own method. */
    public static volatile boolean bypass;

    public static boolean enabled() {
        return !bypass;
    }

    /** As the game's Vec3d keeps a coordinate: -0 becomes 0. */
    private static double norm(double v) {
        return v == -0.0D ? 0.0D : v;
    }

    public static RayTraceResult trace(World world, Vec3d start, Vec3d end, boolean stopOnLiquid, boolean ignoreBlockWithoutBoundingBox,
            boolean returnLastUncollidableBlock) {
        if (Double.isNaN(start.x) || Double.isNaN(start.y) || Double.isNaN(start.z)) {
            return null;
        }
        if (Double.isNaN(end.x) || Double.isNaN(end.y) || Double.isNaN(end.z)) {
            return null;
        }
        int i = MathHelper.floor(end.x);
        int j = MathHelper.floor(end.y);
        int k = MathHelper.floor(end.z);
        int l = MathHelper.floor(start.x);
        int i1 = MathHelper.floor(start.y);
        int j1 = MathHelper.floor(start.z);
        BlockPos blockpos = new BlockPos(l, i1, j1);
        IBlockState iblockstate = world.getBlockState(blockpos);
        Block block = iblockstate.getBlock();
        if ((!ignoreBlockWithoutBoundingBox || iblockstate.getCollisionBoundingBox(world, blockpos) != Block.NULL_AABB)
                && block.canCollideCheck(iblockstate, stopOnLiquid)) {
            RayTraceResult raytraceresult = iblockstate.collisionRayTrace(world, blockpos, start, end);
            if (raytraceresult != null) {
                return raytraceresult;
            }
        }
        // The ray's current point; the game keeps it as a new Vec3d per step.
        double px = start.x, py = start.y, pz = start.z;
        // The last block passed through without a hit: the game makes a result for each, and returns the last.
        boolean missed = false;
        double missX = 0.0D, missY = 0.0D, missZ = 0.0D;
        EnumFacing missFacing = null;
        BlockPos missPos = null;
        int k1 = 200;
        while (k1-- >= 0) {
            if (Double.isNaN(px) || Double.isNaN(py) || Double.isNaN(pz)) {
                return null;
            }
            if (l == i && i1 == j && j1 == k) {
                return returnLastUncollidableBlock && missed ? new RayTraceResult(RayTraceResult.Type.MISS, new Vec3d(missX, missY, missZ), missFacing,
                        missPos) : null;
            }
            boolean flag2 = true;
            boolean flag = true;
            boolean flag1 = true;
            double d0 = 999.0D;
            double d1 = 999.0D;
            double d2 = 999.0D;
            if (i > l) {
                d0 = (double) l + 1.0D;
            } else if (i < l) {
                d0 = (double) l + 0.0D;
            } else {
                flag2 = false;
            }
            if (j > i1) {
                d1 = (double) i1 + 1.0D;
            } else if (j < i1) {
                d1 = (double) i1 + 0.0D;
            } else {
                flag = false;
            }
            if (k > j1) {
                d2 = (double) j1 + 1.0D;
            } else if (k < j1) {
                d2 = (double) j1 + 0.0D;
            } else {
                flag1 = false;
            }
            double d3 = 999.0D;
            double d4 = 999.0D;
            double d5 = 999.0D;
            double d6 = end.x - px;
            double d7 = end.y - py;
            double d8 = end.z - pz;
            if (flag2) {
                d3 = (d0 - px) / d6;
            }
            if (flag) {
                d4 = (d1 - py) / d7;
            }
            if (flag1) {
                d5 = (d2 - pz) / d8;
            }
            if (d3 == -0.0D) {
                d3 = -1.0E-4D;
            }
            if (d4 == -0.0D) {
                d4 = -1.0E-4D;
            }
            if (d5 == -0.0D) {
                d5 = -1.0E-4D;
            }
            EnumFacing enumfacing;
            double nx, ny, nz;
            if (d3 < d4 && d3 < d5) {
                enumfacing = i > l ? EnumFacing.WEST : EnumFacing.EAST;
                nx = d0;
                ny = py + d7 * d3;
                nz = pz + d8 * d3;
            } else if (d4 < d5) {
                enumfacing = j > i1 ? EnumFacing.DOWN : EnumFacing.UP;
                nx = px + d6 * d4;
                ny = d1;
                nz = pz + d8 * d4;
            } else {
                enumfacing = k > j1 ? EnumFacing.NORTH : EnumFacing.SOUTH;
                nx = px + d6 * d5;
                ny = py + d7 * d5;
                nz = d2;
            }
            px = norm(nx);
            py = norm(ny);
            pz = norm(nz);
            l = MathHelper.floor(px) - (enumfacing == EnumFacing.EAST ? 1 : 0);
            i1 = MathHelper.floor(py) - (enumfacing == EnumFacing.UP ? 1 : 0);
            j1 = MathHelper.floor(pz) - (enumfacing == EnumFacing.SOUTH ? 1 : 0);
            blockpos = new BlockPos(l, i1, j1);
            IBlockState iblockstate1 = world.getBlockState(blockpos);
            Block block1 = iblockstate1.getBlock();
            if (!ignoreBlockWithoutBoundingBox || iblockstate1.getMaterial() == Material.PORTAL
                    || iblockstate1.getCollisionBoundingBox(world, blockpos) != Block.NULL_AABB) {
                if (block1.canCollideCheck(iblockstate1, stopOnLiquid)) {
                    RayTraceResult raytraceresult1 = iblockstate1.collisionRayTrace(world, blockpos, new Vec3d(px, py, pz), end);
                    if (raytraceresult1 != null) {
                        return raytraceresult1;
                    }
                } else {
                    missed = true;
                    missX = px;
                    missY = py;
                    missZ = pz;
                    missFacing = enumfacing;
                    missPos = blockpos;
                }
            }
        }
        return returnLastUncollidableBlock && missed ? new RayTraceResult(RayTraceResult.Type.MISS, new Vec3d(missX, missY, missZ), missFacing,
                missPos) : null;
    }
}
