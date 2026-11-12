package better112.world.feature;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockHugeMushroom;
import net.minecraft.block.BlockPumpkin;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenShrub;
import net.minecraft.world.gen.feature.WorldGenVines;

/**
 * The small decorations Biomes O' Plenty defines entirely in code: its data files only say
 * "a fallen log goes here" or "a patch of leaf piles", with no settings. Each is rebuilt
 * here by name, in the spirit of the original: what it is made of and roughly how big.
 */
final class Decor {

    private Decor() {}

    private static final int FLAGS = 2 | 16;
    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "red_maple_leaf_pile", "orange_maple_leaf_pile", "yellow_maple_leaf_pile",
            "fallen_log", "fallen_fir_log", "fallen_birch_log", "fallen_jacaranda_log",
            "scattered_rocks", "scrub", "crag_splatter", "mycelium_splatter", "mossy_black_sand_splatter", "moss_splatter",
            "huge_toadstool", "small_toadstool", "small_red_mushroom", "small_brown_mushroom",
            "pumpkin_patch", "big_pumpkin", "high_grass", "sparse_dune_grass", "rooted_stump", "rainforest_cliffs_vines"));

    static boolean knows(String kind) {
        return KNOWN.contains(kind);
    }

    private static IBlockState bop(String name) {
        return States.of("biomesoplenty:" + name, null);
    }

    private static IBlockState vanilla(String name) {
        return States.of("minecraft:" + name, null);
    }

    static boolean generate(String kind, World world, Random random, BlockPos pos) {
        switch (kind) {
            case "red_maple_leaf_pile":
            case "orange_maple_leaf_pile":
            case "yellow_maple_leaf_pile":
                return scatter(world, random, pos, bop(kind), 24, 5, false);
            case "fallen_log":
                return fallenLog(world, random, pos, vanilla("oak_log"));
            case "fallen_fir_log":
                return fallenLog(world, random, pos, bop("fir_log"));
            case "fallen_birch_log":
                return fallenLog(world, random, pos, vanilla("birch_log"));
            case "fallen_jacaranda_log":
                return fallenLog(world, random, pos, bop("jacaranda_log"));
            case "scattered_rocks":
                return rocks(world, random, pos);
            case "scrub":
                return new WorldGenShrub(vanilla("oak_log"), vanilla("oak_leaves")).generate(world, random, pos);
            case "crag_splatter":
                return splatter(world, random, pos, random.nextBoolean() ? Blocks.MOSSY_COBBLESTONE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState());
            case "mycelium_splatter":
                return splatter(world, random, pos, Blocks.MYCELIUM.getDefaultState());
            case "moss_splatter":
                return splatter(world, random, pos, States.of("minecraft:moss_block", null));
            case "mossy_black_sand_splatter":
                return splatter(world, random, pos, bop("mossy_black_sand"));
            case "huge_toadstool":
                return mushroom(world, random, pos, bop("toadstool_block"), 4 + random.nextInt(4), 2);
            case "small_toadstool":
                return mushroom(world, random, pos, bop("toadstool_block"), 1 + random.nextInt(2), 1);
            case "small_red_mushroom":
                return mushroom(world, random, pos, cap(Blocks.RED_MUSHROOM_BLOCK), 1 + random.nextInt(3), 1);
            case "small_brown_mushroom":
                return mushroom(world, random, pos, cap(Blocks.BROWN_MUSHROOM_BLOCK), 1 + random.nextInt(3), 1);
            case "pumpkin_patch":
                return pumpkins(world, random, pos);
            case "big_pumpkin":
                return bigPumpkin(world, random, pos);
            case "high_grass":
                return highGrass(world, random, pos);
            case "sparse_dune_grass":
                return scatter(world, random, pos, bop("dune_grass"), 6, 4, true);
            case "rooted_stump":
                return stump(world, random, pos);
            case "rainforest_cliffs_vines":
                return new WorldGenVines().generate(world, random, pos);
            default:
                return false;
        }
    }

    private static boolean ground(World world, BlockPos pos) {
        return world.isAirBlock(pos) && world.getBlockState(pos.down()).isSideSolid(world, pos.down(), EnumFacing.UP);
    }

    /** A loose handful of one block on the ground around a spot: leaf piles under a tree, grass on a dune. */
    private static boolean scatter(World world, Random random, BlockPos pos, IBlockState state, int tries, int spread, boolean plant) {
        if (state == null) {
            return false;
        }
        boolean any = false;
        for (int i = 0; i < tries; i++) {
            BlockPos at = pos.add(random.nextInt(spread * 2 + 1) - spread, random.nextInt(5) - 2, random.nextInt(spread * 2 + 1) - spread);
            if (plant ? world.isAirBlock(at) && Features.canStay(world, at, state) : ground(world, at)) {
                world.setBlockState(at, state, FLAGS);
                any = true;
            }
        }
        return any;
    }

    /** A log on its side, three to six long, with the odd mushroom on it. */
    private static boolean fallenLog(World world, Random random, BlockPos pos, IBlockState log) {
        if (log == null) {
            return false;
        }
        EnumFacing along = random.nextBoolean() ? EnumFacing.EAST : EnumFacing.SOUTH;
        int length = 3 + random.nextInt(4);
        for (int i = 0; i < length; i++) {
            if (!ground(world, pos.offset(along, i))) {
                return false; // it has to lie flat
            }
        }
        IBlockState lying = States.alongAxis(log, along.getAxis());
        for (int i = 0; i < length; i++) {
            BlockPos at = pos.offset(along, i);
            world.setBlockState(at, lying, FLAGS);
            if (random.nextInt(4) == 0 && world.isAirBlock(at.up())) {
                world.setBlockState(at.up(), (random.nextBoolean() ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM).getDefaultState(), FLAGS);
            }
        }
        return true;
    }

    private static boolean rocks(World world, Random random, BlockPos pos) {
        boolean any = false;
        IBlockState[] stones = {Blocks.COBBLESTONE.getDefaultState(), Blocks.MOSSY_COBBLESTONE.getDefaultState(), Blocks.STONE.getDefaultState(),
            vanilla("andesite")};
        for (int i = 2 + random.nextInt(4); i > 0; i--) {
            BlockPos at = pos.add(random.nextInt(7) - 3, random.nextInt(3) - 1, random.nextInt(7) - 3);
            if (ground(world, at)) {
                world.setBlockState(at, stones[random.nextInt(stones.length)], FLAGS);
                any = true;
            }
        }
        return any;
    }

    /** A blotch on the ground: the top block of each column in a rough circle becomes something else. */
    private static boolean splatter(World world, Random random, BlockPos pos, IBlockState state) {
        if (state == null) {
            return false;
        }
        int radius = 2 + random.nextInt(3);
        boolean any = false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius - random.nextInt(3)) {
                    continue;
                }
                for (int dy = 2; dy >= -2; dy--) {
                    BlockPos at = pos.add(dx, dy, dz);
                    IBlockState there = world.getBlockState(at);
                    Material material = there.getMaterial();
                    if (there.isFullCube() && world.isAirBlock(at.up())
                            && (material == Material.GRASS || material == Material.GROUND || material == Material.SAND || material == Material.ROCK)) {
                        world.setBlockState(at, state, FLAGS);
                        any = true;
                        break;
                    }
                }
            }
        }
        return any;
    }

    private static IBlockState cap(Block mushroom) {
        return mushroom.getDefaultState().withProperty(BlockHugeMushroom.VARIANT, BlockHugeMushroom.EnumType.ALL_OUTSIDE);
    }

    /** A mushroom with a stem and a domed cap: small ones a block or two tall, a huge one taller than a player. */
    private static boolean mushroom(World world, Random random, BlockPos pos, IBlockState cap, int stemHeight, int capRadius) {
        if (cap == null || !ground(world, pos)) {
            return false;
        }
        for (int y = 0; y <= stemHeight + 1; y++) {
            if (!world.isAirBlock(pos.up(y))) {
                return false;
            }
        }
        IBlockState stem = Blocks.RED_MUSHROOM_BLOCK.getDefaultState().withProperty(BlockHugeMushroom.VARIANT, BlockHugeMushroom.EnumType.STEM);
        for (int y = 0; y < stemHeight; y++) {
            world.setBlockState(pos.up(y), stem, FLAGS);
        }
        BlockPos top = pos.up(stemHeight);
        for (int dx = -capRadius; dx <= capRadius; dx++) {
            for (int dz = -capRadius; dz <= capRadius; dz++) {
                boolean corner = Math.abs(dx) == capRadius && Math.abs(dz) == capRadius;
                if ((!corner || capRadius == 1 && random.nextBoolean()) && world.isAirBlock(top.add(dx, 0, dz))) {
                    world.setBlockState(top.add(dx, 0, dz), cap, FLAGS);
                }
            }
        }
        if (capRadius > 1) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (world.isAirBlock(top.add(dx, 1, dz))) {
                        world.setBlockState(top.add(dx, 1, dz), cap, FLAGS);
                    }
                }
            }
        }
        return true;
    }

    private static boolean pumpkins(World world, Random random, BlockPos pos) {
        boolean any = false;
        for (int i = 0; i < 24; i++) {
            BlockPos at = pos.add(random.nextInt(9) - 4, random.nextInt(5) - 2, random.nextInt(9) - 4);
            if (world.isAirBlock(at) && world.getBlockState(at.down()).getMaterial() == Material.GRASS && random.nextInt(3) == 0) {
                world.setBlockState(at, Blocks.PUMPKIN.getDefaultState().withProperty(BlockPumpkin.FACING, EnumFacing.HORIZONTALS[random.nextInt(4)]), FLAGS);
                any = true;
            }
        }
        return any;
    }

    /** A pumpkin the size of a hut, with a stub of stem on top. */
    private static boolean bigPumpkin(World world, Random random, BlockPos pos) {
        if (!ground(world, pos)) {
            return false;
        }
        int radius = 2 + random.nextInt(2);
        BlockPos center = pos.up(radius - 1);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius + 1; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // A ball squashed a little from the top, as a pumpkin is.
                    if (dx * dx + dy * dy * 1.3D + dz * dz <= radius * radius + 0.5D) {
                        BlockPos at = center.add(dx, dy, dz);
                        if (world.getBlockState(at).getBlock().isReplaceable(world, at)) {
                            world.setBlockState(at, Blocks.PUMPKIN.getDefaultState().withProperty(BlockPumpkin.FACING,
                                    EnumFacing.HORIZONTALS[random.nextInt(4)]), FLAGS);
                        }
                    }
                }
            }
        }
        IBlockState stem = vanilla("oak_log");
        for (int y = 0; y < 2; y++) {
            BlockPos at = center.up(radius + y);
            if (world.isAirBlock(at)) {
                world.setBlockState(at, stem, FLAGS);
            }
        }
        return true;
    }

    /** Grass taller than a player: a column of stalk with a tuft on top. */
    private static boolean highGrass(World world, Random random, BlockPos pos) {
        IBlockState stalk = bop("high_grass_plant"), tuft = bop("high_grass");
        if (stalk == null || tuft == null) {
            return false;
        }
        boolean any = false;
        for (int i = 0; i < 24; i++) {
            BlockPos at = pos.add(random.nextInt(9) - 4, random.nextInt(5) - 2, random.nextInt(9) - 4);
            if (!world.isAirBlock(at) || !Features.canStay(world, at, tuft)) {
                continue;
            }
            int height = 1 + random.nextInt(3);
            int y = 0;
            for (; y < height && world.isAirBlock(at.up(y + 1)); y++) {
                world.setBlockState(at.up(y), stalk, FLAGS);
            }
            world.setBlockState(at.up(y), tuft, FLAGS);
            any = true;
        }
        return any;
    }

    /** What is left of a felled tree: a stub of trunk with roots running out along the ground. */
    private static boolean stump(World world, Random random, BlockPos pos) {
        IBlockState log = vanilla("oak_log");
        if (!ground(world, pos)) {
            return false;
        }
        world.setBlockState(pos, log, FLAGS);
        if (random.nextBoolean() && world.isAirBlock(pos.up())) {
            world.setBlockState(pos.up(), log, FLAGS);
        }
        for (EnumFacing side : EnumFacing.HORIZONTALS) {
            if (random.nextInt(3) != 0 && ground(world, pos.offset(side))) {
                world.setBlockState(pos.offset(side), States.alongAxis(log, side.getAxis()), FLAGS);
            }
        }
        return true;
    }
}
