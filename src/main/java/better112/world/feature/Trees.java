package better112.world.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenAbstractTree;
import net.minecraft.world.gen.feature.WorldGenBigTree;
import net.minecraft.world.gen.feature.WorldGenBirchTree;
import net.minecraft.world.gen.feature.WorldGenCanopyTree;
import net.minecraft.world.gen.feature.WorldGenMegaJungle;
import net.minecraft.world.gen.feature.WorldGenMegaPineTree;
import net.minecraft.world.gen.feature.WorldGenSavannaTree;
import net.minecraft.world.gen.feature.WorldGenShrub;
import net.minecraft.world.gen.feature.WorldGenSwamp;
import net.minecraft.world.gen.feature.WorldGenTaiga1;
import net.minecraft.world.gen.feature.WorldGenTaiga2;
import net.minecraft.world.gen.feature.WorldGenTrees;

/**
 * Trees. Vanilla tree features are handed to the 1.12.2 generator for the same tree. The
 * tree kinds Biomes O' Plenty defines in its own code (a "taiga tree", a "twiglet", a
 * "redwood") are rebuilt here from what their data says: which log and leaves, how tall,
 * and each kind's few shape numbers. The shapes follow the originals closely where the
 * original is a known vanilla shape and loosely elsewhere.
 *
 * Leaves are set down as vanilla worldgen sets them: not checking for decay until something
 * next to them changes, so a wide crown stays whole until the tree is cut.
 */
final class Trees {

    private Trees() {}

    private static final int FLAGS = 2 | 16;

    /** What a tree of the mod is built from, read from its "config". */
    static final class Shape {
        final JsonObject json;
        final Values.State trunk, foliage, altFoliage, hanging;
        final int minHeight, maxHeight;

        Shape(JsonObject config) {
            this.json = config;
            this.trunk = Values.State.parse(config.get("trunk_provider"));
            this.foliage = Values.State.parse(config.get("foliage_provider"));
            this.altFoliage = Values.State.parse(config.get("alt_foliage_provider"));
            this.hanging = Values.State.parse(config.get("hanging_provider"));
            this.minHeight = number("min_height", 4);
            this.maxHeight = Math.max(minHeight, number("max_height", minHeight + 2));
        }

        int number(String key, int fallback) {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
        }

        float fraction(String key, float fallback) {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsFloat() : fallback;
        }
    }

    // ------------------------------------------------------------------------------------
    // Vanilla trees, by the name of the modern feature
    // ------------------------------------------------------------------------------------

    static WorldGenAbstractTree vanilla(String id) {
        String name = id.substring(id.indexOf(':') + 1);
        IBlockState jungleLog = Blocks.LOG.getDefaultState().withProperty(net.minecraft.block.BlockOldLog.VARIANT, BlockPlanks.EnumType.JUNGLE);
        IBlockState jungleLeaves = Blocks.LEAVES.getDefaultState().withProperty(net.minecraft.block.BlockOldLeaf.VARIANT, BlockPlanks.EnumType.JUNGLE)
                .withProperty(BlockLeaves.CHECK_DECAY, Boolean.FALSE);
        IBlockState oakLeaves = Blocks.LEAVES.getDefaultState().withProperty(BlockLeaves.CHECK_DECAY, Boolean.FALSE);
        if (name.startsWith("fancy_oak")) {
            return new WorldGenBigTree(false);
        }
        if (name.startsWith("super_birch")) {
            return new WorldGenBirchTree(false, true);
        }
        if (name.startsWith("birch")) {
            return new WorldGenBirchTree(false, false);
        }
        if (name.equals("mega_spruce")) {
            return new WorldGenMegaPineTree(false, true);
        }
        if (name.equals("mega_pine")) {
            return new WorldGenMegaPineTree(false, false);
        }
        if (name.equals("mega_jungle_tree")) {
            return new WorldGenMegaJungle(false, 10, 20, jungleLog, jungleLeaves);
        }
        if (name.equals("jungle_bush")) {
            return new WorldGenShrub(jungleLog, oakLeaves);
        }
        if (name.startsWith("jungle_tree")) {
            return new WorldGenTrees(false, 5, jungleLog, jungleLeaves, true);
        }
        if (name.startsWith("spruce")) {
            return new WorldGenTaiga2(false);
        }
        if (name.startsWith("pine")) {
            return new WorldGenTaiga1();
        }
        if (name.startsWith("acacia")) {
            return new WorldGenSavannaTree(false);
        }
        if (name.startsWith("dark_oak")) {
            return new WorldGenCanopyTree(false);
        }
        if (name.startsWith("swamp_oak")) {
            return new WorldGenSwamp();
        }
        if (name.startsWith("oak")) {
            return new WorldGenTrees(false);
        }
        return null; // cherry, mangrove, pale oak, azalea: see modern() below
    }

    // ------------------------------------------------------------------------------------
    // Modern Minecraft's own newer trees, built the way the modern game builds them
    // ------------------------------------------------------------------------------------

    interface Grower {
        boolean generate(World world, Random random, BlockPos pos);
    }

