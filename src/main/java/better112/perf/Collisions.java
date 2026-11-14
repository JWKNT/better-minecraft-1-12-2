package better112.perf;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

/**
 * Block collision boxes without a new box for every block looked at. When a mob, item or player moves, the game
 * asks every block around its path for its collision box, moves that box to the block's position (a new object)
 * and only then checks whether it touches the mover. Most do not, so the new box is garbage at once; it was the
 * largest single source of garbage on the server thread. This checks first and makes the box only when it is
 * kept. Runs on both sides; loads no client classes.
 */
public final class Collisions {

    private Collisions() {}

    /**
     * Block.addCollisionBoxToList with the same result. Returns false (the game's own code then runs) for boxes of
     * mod classes that may change how they move or touch.
     */
    public static boolean add(BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> list, AxisAlignedBB blockBox) {
        if (blockBox == Block.NULL_AABB) {
            return true;
        }
        if (blockBox == null || entityBox == null || blockBox.getClass() != AxisAlignedBB.class || entityBox.getClass() != AxisAlignedBB.class) {
            return false;
        }
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        double minX = blockBox.minX + x, minY = blockBox.minY + y, minZ = blockBox.minZ + z;
        double maxX = blockBox.maxX + x, maxY = blockBox.maxY + y, maxZ = blockBox.maxZ + z;
        if (entityBox.minX < maxX && entityBox.maxX > minX && entityBox.minY < maxY && entityBox.maxY > minY
                && entityBox.minZ < maxZ && entityBox.maxZ > minZ) {
            list.add(new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ));
        }
        return true;
    }
}
