package better112.world.feature;

import java.io.File;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The blocks brought over from modern jars, as the worldgen side sees them. ModernContent sets the
 * lookup on both sides once it has read the jars. A dedicated server without the jars has none:
 * Modern Terrain then builds its worlds from the blocks 1.12.2 and the installed mods have.
 */
public final class Imported {

    private Imported() {}

    public interface Lookup {
        Block block(String modernId);

        boolean isTallPlant(Block block);

        boolean placeTall(World world, BlockPos pos, Block block, int flags);

        boolean isAquatic(Block block);

        boolean placeAquatic(World world, BlockPos pos, IBlockState state, int flags);

        File sourceJar();
    }

    /** The namespace of the modern mod whose biomes and blocks are brought over. */
    public static final String MOD_NAMESPACE = "biomesoplenty";

    private static Lookup lookup;

    public static void set(Lookup found) {
        lookup = found;
    }

    /** True where the imported blocks can be in a world made here: in the game, and on a server with the jars. */
    public static boolean present() {
        return lookup != null;
    }

    public static Block block(String modernId) {
        return lookup == null ? null : lookup.block(modernId);
    }

    public static boolean isTallPlant(Block block) {
        return lookup != null && lookup.isTallPlant(block);
    }

    public static boolean placeTall(World world, BlockPos pos, Block block, int flags) {
        return lookup != null && lookup.placeTall(world, pos, block, flags);
    }

    public static boolean isAquatic(Block block) {
        return lookup != null && lookup.isAquatic(block);
    }

    public static boolean placeAquatic(World world, BlockPos pos, IBlockState state, int flags) {
        return lookup != null && state != null && lookup.placeAquatic(world, pos, state, flags);
    }

    /** The Biomes O' Plenty jar the imported blocks came from, or null. */
    public static File sourceJar() {
        return lookup == null ? null : lookup.sourceJar();
    }
}