    /** A grower for a vanilla tree 1.12.2 has no generator for, told apart by how its trunk is made; null for any other. */
    static Grower modern(final JsonObject config) {
        if (!config.has("trunk_placer")) {
            return null;
        }
        final JsonObject trunk = config.getAsJsonObject("trunk_placer"), foliage = config.getAsJsonObject("foliage_placer");
        final String type = trunk.get("type").getAsString().replace("minecraft:", "");
        final boolean pale = type.equals("dark_oak_trunk_placer") && config.get("trunk_provider").toString().contains("pale_oak");
        if (!pale && !type.equals("cherry_trunk_placer") && !type.equals("upwards_branching_trunk_placer") && !type.equals("bending_trunk_placer")) {
            return null;
        }
        final Values.State logs = Values.State.parse(config.get("trunk_provider")), leaves = Values.State.parse(config.get("foliage_provider"));
        return new Grower() {
            @Override
            public boolean generate(World world, Random random, BlockPos pos) {
                if (logs.get(random) == null || leaves.get(random) == null) {
                    return false;
                }
                Build build = new Build(world, random, logs, leaves);
                boolean grown;
                if (pale) {
                    grown = paleOak(build, pos);
                } else if (type.equals("cherry_trunk_placer")) {
                    grown = cherry(build, pos, trunk, foliage);
                } else if (type.equals("upwards_branching_trunk_placer")) {
                    grown = mangrove(build, pos, trunk, foliage, config.has("root_placer") ? config.getAsJsonObject("root_placer") : null);
                } else {
                    grown = bending(build, pos, trunk, foliage, Values.State.parse(config.get("dirt_provider")));
                }
                if (grown && config.has("decorators")) {
                    for (JsonElement decorator : config.getAsJsonArray("decorators")) {
                        decorate(build, decorator.getAsJsonObject());
                    }
                }
                return grown;
            }
        };
    }

    /** A tree while it is being put together: where its logs and leaves went, for what is hung on them afterwards. */
    private static final class Build {
        final World world;
        final Random random;
        final Values.State logs, leaves;
        final List<BlockPos> logged = new ArrayList<BlockPos>(), leafed = new ArrayList<BlockPos>();

        Build(World world, Random random, Values.State logs, Values.State leaves) {
            this.world = world;
            this.random = random;
            this.logs = logs;
            this.leaves = leaves;
        }

        boolean inside(BlockPos pos) {
            return pos.getY() > 0 && pos.getY() < 255 && world.isBlockLoaded(pos);
        }

        /** Free for a tree to grow into: air, leaves, small plants, water. */
        boolean free(BlockPos pos) {
            if (!inside(pos)) {
                return false;
            }
            IBlockState state = world.getBlockState(pos);
            Material material = state.getMaterial();
            return state.getBlock().isAir(state, world, pos) || material == Material.LEAVES || material == Material.PLANTS || material == Material.VINE
                    || material == Material.SNOW || material == Material.WATER || material == Material.CARPET;
        }

        boolean log(BlockPos pos, EnumFacing.Axis axis) {
            if (!free(pos)) {
                return false;
            }
            IBlockState wood = logs.get(random);
            world.setBlockState(pos, axis == EnumFacing.Axis.Y ? wood : States.alongAxis(wood, axis), FLAGS);
            logged.add(pos);
            return true;
        }

        boolean leaf(BlockPos pos) {
            if (!inside(pos)) {
                return false;
            }
            IBlockState at = world.getBlockState(pos);
            if (!at.getBlock().isAir(at, world, pos) && at.getMaterial() != Material.PLANTS && at.getMaterial() != Material.VINE) {
                return false;
            }
            world.setBlockState(pos, leaves.get(random), FLAGS);
            leafed.add(pos);
            return true;
        }

        int height(JsonObject trunk) {
            return trunk.get("base_height").getAsInt() + random.nextInt(trunk.get("height_rand_a").getAsInt() + 1)
                    + random.nextInt(trunk.get("height_rand_b").getAsInt() + 1);
        }

        boolean column(BlockPos pos, int height) {
            for (int y = 0; y <= height; y++) {
                if (!free(pos.up(y))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static int between(Random random, JsonElement range, int fallbackMin, int fallbackMax) {
        JsonObject o = range != null && range.isJsonObject() ? range.getAsJsonObject() : null;
        int min = o != null && o.has("min_inclusive") ? o.get("min_inclusive").getAsInt() : fallbackMin;
        int max = o != null && o.has("max_inclusive") ? o.get("max_inclusive").getAsInt() : fallbackMax;
        return min + random.nextInt(Math.max(1, max - min + 1));
    }

    private static float chance(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    /** A cherry: a short trunk that forks into one to three boughs, each bending outward and up to a wide, drooping crown. */
    private static boolean cherry(Build b, BlockPos pos, JsonObject trunk, JsonObject foliage) {
        Random random = b.random;
        int height = b.height(trunk);
        if (!b.column(pos, height)) {
            return false;
        }
        JsonElement start = trunk.get("branch_start_offset_from_top");
        int first = Math.max(0, height - 1 + between(random, start, -4, -3));
        JsonObject o = start != null && start.isJsonObject() ? start.getAsJsonObject() : new JsonObject();
        int startMin = o.has("min_inclusive") ? o.get("min_inclusive").getAsInt() : -4, startMax = o.has("max_inclusive") ? o.get("max_inclusive").getAsInt() : -3;
        int second = Math.max(0, height - 1 + startMin + random.nextInt(Math.max(1, startMax - startMin)));
        if (second >= first) {
            second++;
        }
        int branches = Values.Int.parse(trunk.get("branch_count"), 2).get(random);
        boolean three = branches == 3, two = branches >= 2;
        int trunkHeight = three ? height : two ? Math.max(first, second) + 1 : first + 1;
        for (int i = 0; i < trunkHeight; i++) {
            b.log(pos.up(i), EnumFacing.Axis.Y);
        }
        List<BlockPos> crowns = new ArrayList<BlockPos>();
        if (three) {
            crowns.add(pos.up(trunkHeight));
        }
        EnumFacing way = EnumFacing.HORIZONTALS[random.nextInt(4)];
        crowns.add(cherryBough(b, height, pos, way, first, first < trunkHeight - 1, trunk));
        if (two) {
            crowns.add(cherryBough(b, height, pos, way.getOpposite(), second, second < trunkHeight - 1, trunk));
        }
        int radius = foliage.get("radius").getAsInt() - 1, tall = foliage.get("height").getAsInt();
        float wideHole = chance(foliage, "wide_bottom_layer_hole_chance", 0.25F), cornerHole = chance(foliage, "corner_hole_chance", 0.25F);
        float hang = chance(foliage, "hanging_leaves_chance", 0.1667F), hangMore = chance(foliage, "hanging_leaves_extension_chance", 0.3333F);
        for (BlockPos crown : crowns) {
            cherryRow(b, crown, radius - 2, tall - 3, wideHole, cornerHole);
            cherryRow(b, crown, radius - 1, tall - 4, wideHole, cornerHole);
            for (int y = tall - 5; y >= 0; y--) {
                cherryRow(b, crown, radius, y, wideHole, cornerHole);
            }
            cherryRow(b, crown, radius, -1, wideHole, cornerHole);
            cherryHang(b, crown, radius, -1, hang, hangMore);
            cherryRow(b, crown, radius - 1, -2, wideHole, cornerHole);
            cherryHang(b, crown, radius - 1, -2, hang, hangMore);
        }
        return true;
    }

    private static BlockPos cherryBough(Build b, int height, BlockPos origin, EnumFacing way, int offset, boolean middleGoesOn, JsonObject trunk) {
        Random random = b.random;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(origin.up(offset));
        int endY = height - 1 + between(random, trunk.get("branch_end_offset_from_top"), -1, 0);
        boolean longer = middleGoesOn || endY < offset;
        int length = between(random, trunk.get("branch_horizontal_length"), 2, 4) + (longer ? 1 : 0);
        BlockPos end = origin.offset(way, length).up(endY);
        for (int i = longer ? 2 : 1; i > 0; i--) {
            b.log(at.move(way).toImmutable(), way.getAxis());
        }
        EnumFacing vertical = end.getY() > at.getY() ? EnumFacing.UP : EnumFacing.DOWN;
        for (int guard = 0; guard < 32; guard++) {
            int distance = Math.abs(end.getX() - at.getX()) + Math.abs(end.getY() - at.getY()) + Math.abs(end.getZ() - at.getZ());
            if (distance == 0) {
                break;
            }
            boolean up = random.nextFloat() < Math.abs(end.getY() - at.getY()) / (float) distance;
            at.move(up ? vertical : way);
            b.log(at.toImmutable(), up ? EnumFacing.Axis.Y : way.getAxis());
        }
        return end.up();
    }

    private static void cherryRow(Build b, BlockPos center, int radius, int y, float wideHole, float cornerHole) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int ax = Math.abs(dx), az = Math.abs(dz);
                boolean skip;
                if (y == -1 && (ax == radius || az == radius) && b.random.nextFloat() < wideHole) {
                    skip = true;
                } else {
                    boolean corner = ax == radius && az == radius;
                    skip = radius > 2 ? corner || (ax + az > radius * 2 - 2 && b.random.nextFloat() < cornerHole) : corner && b.random.nextFloat() < cornerHole;
                }
                if (!skip) {
                    b.leaf(center.add(dx, y, dz));
                }
            }
        }
    }

    /** Leaves hanging one or two blocks below the rim of a crown's layer. */
    private static void cherryHang(Build b, BlockPos center, int radius, int y, float hang, float hangMore) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                    continue;
                }
                BlockPos rim = center.add(dx, y, dz);
                if (b.world.getBlockState(rim).getMaterial() == Material.LEAVES && b.random.nextFloat() < hang && b.leaf(rim.down())
                        && b.random.nextFloat() < hangMore) {
                    b.leaf(rim.down(2));
                }
            }
        }
    }

    /** Leaves thrown at random around a point: the loose crowns of mangroves and azaleas. */
    private static void spread(Build b, BlockPos center, JsonObject foliage) {
        int radius = foliage.get("radius").getAsInt(), tall = foliage.get("foliage_height").getAsInt();
        for (int i = foliage.get("leaf_placement_attempts").getAsInt(); i > 0; i--) {
            b.leaf(center.add(b.random.nextInt(radius) - b.random.nextInt(radius), b.random.nextInt(tall) - b.random.nextInt(tall),
                    b.random.nextInt(radius) - b.random.nextInt(radius)));
        }
    }

    /**
     * A mangrove: a trunk raised on arching roots that step outward and down to the ground,
     * with side branches that climb as they go and loose crowns at every tip.
     */
    private static boolean mangrove(Build b, BlockPos pos, JsonObject trunk, JsonObject foliage, JsonObject roots) {
        Random random = b.random;
        int height = b.height(trunk);
        BlockPos base = roots == null ? pos : pos.up(Values.Int.parse(roots.get("trunk_offset_y"), 2).get(random));
        if (base.getY() + height + 4 > 255 || !b.column(base, height)) {
            return false;
        }
        if (roots != null && !mangroveRoots(b, pos, base, roots)) {
            return false;
        }
        float branchChance = chance(trunk, "place_branch_per_log_probability", 0.5F);
        List<BlockPos> crowns = new ArrayList<BlockPos>();
        for (int i = 0; i < height; i++) {
            int y = base.getY() + i;
            if (b.log(new BlockPos(base.getX(), y, base.getZ()), EnumFacing.Axis.Y) && i < height - 1 && random.nextFloat() < branchChance) {
                EnumFacing way = EnumFacing.HORIZONTALS[random.nextInt(4)];
                int length = between(random, trunk.get("extra_branch_length"), 0, 1);
                int from = Math.max(0, length - between(random, trunk.get("extra_branch_length"), 0, 1) - 1);
                int steps = between(random, trunk.get("extra_branch_steps"), 1, 4);
                int x = base.getX(), z = base.getZ(), top = y + from;
                for (int l = from; l < height && steps > 0; l++, steps--) {
                    if (l >= 1) {
                        int at = y + l;
                        x += way.getXOffset();
                        z += way.getZOffset();
                        top = at;
                        if (b.log(new BlockPos(x, at, z), EnumFacing.Axis.Y)) {
                            top = at + 1;
                        }
                        crowns.add(new BlockPos(x, at, z));
                    }
                }
                if (top - y > 1) {
                    crowns.add(new BlockPos(x, top, z));
                    crowns.add(new BlockPos(x, top - 2, z));
                }
            }
            if (i == height - 1) {
                crowns.add(new BlockPos(base.getX(), y + 1, base.getZ()));
            }
        }
        for (BlockPos crown : crowns) {
            spread(b, crown, foliage);
        }
        return true;
    }

    private static boolean rootable(Build b, BlockPos pos) {
        if (b.free(pos)) {
            return true;
        }
        if (!b.inside(pos)) {
            return false;
        }
        String name = String.valueOf(b.world.getBlockState(pos).getBlock().getRegistryName());
        return name.endsWith("mc_mud") || name.endsWith("mangrove_roots") || name.endsWith("mc_moss_carpet");
    }

    private static boolean mangroveRoots(Build b, BlockPos origin, BlockPos base, JsonObject roots) {
        JsonObject placement = roots.getAsJsonObject("mangrove_root_placement");
        int maxLength = placement.get("max_root_length").getAsInt(), maxWidth = placement.get("max_root_width").getAsInt();
        float skew = placement.get("random_skew_chance").getAsFloat();
        for (int y = origin.getY(); y < base.getY(); y++) {
            if (!rootable(b, new BlockPos(origin.getX(), y, origin.getZ()))) {
                return false;
            }
        }
        List<BlockPos> all = new ArrayList<BlockPos>();
        all.add(base.down());
        for (EnumFacing way : EnumFacing.HORIZONTALS) {
            List<BlockPos> one = new ArrayList<BlockPos>();
            if (!rootsFrom(b, base.offset(way), way, base, one, 0, maxLength, maxWidth, skew)) {
                return false;
            }
            all.addAll(one);
            all.add(base.offset(way));
        }
        IBlockState root = Values.State.parse(roots.get("root_provider")).get(b.random);
        IBlockState muddy = Values.State.parse(placement.get("muddy_roots_provider")).get(b.random);
        JsonObject above = roots.has("above_root_placement") ? roots.getAsJsonObject("above_root_placement") : null;
        IBlockState carpet = above == null ? null : Values.State.parse(above.get("above_root_provider")).get(b.random);
        float carpetChance = above == null ? 0.0F : above.get("above_root_placement_chance").getAsFloat();
        if (root == null) {
            root = b.logs.get(b.random); // no root block in this game: the roots are made of the wood itself
        }
        for (BlockPos at : all) {
            if (!rootable(b, at)) {
                continue;
            }
            boolean inMud = String.valueOf(b.world.getBlockState(at).getBlock().getRegistryName()).endsWith("mc_mud");
            b.world.setBlockState(at, inMud && muddy != null ? muddy : root, FLAGS);
            if (carpet != null && b.random.nextFloat() < carpetChance && b.inside(at.up()) && b.world.isAirBlock(at.up())) {
                b.world.setBlockState(at.up(), carpet, FLAGS);
            }
        }
        return true;
    }

    /** Follows one root outward and down until it reaches the ground; false if it would be too long. */
    private static boolean rootsFrom(Build b, BlockPos pos, EnumFacing way, BlockPos base, List<BlockPos> out, int length, int maxLength, int maxWidth,
            float skew) {
        if (length == maxLength || out.size() > maxLength) {
            return false;
        }
        BlockPos below = pos.down(), side = pos.offset(way);
        int distance = Math.abs(pos.getX() - base.getX()) + Math.abs(pos.getY() - base.getY()) + Math.abs(pos.getZ() - base.getZ());
        BlockPos[] next;
        if (distance > maxWidth - 3 && distance <= maxWidth) {
            next = b.random.nextFloat() < skew ? new BlockPos[] {below, side.down()} : new BlockPos[] {below};
        } else if (distance > maxWidth || b.random.nextFloat() < skew) {
            next = new BlockPos[] {below};
        } else {
            next = new BlockPos[] {b.random.nextBoolean() ? side : below};
        }
        for (BlockPos each : next) {
            if (rootable(b, each)) {
                out.add(each);
                if (!rootsFrom(b, each, way, base, out, length + 1, maxLength, maxWidth, skew)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** An azalea: a trunk that leans over at the top, with a loose crown along the bend. */
    private static boolean bending(Build b, BlockPos pos, JsonObject trunk, JsonObject foliage, Values.State dirt) {
        Random random = b.random;
        int height = b.height(trunk) - 1;
        if (!b.column(pos, height + 1)) {
            return false;
        }
        IBlockState soil = dirt.get(random);
        if (soil != null && b.inside(pos.down()) && b.world.getBlockState(pos.down()).getMaterial() != Material.AIR) {
            b.world.setBlockState(pos.down(), soil, FLAGS);
        }
        EnumFacing way = EnumFacing.HORIZONTALS[random.nextInt(4)];
        int leavesFrom = trunk.get("min_height_for_leaves").getAsInt();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(pos);
        List<BlockPos> crowns = new ArrayList<BlockPos>();
        for (int j = 0; j <= height; j++) {
            if (j + 1 >= height + random.nextInt(2)) {
                at.move(way);
            }
            b.log(at.toImmutable(), EnumFacing.Axis.Y);
            if (j >= leavesFrom) {
                crowns.add(at.toImmutable());
            }
            at.move(EnumFacing.UP);
        }
        for (int l = between(random, trunk.get("bend_length"), 1, 2); l >= 0; l--) {
            b.log(at.toImmutable(), EnumFacing.Axis.Y);
            crowns.add(at.toImmutable());
            at.move(way);
        }
        for (BlockPos crown : crowns) {
            spread(b, crown, foliage);
        }
        return true;
    }

    /** A pale oak has the dark oak's shape, which 1.12.2 can already build: its generator is run with the wood swapped. */
    private static boolean paleOak(final Build b, BlockPos pos) {
        return Features.onSoil(b.world, pos, new WorldGenCanopyTree(false) {
            @Override
            protected void setBlockAndNotifyAdequately(World world, BlockPos at, IBlockState state) {
                if (!b.inside(at)) {
                    return;
                }
                if (state.getMaterial() == Material.LEAVES) {
                    world.setBlockState(at, b.leaves.get(b.random), FLAGS);
                    b.leafed.add(at);
                } else if (state.getMaterial() == Material.WOOD) {
                    world.setBlockState(at, b.logs.get(b.random), FLAGS);
                    b.logged.add(at);
                } else {
                    world.setBlockState(at, state, FLAGS);
                }
            }
        }, b.random);
    }

    /** What the modern game hangs on a finished tree: vines on mangroves, pale moss on pale oaks. Bees and seed pods are left off. */
    private static void decorate(Build b, JsonObject decorator) {
        String type = decorator.get("type").getAsString().replace("minecraft:", "");
        Random random = b.random;
        if (type.equals("leave_vine")) {
            float chance = decorator.get("probability").getAsFloat();
            for (BlockPos leaf : b.leafed) {
                for (EnumFacing side : EnumFacing.HORIZONTALS) {
                    BlockPos at = leaf.offset(side);
                    if (random.nextFloat() < chance && b.inside(at) && b.world.isAirBlock(at)) {
                        // A vine clings to the block behind it, so it carries the opposite side's name.
                        IBlockState vine = Blocks.VINE.getDefaultState().withProperty(net.minecraft.block.BlockVine.getPropertyFor(side.getOpposite()), Boolean.TRUE);
                        for (int i = 0; i < 5 && b.inside(at) && b.world.isAirBlock(at); i++) {
                            b.world.setBlockState(at, vine, FLAGS);
                            at = at.down();
                        }
                    }
                }
            }
        } else if (type.equals("pale_moss")) {
            IBlockState moss = States.of("minecraft:pale_hanging_moss", null);
            if (moss == null) {
                return;
            }
            float onLogs = chance(decorator, "trunk_probability", 0.4F), onLeaves = chance(decorator, "leaves_probability", 0.15F);
            List<BlockPos> under = new ArrayList<BlockPos>();
            for (BlockPos log : b.logged) {
                if (random.nextFloat() < onLogs) {
                    under.add(log.down());
                }
            }
            for (BlockPos leaf : b.leafed) {
                if (random.nextFloat() < onLeaves) {
                    under.add(leaf.down());
                }
            }
            for (BlockPos at : under) {
                while (b.inside(at) && b.world.isAirBlock(at)) {
                    b.world.setBlockState(at, moss, FLAGS);
                    if (random.nextFloat() < 0.5F) {
                        break;
                    }
                    at = at.down();
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Building blocks
    // ------------------------------------------------------------------------------------

    private static boolean open(World world, BlockPos pos) {
        if (pos.getY() < 1 || pos.getY() > 254) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        Material material = state.getMaterial();
        return state.getBlock().isAir(state, world, pos) || material == Material.LEAVES || material == Material.PLANTS || material == Material.VINE
                || material == Material.SNOW;
    }

    private static void log(World world, BlockPos pos, IBlockState log) {
        if (log != null && open(world, pos)) {
            world.setBlockState(pos, log, FLAGS);
        }
    }

    private static void leaf(World world, Random random, BlockPos pos, Shape shape) {
        if (pos.getY() < 1 || pos.getY() > 254) {
            return;
        }
        IBlockState at = world.getBlockState(pos);
        if (!at.getBlock().isAir(at, world, pos) && at.getMaterial() != Material.PLANTS && at.getMaterial() != Material.VINE) {
            return;
        }
        IBlockState leaves = null;
        if (!shape.altFoliage.isNothing() && random.nextInt(4) == 0) {
            leaves = shape.altFoliage.get(random);
        }
        if (leaves == null || leaves.getBlock() == Blocks.AIR) {
            leaves = shape.foliage.get(random);
        }
        if (leaves != null && leaves.getBlock() != Blocks.AIR) {
            world.setBlockState(pos, leaves, FLAGS);
        }
    }

    /** A flat layer of leaves: a square with its corners sometimes cut, or a rounder patch. */
    private static void layer(World world, Random random, BlockPos center, int radius, Shape shape, boolean cutCorners) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 0;
                if (corner && (cutCorners || random.nextInt(2) == 0)) {
                    continue;
                }
                leaf(world, random, center.add(dx, 0, dz), shape);
            }
        }
    }

    private static int height(Random random, Shape shape) {
        return shape.minHeight + random.nextInt(shape.maxHeight - shape.minHeight + 1);
    }

    /** True if a trunk this tall and wide has room here. */
    private static boolean room(World world, BlockPos pos, int height, int width) {
        if (pos.getY() < 1 || pos.getY() + height + 4 > 255) {
            return false;
        }
        for (int y = 0; y <= height; y++) {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < width; dz++) {
                    if (!open(world, pos.add(dx, y, dz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void trunk(World world, Random random, BlockPos pos, int height, int width, Shape shape) {
        for (int y = 0; y < height; y++) {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < width; dz++) {
                    log(world, pos.add(dx, y, dz), shape.trunk.get(random));
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // The mod's tree kinds
    // ------------------------------------------------------------------------------------

    static boolean grow(String type, World world, Random random, BlockPos pos, Shape shape) {
        if (shape.trunk.get(random) == null || shape.foliage.get(random) == null) {
            return false; // a wood this game has not been given
        }
        switch (type) {
            case "basic_tree":
            case "bush_tree":
                return basic(world, random, pos, shape);
            case "big_tree":
                return big(world, random, pos, shape);
            case "twiglet_tree":
                return twiglet(world, random, pos, shape);
            case "magic_tree":
                return column(world, random, pos, shape);
            case "taiga_tree":
            case "umbran_tree":
            case "empyreal_tree":
                return conifer(world, random, pos, shape, 1, 0.12F);
            case "redwood_tree":
                return conifer(world, random, pos, shape, Math.max(1, shape.number("trunk_width", 1)), 0.4F);
            case "cypress_tree":
                return column(world, random, pos, shape);
            case "pine_tree":
                return pine(world, random, pos, shape);
            case "palm_tree":
                return palm(world, random, pos, shape);
            case "mahogany_tree":
                return canopy(world, random, pos, shape, false);
            case "bayou_tree":
                return canopy(world, random, pos, shape, true);
            default:
                return basic(world, random, pos, shape);
        }
    }

    /** The oak shape, with the mod's numbers for how many leaf layers and how wide. */
    private static boolean basic(World world, Random random, BlockPos pos, Shape shape) {
        int height = height(random, shape);
        if (!room(world, pos, height, 1)) {
            return false;
        }
        int layers = shape.number("leaf_layers", 4), layerHeight = Math.max(1, shape.number("leaves_layer_height", 2));
        int maxRadius = Math.min(3, shape.number("max_leaves_radius", 2));
        int top = height - 1 + shape.number("leaves_offset", 1);
        for (int y = top - layers + 1; y <= top; y++) {
            int fromTop = y - top;
            int radius = Math.min(maxRadius, 1 - fromTop / layerHeight);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius || Math.abs(dz) != radius || (random.nextInt(2) != 0 && fromTop != 0)) {
                        leaf(world, random, pos.add(dx, y, dz), shape);
                    }
                }
            }
        }
        trunk(world, random, pos, height, 1, shape);
        return true;
    }

    /** A broad tree: a trunk with boughs reaching out to several clumps of leaves. */
    private static boolean big(World world, Random random, BlockPos pos, Shape shape) {
        int height = Math.max(6, height(random, shape));
        if (!room(world, pos, height, 1)) {
            return false;
        }
        int clumpHeight = Math.max(3, Math.min(5, shape.number("foliage_height", 4)));
        int boughs = 3 + random.nextInt(3) + height / 8;
        for (int i = 0; i < boughs; i++) {
            int y = height / 2 + random.nextInt(Math.max(1, height / 2 - 1));
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int reach = 2 + random.nextInt(2 + height / 10);
            int ex = (int) Math.round(Math.cos(angle) * reach), ez = (int) Math.round(Math.sin(angle) * reach);
            // The bough climbs from the trunk to its clump.
            int steps = Math.max(Math.abs(ex), Math.abs(ez));
            for (int s = 1; s <= steps; s++) {
                BlockPos at = pos.add(Math.round(ex * s / (float) steps), y - (steps - s) / 2, Math.round(ez * s / (float) steps));
                IBlockState wood = shape.trunk.get(random);
                log(world, at, wood == null ? null : States.alongAxis(wood, Math.abs(ex) >= Math.abs(ez) ? EnumFacing.Axis.X : EnumFacing.Axis.Z));
            }
            clump(world, random, pos.add(ex, y, ez), clumpHeight, shape);
        }
        trunk(world, random, pos, height, 1, shape);
        clump(world, random, pos.up(height - 1), clumpHeight, shape);
        return true;
    }

    /** A rounded clump of leaves around a log: narrow, wide, wide, narrow. */
    private static void clump(World world, Random random, BlockPos center, int height, Shape shape) {
        for (int dy = 0; dy < height; dy++) {
            int radius = dy == 0 || dy == height - 1 ? 1 : 2;
            layer(world, random, center.up(dy - 1), radius, shape, radius == 2);
        }
    }

    /** A sapling-sized tree: a thin stem with a leaf here and there. */
    private static boolean twiglet(World world, Random random, BlockPos pos, Shape shape) {
        int height = height(random, shape);
        if (!room(world, pos, height, 1)) {
            return false;
        }
        float even = shape.fraction("leaf_chance_even", 0.2F), odd = shape.fraction("leaf_chance_odd", 0.9F);
        int bare = height / 3;
        for (int y = bare; y < height; y++) {
            float chance = (y - bare) % 2 == 0 ? even : odd;
            for (EnumFacing side : EnumFacing.HORIZONTALS) {
                if (random.nextFloat() < chance) {
                    leaf(world, random, pos.up(y).offset(side), shape);
                }
            }
        }
        trunk(world, random, pos, height, 1, shape);
        leaf(world, random, pos.up(height), shape);
        return true;
    }

    /**
     * A conifer: tiers of leaves that widen downward and start over, as on a spruce. Wide
     * trunks (redwoods) get a taller bare stem and wider tiers.
     */
    private static boolean conifer(World world, Random random, BlockPos pos, Shape shape, int width, float bareShare) {
        int height = height(random, shape);
        if (!room(world, pos, height, width)) {
            return false;
        }
        int bare = Math.max(1 + random.nextInt(2), (int) (height * bareShare));
        int foliage = height - bare;
        int maxRadius = Math.min(3, 2 + height / 14) + (width - 1);
        int half = (width - 1) / 2;
        BlockPos axis = pos.add(half, 0, half);
        int radius = random.nextInt(2), limit = 1, restart = 0;
        for (int down = 0; down <= foliage; down++) {
            int y = height - down;
            int reach = radius + (width > 1 ? half : 0); // measured from the bark, so a wide trunk's top is covered too
            layer(world, random, axis.up(y), Math.min(reach, 4 + half), shape, true);
            if (width > 2 && radius > 0) {
                // A wide trunk: the tier is measured from the bark, so its corners need filling in.
                layer(world, random, axis.up(y), Math.max(0, Math.min(reach, 4 + half) - 1), shape, false);
            }
            if (radius >= limit) {
                radius = restart;
                restart = 1;
                if (++limit > maxRadius) {
                    limit = maxRadius;
                }
            } else {
                radius++;
            }
        }
        trunk(world, random, pos, height - (width > 1 ? 0 : random.nextInt(2)), width, shape);
        leaf(world, random, axis.up(height + 1), shape);
        return true;
    }

    /** A narrow column of leaves around a tall stem, tapering at the top. */
    private static boolean column(World world, Random random, BlockPos pos, Shape shape) {
        int height = height(random, shape);
        if (!room(world, pos, height, 1)) {
            return false;
        }
        int bare = shape.json.has("leaves_at_bottom") && shape.json.get("leaves_at_bottom").getAsBoolean() ? 1 : 2 + random.nextInt(3);
        for (int y = bare; y <= height + 1; y++) {
            int fromTop = height + 1 - y;
            int radius = fromTop < 2 ? 0 : fromTop < 4 || y == bare ? 1 : (y % 3 == 0 ? 2 : 1);
            layer(world, random, pos.up(y), radius, shape, true);
        }
        trunk(world, random, pos, height, 1, shape);
        return true;
    }

    /** A bare, tall stem with a small crown and a few tufts below it. */
    private static boolean pine(World world, Random random, BlockPos pos, Shape shape) {
        int height = height(random, shape);
        if (!room(world, pos, height, 1)) {
            return false;
        }
        for (int dy = -2; dy <= 1; dy++) {
            layer(world, random, pos.up(height - 1 + dy), dy == 1 ? 1 : dy == -2 ? 1 : 2, shape, true);
        }
        int tufts = 1 + random.nextInt(3);
        for (int i = 0; i < tufts; i++) {
            int y = height / 2 + random.nextInt(Math.max(1, height / 2 - 3));
            EnumFacing side = EnumFacing.HORIZONTALS[random.nextInt(4)];
            IBlockState wood = shape.trunk.get(random);
            log(world, pos.up(y).offset(side), wood == null ? null : States.alongAxis(wood, side.getAxis()));
            layer(world, random, pos.up(y).offset(side, 2), 1, shape, true);
            leaf(world, random, pos.up(y + 1).offset(side), shape);
        }
        trunk(world, random, pos, height, 1, shape);
        return true;
    }

    /** A palm: a stem that leans a little and four fronds that reach out and droop. */
    private static boolean palm(World world, Random random, BlockPos pos, Shape shape) {
        int height = height(random, shape);
        if (!room(world, pos, height, 1)) {
            return false;
        }
        EnumFacing lean = EnumFacing.HORIZONTALS[random.nextInt(4)];
        BlockPos at = pos;
        for (int y = 0; y < height; y++) {
            if (y > 0 && y % Math.max(3, height / 3) == 0 && open(world, at.offset(lean))) {
                at = at.offset(lean);
            }
            log(world, at, shape.trunk.get(random));
            at = at.up();
        }
        BlockPos crown = at.down();
        leaf(world, random, crown.up(), shape);
        for (EnumFacing side : EnumFacing.HORIZONTALS) {
            for (int out = 1; out <= 3; out++) {
                leaf(world, random, crown.offset(side, out).up(out == 3 ? 0 : 1), shape);
            }
            leaf(world, random, crown.offset(side, 3).down(), shape);
            leaf(world, random, crown.offset(side).offset(side.rotateY()).up(), shape);
        }
        return true;
    }

    /** A jungle giant or a swamp willow: a stem that forks near the top into wide, flat crowns; the willow's edges hang down. */
    private static boolean canopy(World world, Random random, BlockPos pos, Shape shape, boolean drooping) {
        int height = height(random, shape);
        if (!room(world, pos, height, 1)) {
            return false;
        }
        trunk(world, random, pos, height, 1, shape);
        if (drooping) {
            // Roots: a log at the foot on a couple of sides.
            for (EnumFacing side : EnumFacing.HORIZONTALS) {
                if (random.nextInt(2) == 0) {
                    log(world, pos.offset(side), shape.trunk.get(random));
                }
            }
        }
        int forks = drooping ? 1 : 2 + random.nextInt(2);
        BlockPos[] tops = new BlockPos[forks + 1];
        tops[0] = pos.up(height - 1);
        for (int i = 1; i <= forks; i++) {
            EnumFacing side = EnumFacing.HORIZONTALS[random.nextInt(4)];
            BlockPos at = pos.up(height - 3 - random.nextInt(2));
            for (int s = 0; s < 3; s++) {
                at = at.offset(side).up(s == 0 ? 0 : 1);
                log(world, at, shape.trunk.get(random));
            }
            tops[i] = at;
        }
        for (BlockPos top : tops) {
            layer(world, random, top, 3, shape, true);
            layer(world, random, top.up(), 2, shape, true);
            if (drooping) {
                layer(world, random, top.down(), 3, shape, true);
                for (int i = 0; i < 10; i++) {
                    int dx = random.nextInt(7) - 3, dz = random.nextInt(7) - 3;
                    if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
                        for (int d = 2; d <= 2 + random.nextInt(2); d++) {
                            leaf(world, random, top.add(dx, -d, dz), shape);
                        }
                    }
                }
            }
        }
        return true;
    }
}
