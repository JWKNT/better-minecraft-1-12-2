package better112.world.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import better112.Better112;
import better112.content.ModernContent;
import better112.world.density.WorldData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockLog;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.WorldGenAbstractTree;

/**
 * Reads the modern game's "features": the data files that say what a biome is decorated
 * with (trees, flowers, patches of grass) and where each goes. A placed feature is a list
 * of placement steps ("so many per chunk", "at the surface", "only in this biome") followed
 * by the thing to put down.
 *
 * Not every kind of feature is understood yet. One this code does not know is skipped and
 * named once in the log, so a biome simply comes out a little barer than the original.
 */
public final class Features {

    private static final int FLAGS = 2 | 16;
    /** The slow noise the old game also uses to vary grass colour: "more of this here, less there". */
    private static final net.minecraft.world.gen.NoiseGeneratorPerlin PATCH_NOISE = new net.minecraft.world.gen.NoiseGeneratorPerlin(new Random(2345L), 1);

    private final WorldData data;
    private final Map<String, Placed> placed = new HashMap<String, Placed>();
    private final Map<String, Thing> things = new HashMap<String, Thing>();
    private final Set<String> unknown = new TreeSet<String>();

    public Features(WorldData data) {
        this.data = data;
    }

    /** The names of the features a modern biome lists for one decoration step (9 is vegetation); empty if the biome is unknown. */
    public List<String> biomeFeatures(String biomeId, int step) {
        List<String> ids = new ArrayList<String>();
        try {
            JsonArray steps = data.read("biome", WorldData.canonical(biomeId)).getAsJsonObject().getAsJsonArray("features");
            if (steps != null && step < steps.size()) {
                for (JsonElement id : steps.get(step).getAsJsonArray()) {
                    if (id.isJsonPrimitive()) {
                        ids.add(id.getAsString());
                    }
                }
            }
        } catch (RuntimeException e) {
            // a biome this game version does not have
        }
        return ids;
    }

    /** Kinds of feature and placement step met so far that this code cannot do. */
    public Set<String> unknown() {
        return unknown;
    }

    // ------------------------------------------------------------------------------------
    // Placed features
    // ------------------------------------------------------------------------------------

    /** Something to put down, with the steps that decide where. */
    public final class Placed {
        final String id;
        private final List<Step> steps = new ArrayList<Step>();
        private final Thing thing;
        /**
         * Underground features pick a height, search for a cave wall and only then ask "is this
         * my biome". Asking once already after the height is picked spares the search wherever
         * the answer is no, which is most places.
         */
        private final int checkAfter;

        Placed(String id, JsonObject json) {
            this.id = id;
            int heightAt = -1;
            boolean biomeLater = false;
            if (json.has("placement")) {
                for (JsonElement step : json.getAsJsonArray("placement")) {
                    Step parsed = step(step.getAsJsonObject());
                    if (parsed != null) {
                        String type = step.getAsJsonObject().get("type").getAsString();
                        if (type.endsWith("height_range") && heightAt < 0) {
                            heightAt = steps.size();
                        } else if (type.endsWith(":biome") || type.equals("biome")) {
                            biomeLater |= heightAt >= 0;
                        }
                        steps.add(parsed);
                    }
                }
            }
            this.checkAfter = biomeLater ? heightAt : -1;
            this.thing = thing(json.get("feature"));
        }

        /** True if this can put anything down at all (its kind of feature is one this code knows). */
        public boolean usable() {
            return thing != null;
        }

        /** "where" answers the feature's "only in this biome" step; null lets it through anywhere. */
        public boolean place(World world, Random random, BlockPos origin, Where where) {
            if (thing == null) {
                return false;
            }
            List<BlockPos> positions = Collections.singletonList(origin);
            for (Step step : steps) {
                List<BlockPos> next = new ArrayList<BlockPos>();
                for (BlockPos pos : positions) {
                    step.apply(world, random, pos, where, next);
                }
                if (where != null && checkAfter >= 0 && step == steps.get(checkAfter)) {
                    for (java.util.Iterator<BlockPos> it = next.iterator(); it.hasNext();) {
                        if (!where.here(world, it.next())) {
                            it.remove();
                        }
                    }
                }
                if (next.isEmpty()) {
                    return false;
                }
                positions = next;
            }
            boolean any = false;
            for (BlockPos pos : positions) {
                // Never reach into a chunk that is not there yet: that would make the game generate it on the spot.
                if (pos.getY() > 0 && pos.getY() < 255 && world.isBlockLoaded(pos)) {
                    any |= thing.generate(world, random, pos, null);
                }
            }
            return any;
        }
    }

    /** Says whether a spot belongs to the place being decorated: a biome on the surface, a cave biome underground. */
    public interface Where {
        boolean here(World world, BlockPos pos);
    }

    /** Puts down one configured feature by name at a spot, with no placement steps: a sapling growing into its tree. */
    public boolean generate(String configuredId, World world, Random random, BlockPos pos) {
        Thing thing = thing(new com.google.gson.JsonPrimitive(configuredId));
        return thing != null && thing.generate(world, random, pos, null);
    }

    /** True if a configured feature of this name exists and is one this code can build. */
    public boolean has(String configuredId) {
        return thing(new com.google.gson.JsonPrimitive(configuredId)) != null;
    }

    /** A placed feature by name ("minecraft:patch_grass_forest") or written out in place. */
    public Placed placed(JsonElement reference) {
        if (reference == null) {
            return null;
        }
        if (reference.isJsonObject()) {
            return new Placed("(inline)", reference.getAsJsonObject());
        }
        String id = WorldData.canonical(reference.getAsString());
        if (!placed.containsKey(id)) {
            Placed loaded = null;
            try {
                loaded = new Placed(id, data.read("placed_feature", id).getAsJsonObject());
            } catch (RuntimeException e) {
                Better112.LOG.debug("Feature {} cannot be read: {}", id, e.toString());
            }
            placed.put(id, loaded);
        }
        return placed.get(id);
    }

    // ------------------------------------------------------------------------------------
    // Placement steps
    // ------------------------------------------------------------------------------------

    private interface Step {
        void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out);
    }

    private Step step(JsonObject json) {
        String type = json.get("type").getAsString().replace("minecraft:", "");
        switch (type) {
            case "count":
            case "count_on_every_layer": {
                final Values.Int count = Values.Int.parse(json.get("count"), 1);
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        for (int i = count.get(random); i > 0; i--) {
                            out.add(pos);
                        }
                    }
                };
            }
            case "rarity_filter": {
                final int chance = Math.max(1, json.get("chance").getAsInt());
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        if (random.nextInt(chance) == 0) {
                            out.add(pos);
                        }
                    }
                };
            }
            case "noise_threshold_count": {
                final double level = json.get("noise_level").getAsDouble();
                final int below = json.get("below_noise").getAsInt(), above = json.get("above_noise").getAsInt();
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        double noise = PATCH_NOISE.getValue(pos.getX() / 200.0D, pos.getZ() / 200.0D);
                        for (int i = noise < level ? below : above; i > 0; i--) {
                            out.add(pos);
                        }
                    }
                };
            }
            case "noise_based_count": {
                final double ratio = json.get("noise_to_count_ratio").getAsDouble(), factor = json.get("noise_factor").getAsDouble();
                final double offset = json.has("noise_offset") ? json.get("noise_offset").getAsDouble() : 0.0D;
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        double noise = PATCH_NOISE.getValue(pos.getX() / factor, pos.getZ() / factor);
                        for (int i = (int) Math.ceil((noise + offset) * ratio); i > 0; i--) {
                            out.add(pos);
                        }
                    }
                };
            }
            case "in_square":
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        out.add(pos.add(random.nextInt(16), 0, random.nextInt(16)));
                    }
                };
            case "biome":
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        if (where == null || where.here(world, pos)) {
                            out.add(pos);
                        }
                    }
                };
            case "heightmap": {
                final int floor = heightKind(json.get("heightmap").getAsString());
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        if (world.isBlockLoaded(pos)) {
                            int y = surface(world, pos, floor);
                            if (y > 0) {
                                out.add(new BlockPos(pos.getX(), y, pos.getZ()));
                            }
                        }
                    }
                };
            }
            case "surface_water_depth_filter": {
                final int max = json.get("max_water_depth").getAsInt();
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        if (!world.isBlockLoaded(pos)) {
                            return;
                        }
                        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(pos.getX(), surface(world, pos, OCEAN_FLOOR), pos.getZ());
                        int depth = 0;
                        while (depth <= max && world.getBlockState(at).getMaterial() == Material.WATER) {
                            depth++;
                            at.move(EnumFacing.UP);
                        }
                        if (depth <= max) {
                            out.add(pos);
                        }
                    }
                };
            }
            case "surface_relative_threshold_filter": {
                final int floor = heightKind(json.get("heightmap").getAsString());
                final int min = json.has("min_inclusive") ? json.get("min_inclusive").getAsInt() : Integer.MIN_VALUE / 2;
                final int max = json.has("max_inclusive") ? json.get("max_inclusive").getAsInt() : Integer.MAX_VALUE / 2;
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        if (world.isBlockLoaded(pos)) {
                            int y = surface(world, pos, floor);
                            if (pos.getY() >= y + min && pos.getY() <= y + max) {
                                out.add(pos);
                            }
                        }
                    }
                };
            }
            case "height_range": {
                JsonObject height = json.getAsJsonObject("height");
                final int min = Values.anchor(height.get("min_inclusive")), max = Values.anchor(height.get("max_inclusive"));
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        int low = Math.max(1, Math.min(min, max)), high = Math.min(254, Math.max(min, max));
                        if (high >= low) {
                            out.add(new BlockPos(pos.getX(), low + random.nextInt(high - low + 1), pos.getZ()));
                        }
                    }
                };
            }
            case "random_offset": {
                final Values.Int xz = Values.Int.parse(json.get("xz_spread"), 0), y = Values.Int.parse(json.get("y_spread"), 0);
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        out.add(pos.add(xz.get(random), y.get(random), xz.get(random)));
                    }
                };
            }
            case "environment_scan": {
                final EnumFacing direction = "up".equals(json.get("direction_of_search").getAsString()) ? EnumFacing.UP : EnumFacing.DOWN;
                final Test target = test(json.get("target_condition"));
                final Test allowed = json.has("allowed_search_condition") ? test(json.get("allowed_search_condition")) : null;
                final int max = json.get("max_steps").getAsInt();
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        BlockPos at = pos;
                        for (int i = 0; i <= max && at.getY() > 0 && at.getY() < 255; i++) {
                            if (target.test(world, at)) {
                                out.add(at);
                                return;
                            }
                            if (allowed != null && !allowed.test(world, at)) {
                                return;
                            }
                            at = at.offset(direction);
                        }
                    }
                };
            }
            case "block_predicate_filter": {
                final Test test = test(json.get("predicate"));
                return new Step() {
                    @Override
                    public void apply(World world, Random random, BlockPos pos, Where where, List<BlockPos> out) {
                        if (pos.getY() > 0 && pos.getY() < 255 && world.isBlockLoaded(pos) && test.test(world, pos)) {
                            out.add(pos);
                        }
                    }
                };
            }
            default:
                unknown.add("placement " + type);
                return null;
        }
    }

    private static final int OCEAN_FLOOR = 0, MOTION_BLOCKING = 1, NO_LEAVES = 2, WORLD_SURFACE = 3;

    private static int heightKind(String heightmap) {
        return heightmap.startsWith("OCEAN_FLOOR") ? OCEAN_FLOOR : heightmap.startsWith("MOTION_BLOCKING_NO_LEAVES") ? NO_LEAVES
                : heightmap.startsWith("MOTION_BLOCKING") ? MOTION_BLOCKING : WORLD_SURFACE;
    }

    /**
     * The y just above the top of a column, by the modern game's four ways of measuring it:
     * the sea floor (anything firm, leaves included, but not water), anything firm or wet,
     * the same without leaves, or anything at all. That leaves count as "floor" is what
     * spaces trees out: a spot under another tree's crown is measured on top of the crown,
     * where no sapling can stand.
     */
    static int surface(World world, BlockPos pos, int kind) {
        net.minecraft.world.chunk.Chunk chunk = world.getChunk(pos);
        int x = pos.getX() & 15, z = pos.getZ() & 15;
        for (int y = Math.min(255, chunk.getTopFilledSegment() + 15); y > 0; y--) {
            IBlockState state = chunk.getBlockState(x, y, z);
            Material material = state.getMaterial();
            if (material == Material.AIR) {
                continue;
            }
            boolean counts;
            switch (kind) {
                case OCEAN_FLOOR:
                    counts = material.blocksMovement();
                    break;
                case MOTION_BLOCKING:
                    counts = material.blocksMovement() || material.isLiquid();
                    break;
                case NO_LEAVES:
                    counts = (material.blocksMovement() || material.isLiquid()) && material != Material.LEAVES;
                    break;
                default:
                    counts = true;
                    break;
            }
            if (counts) {
                return y + 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------
    // Conditions on a spot
    // ------------------------------------------------------------------------------------

    private interface Test {
        boolean test(World world, BlockPos pos);
    }

    private static BlockPos offset(JsonObject json, BlockPos pos) {
        if (!json.has("offset")) {
            return pos;
        }
        JsonArray o = json.getAsJsonArray("offset");
        return pos.add(o.get(0).getAsInt(), o.get(1).getAsInt(), o.get(2).getAsInt());
    }

    private Test test(JsonElement element) {
        final JsonObject json = element.getAsJsonObject();
        String type = json.get("type").getAsString().replace("minecraft:", "");
        switch (type) {
            case "true":
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        return true;
                    }
                };
            case "all_of":
            case "any_of": {
                final List<Test> tests = new ArrayList<Test>();
                for (JsonElement inner : json.getAsJsonArray("predicates")) {
                    tests.add(test(inner));
                }
                final boolean all = type.equals("all_of");
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        for (Test test : tests) {
                            if (test.test(world, pos) != all) {
                                return !all;
                            }
                        }
                        return all;
                    }
                };
            }
            case "not": {
                final Test inner = test(json.get("predicate"));
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        return !inner.test(world, pos);
                    }
                };
            }
            case "matching_blocks": {
                final Set<Block> blocks = new HashSet<Block>();
                final List<String> tags = new ArrayList<String>();
                JsonElement list = json.get("blocks");
                for (JsonElement entry : list.isJsonArray() ? list.getAsJsonArray() : singleton(list)) {
                    String id = entry.getAsString();
                    if (id.startsWith("#")) {
                        tags.add(id.substring(1));
                    } else {
                        IBlockState state = States.of(id, null);
                        if (state != null) {
                            blocks.add(state.getBlock());
                        }
                    }
                }
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        IBlockState state = world.getBlockState(offset(json, pos));
                        if (blocks.contains(state.getBlock())) {
                            return true;
                        }
                        for (String tag : tags) {
                            if (inTag(tag, state)) {
                                return true;
                            }
                        }
                        return false;
                    }
                };
            }
            case "matching_block_tag": {
                final String tag = json.get("tag").getAsString();
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        return inTag(tag, world.getBlockState(offset(json, pos)));
                    }
                };
            }
            case "matching_fluids": {
                final boolean lava = json.get("fluids").toString().contains("lava");
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        return world.getBlockState(offset(json, pos)).getMaterial() == (lava ? Material.LAVA : Material.WATER);
                    }
                };
            }
            case "would_survive": {
                final IBlockState state = States.of(json.get("state"));
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        return state != null && canStay(world, offset(json, pos), state);
                    }
                };
            }
            case "replaceable":
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        BlockPos at = offset(json, pos);
                        IBlockState state = world.getBlockState(at);
                        return state.getBlock().isReplaceable(world, at);
                    }
                };
            case "solid":
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        return world.getBlockState(offset(json, pos)).getMaterial().isSolid();
                    }
                };
            case "has_sturdy_face": {
                final EnumFacing side = json.has("direction") ? EnumFacing.byName(json.get("direction").getAsString()) : EnumFacing.UP;
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        BlockPos at = offset(json, pos);
                        return world.getBlockState(at).isSideSolid(world, at, side == null ? EnumFacing.UP : side);
                    }
                };
            }
            case "inside_world_bounds":
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        int y = offset(json, pos).getY();
                        return y >= 0 && y < 256;
                    }
                };
            default:
                unknown.add("condition " + type);
                return new Test() {
                    @Override
                    public boolean test(World world, BlockPos pos) {
                        return false;
                    }
                };
        }
    }

    private static List<JsonElement> singleton(JsonElement one) {
        return Collections.singletonList(one);
    }

    /** The handful of modern block tags worldgen leans on, by what 1.12.2 blocks are made of. */
    private static boolean inTag(String tag, IBlockState state) {
        Material material = state.getMaterial();
        Block block = state.getBlock();
        String name = tag.substring(tag.indexOf(':') + 1);
        switch (name) {
            case "dirt":
            case "azalea_grows_on":
            case "bamboo_plantable_on":
                return material == Material.GRASS || material == Material.GROUND;
            case "sand":
                return material == Material.SAND;
            case "logs":
            case "logs_that_burn":
            case "overworld_natural_logs":
                return block instanceof BlockLog || material == Material.WOOD;
            case "leaves":
                return material == Material.LEAVES;
            case "base_stone_overworld":
            case "stone_ore_replaceables":
            case "dripstone_replaceable_blocks":
                return isBaseStone(state);
            case "moss_replaceable":
            case "azalea_root_replaceable":
                return isBaseStone(state) || material == Material.GRASS || material == Material.GROUND;
            case "lush_ground_replaceable":
                return isBaseStone(state) || material == Material.GRASS || material == Material.GROUND || material == Material.SAND
                        || material == Material.CLAY;
            case "mangrove_roots_can_grow_through":
            case "mangrove_logs_can_grow_through":
                return material == Material.AIR || material == Material.WATER || material == Material.PLANTS || material == Material.VINE
                        || material == Material.LEAVES;
            case "snow":
                return block == Blocks.SNOW || block == Blocks.SNOW_LAYER;
            case "ice":
                return material == Material.ICE || material == Material.PACKED_ICE;
            case "replaceable":
            case "replaceable_by_trees":
            case "replaceable_plants":
                return material == Material.AIR || material == Material.PLANTS || material == Material.VINE || material == Material.LEAVES;
            default:
                return false;
        }
    }

    /** Whether a plant (or other block that needs footing) could stand at a spot. */
    static boolean canStay(World world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.DOUBLE_PLANT || ModernContent.isTallPlant(block)) {
            // A two-block plant is judged before it is there: room for both halves and footing for the lower.
            return world.isAirBlock(pos) && block.canPlaceBlockAt(world, pos);
        }
        if (block instanceof BlockBush) {
            return ((BlockBush) block).canBlockStay(world, pos, state);
        }
        if (block == Blocks.REEDS) {
            return Blocks.REEDS.canBlockStay(world, pos);
        }
        if (block == Blocks.CACTUS) {
            return Blocks.CACTUS.canBlockStay(world, pos);
        }
        return block.canPlaceBlockAt(world, pos);
    }

    // ------------------------------------------------------------------------------------
    // The things to put down
    // ------------------------------------------------------------------------------------

    private interface Thing {
        boolean generate(World world, Random random, BlockPos pos, Biome biome);
    }

    /** A configured feature by name or written out in place; null if its kind is not known here. */
    private Thing thing(JsonElement reference) {
        if (reference == null) {
            return null;
        }
        if (reference.isJsonObject()) {
            return build("(inline)", reference.getAsJsonObject());
        }
        String id = WorldData.canonical(reference.getAsString());
        if (!things.containsKey(id)) {
            Thing built = null;
            try {
                things.put(id, null); // a feature that names itself must not loop
                built = build(id, data.read("configured_feature", id).getAsJsonObject());
            } catch (RuntimeException e) {
                Better112.LOG.debug("Feature {} cannot be read: {}", id, e.toString());
            }
            things.put(id, built);
        }
        return things.get(id);
    }

    private Thing build(final String id, JsonObject json) {
        final String fullType = json.get("type").getAsString();
        String type = fullType.replace("minecraft:", "");
        final JsonObject config = json.has("config") ? json.getAsJsonObject("config") : new JsonObject();
        switch (type) {
            case "random_selector": {
                final List<Placed> choices = new ArrayList<Placed>();
                final List<Float> chances = new ArrayList<Float>();
                for (JsonElement entry : config.getAsJsonArray("features")) {
                    JsonObject e = entry.getAsJsonObject();
                    choices.add(placed(e.get("feature")));
                    chances.add(e.get("chance").getAsFloat());
                }
                final Placed otherwise = placed(config.get("default"));
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        for (int i = 0; i < choices.size(); i++) {
                            if (random.nextFloat() < chances.get(i)) {
                                return choices.get(i) != null && choices.get(i).place(world, random, pos, null);
                            }
                        }
                        return otherwise != null && otherwise.place(world, random, pos, null);
                    }
                };
            }
            case "simple_random_selector": {
                final List<Placed> choices = new ArrayList<Placed>();
                for (JsonElement entry : config.getAsJsonArray("features")) {
                    Placed choice = placed(entry);
                    if (choice != null) {
                        choices.add(choice);
                    }
                }
                return choices.isEmpty() ? null : new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        return choices.get(random.nextInt(choices.size())).place(world, random, pos, null);
                    }
                };
            }
            case "random_boolean_selector": {
                final Placed yes = placed(config.get("feature_true")), no = placed(config.get("feature_false"));
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        Placed pick = random.nextBoolean() ? yes : no;
                        return pick != null && pick.place(world, random, pos, null);
                    }
                };
            }
            case "random_patch":
            case "flower":
            case "no_bonemeal_flower": {
                final int tries = config.has("tries") ? config.get("tries").getAsInt() : 128;
                final int xz = (config.has("xz_spread") ? config.get("xz_spread").getAsInt() : 7) + 1;
                final int ys = (config.has("y_spread") ? config.get("y_spread").getAsInt() : 3) + 1;
                final Placed each = placed(config.get("feature"));
                return each == null || !each.usable() ? null : new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        boolean any = false;
                        for (int i = 0; i < tries; i++) {
                            // Kept within eight blocks: a patch must not spill into chunks that do not exist yet.
                            BlockPos at = pos.add(clamp(random.nextInt(xz) - random.nextInt(xz)), random.nextInt(ys) - random.nextInt(ys),
                                    clamp(random.nextInt(xz) - random.nextInt(xz)));
                            any |= each.place(world, random, at, null);
                        }
                        return any;
                    }
                };
            }
            case "simple_block": {
                final Values.State provider = Values.State.parse(config.get("to_place"));
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        return set(world, pos, provider.get(random));
                    }
                };
            }
            case "block_column": {
                final List<Values.Int> heights = new ArrayList<Values.Int>();
                final List<Values.State> providers = new ArrayList<Values.State>();
                for (JsonElement layer : config.getAsJsonArray("layers")) {
                    heights.add(Values.Int.parse(layer.getAsJsonObject().get("height"), 1));
                    providers.add(Values.State.parse(layer.getAsJsonObject().get("provider")));
                }
                final EnumFacing direction = config.has("direction") && "down".equals(config.get("direction").getAsString()) ? EnumFacing.DOWN : EnumFacing.UP;
                final boolean tipFirst = config.has("prioritize_tip") && config.get("prioritize_tip").getAsBoolean();
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        int[] lengths = new int[heights.size()];
                        int total = 0;
                        for (int i = 0; i < lengths.length; i++) {
                            lengths[i] = heights.get(i).get(random);
                            total += lengths[i];
                        }
                        // How much room there is; a column that does not fit is cut short, from its body if the tip matters most.
                        int room = 0;
                        BlockPos probe = pos;
                        while (room < total && probe.getY() > 0 && probe.getY() < 255 && world.isAirBlock(probe)) {
                            room++;
                            probe = probe.offset(direction);
                        }
                        for (int i = tipFirst ? 0 : lengths.length - 1; total > room && i >= 0 && i < lengths.length; i += tipFirst ? 1 : -1) {
                            int cut = Math.min(lengths[i], total - room);
                            lengths[i] -= cut;
                            total -= cut;
                        }
                        BlockPos at = pos;
                        boolean any = false;
                        for (int i = 0; i < lengths.length; i++) {
                            for (int n = lengths[i]; n > 0; n--) {
                                IBlockState state = providers.get(i).get(random);
                                if (state == null || !world.isAirBlock(at) || (!any && !canStay(world, at, state))) {
                                    return any;
                                }
                                world.setBlockState(at, state, FLAGS);
                                any = true;
                                at = at.offset(direction);
                            }
                        }
                        return any;
                    }
                };
            }
            case "tree": {
                final Trees.Grower modern = Trees.modern(config);
                if (modern != null) {
                    return new Thing() {
                        @Override
                        public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                            return modern.generate(world, random, pos);
                        }
                    };
                }
                final WorldGenAbstractTree vanilla = id.startsWith("minecraft:") ? Trees.vanilla(id) : null;
                if (vanilla != null) {
                    return new Thing() {
                        @Override
                        public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                            return onSoil(world, pos, vanilla, random);
                        }
                    };
                }
                if (id.startsWith("minecraft:")) {
                    unknown.add("tree " + id);
                    return null;
                }
                // A tree of the mod written in the vanilla form: its trunk and leaves in a plain oak shape.
                JsonObject asShape = new JsonObject();
                asShape.add("trunk_provider", config.get("trunk_provider"));
                asShape.add("foliage_provider", config.get("foliage_provider"));
                asShape.addProperty("min_height", 5);
                asShape.addProperty("max_height", 7);
                final Trees.Shape shape = new Trees.Shape(asShape);
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        return Trees.grow("basic_tree", world, random, pos, shape);
                    }
                };
            }
            case "forest_rock": {
                // A boulder; 1.12.2 has the same thing for its mega taiga.
                final IBlockState rock = States.of(config.get("state"));
                return rock == null ? null : new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        return new net.minecraft.world.gen.feature.WorldGenBlockBlob(rock.getBlock(), 0).generate(world, random, pos);
                    }
                };
            }
            case "huge_red_mushroom":
            case "huge_brown_mushroom": {
                final Block cap = type.equals("huge_red_mushroom") ? Blocks.RED_MUSHROOM_BLOCK : Blocks.BROWN_MUSHROOM_BLOCK;
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        return new net.minecraft.world.gen.feature.WorldGenBigMushroom(cap).generate(world, random, pos);
                    }
                };
            }
            case "bamboo": {
                final IBlockState stalk = States.of("minecraft:bamboo", null);
                if (stalk == null) {
                    unknown.add("feature bamboo (no bamboo block)");
                    return null;
                }
                final float podzolChance = config.has("probability") ? config.get("probability").getAsFloat() : 0.0F;
                final IBlockState podzol = States.of("minecraft:podzol", null), thick = States.with(stalk, "age", "1");
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        if (!world.isAirBlock(pos) || !stalk.getBlock().canPlaceBlockAt(world, pos)) {
                            return false;
                        }
                        int height = random.nextInt(12) + 5;
                        if (random.nextFloat() < podzolChance) {
                            int radius = random.nextInt(4) + 1;
                            for (int dx = -radius; dx <= radius; dx++) {
                                for (int dz = -radius; dz <= radius; dz++) {
                                    BlockPos column = pos.add(dx, 0, dz);
                                    if (dx * dx + dz * dz <= radius * radius && world.isBlockLoaded(column)) {
                                        BlockPos ground = world.getPrecipitationHeight(column).down();
                                        net.minecraft.block.Block there = world.getBlockState(ground).getBlock();
                                        if (there == Blocks.GRASS || there == Blocks.DIRT) {
                                            world.setBlockState(ground, podzol, FLAGS);
                                        }
                                    }
                                }
                            }
                        }
                        int placed = 0;
                        while (placed < height && pos.getY() + placed < 254 && world.isAirBlock(pos.up(placed))) {
                            world.setBlockState(pos.up(placed), thick, FLAGS);
                            placed++;
                        }
                        if (placed >= 3) {
                            // The top three pieces carry the leaves; the topmost has stopped growing.
                            world.setBlockState(pos.up(placed - 1), States.with(States.with(thick, "leaves", "large"), "stage", "1"), FLAGS);
                            world.setBlockState(pos.up(placed - 2), States.with(thick, "leaves", "large"), FLAGS);
                            world.setBlockState(pos.up(placed - 3), States.with(thick, "leaves", "small"), FLAGS);
                        }
                        return placed > 0;
                    }
                };
            }
            case "vines":
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        if (!world.isAirBlock(pos)) {
                            return false;
                        }
                        for (EnumFacing side : EnumFacing.HORIZONTALS) {
                            if (Blocks.VINE.canPlaceBlockOnSide(world, pos, side)) {
                                world.setBlockState(pos, Blocks.VINE.getDefaultState().withProperty(net.minecraft.block.BlockVine.getPropertyFor(side.getOpposite()),
                                        Boolean.TRUE), FLAGS);
                                return true;
                            }
                        }
                        return false;
                    }
                };
            case "vegetation_patch":
            case "waterlogged_vegetation_patch": {
                final boolean floor = !"ceiling".equals(config.get("surface").getAsString()), pool = type.startsWith("waterlogged");
                final Values.Int depth = Values.Int.parse(config.get("depth"), 1), spread = Values.Int.parse(config.get("xz_radius"), 2);
                final int reach = config.get("vertical_range").getAsInt();
                final float plantChance = config.get("vegetation_chance").getAsFloat(), edgeChance = config.get("extra_edge_column_chance").getAsFloat(),
                        deeper = config.get("extra_bottom_block_chance").getAsFloat();
                final Values.State ground = Values.State.parse(config.get("ground_state"));
                final String replaceable = config.get("replaceable").getAsString().replace("#", "");
                final Placed plant = placed(config.get("vegetation_feature"));
                if (ground.get(new Random(0L)) == null) {
                    unknown.add("feature " + id + " (no such ground block)");
                    return null;
                }
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        EnumFacing toward = floor ? EnumFacing.DOWN : EnumFacing.UP, away = toward.getOpposite();
                        int rx = Math.min(7, spread.get(random) + 1), rz = Math.min(7, spread.get(random) + 1);
                        List<BlockPos> laid = new ArrayList<BlockPos>();
                        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
                        for (int dx = -rx; dx <= rx; dx++) {
                            boolean edgeX = dx == -rx || dx == rx;
                            for (int dz = -rz; dz <= rz; dz++) {
                                boolean edgeZ = dz == -rz || dz == rz;
                                if ((edgeX && edgeZ) || ((edgeX || edgeZ) && (edgeChance == 0.0F || random.nextFloat() > edgeChance))) {
                                    continue;
                                }
                                at.setPos(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                                if (!world.isBlockLoaded(at)) {
                                    continue;
                                }
                                for (int k = 0; k < reach && at.getY() > 1 && at.getY() < 254 && world.isAirBlock(at); k++) {
                                    at.move(toward);
                                }
                                for (int k = 0; k < reach && at.getY() > 1 && at.getY() < 254 && !world.isAirBlock(at); k++) {
                                    at.move(away);
                                }
                                BlockPos open = at.toImmutable(), base = open.offset(toward);
                                if (!world.isAirBlock(open) || !world.getBlockState(base).isSideSolid(world, base, away)) {
                                    continue;
                                }
                                int layers = depth.get(random) + (deeper > 0.0F && random.nextFloat() < deeper ? 1 : 0);
                                boolean any = false;
                                BlockPos each = base;
                                for (int i = 0; i < layers; i++) {
                                    IBlockState now = world.getBlockState(each);
                                    if (!inTag(replaceable, now)) {
                                        break;
                                    }
                                    world.setBlockState(each, ground.get(random), FLAGS);
                                    any = true;
                                    each = each.offset(toward);
                                }
                                if (any) {
                                    laid.add(base);
                                }
                            }
                        }
                        if (pool) {
                            // A puddle: the top of the patch becomes water wherever its neighbours would hold the water in.
                            List<BlockPos> wet = new ArrayList<BlockPos>();
                            for (BlockPos base : laid) {
                                boolean held = world.getBlockState(base.down()).isSideSolid(world, base.down(), EnumFacing.UP);
                                for (EnumFacing side : EnumFacing.HORIZONTALS) {
                                    BlockPos next = base.offset(side);
                                    held &= world.isBlockLoaded(next) && world.getBlockState(next).isSideSolid(world, next, side.getOpposite());
                                }
                                if (held) {
                                    wet.add(base);
                                }
                            }
                            for (BlockPos base : wet) {
                                world.setBlockState(base, Blocks.WATER.getDefaultState(), FLAGS);
                            }
                            laid.removeAll(wet);
                        }
                        if (plant != null && plantChance > 0.0F) {
                            for (BlockPos base : laid) {
                                if (random.nextFloat() < plantChance) {
                                    plant.place(world, random, base.offset(away), null);
                                }
                            }
                        }
                        return !laid.isEmpty();
                    }
                };
            }
            case "disk": {
                JsonObject provider = config.getAsJsonObject("state_provider");
                final Values.State fallback = Values.State.parse(provider.get("fallback"));
                final List<Test> conditions = new ArrayList<Test>();
                final List<Values.State> outcomes = new ArrayList<Values.State>();
                if (provider.has("rules")) {
                    for (JsonElement rule : provider.getAsJsonArray("rules")) {
                        conditions.add(test(rule.getAsJsonObject().get("if_true")));
                        outcomes.add(Values.State.parse(rule.getAsJsonObject().get("then")));
                    }
                }
                final Test target = test(config.get("target"));
                final Values.Int radius = Values.Int.parse(config.get("radius"), 2);
                final int half = config.get("half_height").getAsInt();
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        int r = Math.min(7, radius.get(random));
                        boolean any = false;
                        for (int dx = -r; dx <= r; dx++) {
                            for (int dz = -r; dz <= r; dz++) {
                                if (dx * dx + dz * dz > r * r || !world.isBlockLoaded(pos.add(dx, 0, dz))) {
                                    continue;
                                }
                                for (int y = pos.getY() + half; y > pos.getY() - half - 1; y--) {
                                    BlockPos at = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                                    if (y < 1 || y > 254 || !target.test(world, at)) {
                                        continue;
                                    }
                                    Values.State pick = fallback;
                                    for (int i = 0; i < conditions.size(); i++) {
                                        if (conditions.get(i).test(world, at)) {
                                            pick = outcomes.get(i);
                                            break;
                                        }
                                    }
                                    IBlockState state = pick.get(random);
                                    if (state != null) {
                                        world.setBlockState(at, state, FLAGS);
                                        any = true;
                                    }
                                }
                            }
                        }
                        return any;
                    }
                };
            }
            case "root_system": {
                // An azalea tree on the surface above a cave, with a column of rooted dirt down to the cave and roots hanging in it.
                final Placed tree = placed(config.get("feature"));
                final Test treeSpot = test(config.get("allowed_tree_position"));
                final int space = config.get("required_vertical_space_for_tree").getAsInt(), maxRise = config.get("root_column_max_height").getAsInt();
                final int rootRadius = config.get("root_radius").getAsInt(), rootTries = config.get("root_placement_attempts").getAsInt();
                final int hangRadius = config.get("hanging_root_radius").getAsInt(), hangSpan = config.get("hanging_roots_vertical_span").getAsInt(),
                        hangTries = config.get("hanging_root_placement_attempts").getAsInt();
                final Values.State rooted = Values.State.parse(config.get("root_state_provider")), hanging = Values.State.parse(config.get("hanging_root_state_provider"));
                final String rootable = config.get("root_replaceable").getAsString().replace("#", "");
                if (tree == null || !tree.usable()) {
                    return null;
                }
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        if (!world.isAirBlock(pos)) {
                            return false;
                        }
                        int top = -1;
                        for (int rise = 1; rise <= maxRise && pos.getY() + rise + space < 255; rise++) {
                            BlockPos at = pos.up(rise);
                            if (!treeSpot.test(world, at)) {
                                continue;
                            }
                            boolean room = true;
                            for (int i = 1; i <= space && room; i++) {
                                room = world.isAirBlock(at.up(i));
                            }
                            if (!room) {
                                continue;
                            }
                            if (!world.getBlockState(at.down()).getMaterial().isSolid() || !tree.place(world, random, at, null)) {
                                return false;
                            }
                            top = at.getY();
                            break;
                        }
                        if (top < 0) {
                            return false;
                        }
                        IBlockState dirt = rooted.get(random), roots = hanging.get(random);
                        for (int y = pos.getY(); y < top && dirt != null; y++) {
                            int x = pos.getX(), z = pos.getZ();
                            for (int i = 0; i < rootTries; i++) {
                                BlockPos at = new BlockPos(x + random.nextInt(rootRadius) - random.nextInt(rootRadius), y, z + random.nextInt(rootRadius)
                                        - random.nextInt(rootRadius));
                                if (world.isBlockLoaded(at) && inTag(rootable, world.getBlockState(at))) {
                                    world.setBlockState(at, dirt, FLAGS);
                                }
                            }
                        }
                        for (int i = 0; i < hangTries && roots != null; i++) {
                            BlockPos at = pos.add(random.nextInt(hangRadius) - random.nextInt(hangRadius), random.nextInt(hangSpan) - random.nextInt(hangSpan),
                                    random.nextInt(hangRadius) - random.nextInt(hangRadius));
                            if (at.getY() > 0 && world.isBlockLoaded(at) && world.isAirBlock(at) && roots.getBlock().canPlaceBlockAt(world, at)) {
                                world.setBlockState(at, roots, FLAGS);
                            }
                        }
                        return true;
                    }
                };
            }
            case "pointed_dripstone": {
                final IBlockState spike = States.of("minecraft:pointed_dripstone", null), block = States.of("minecraft:dripstone_block", null);
                if (spike == null || block == null) {
                    unknown.add("feature pointed_dripstone (no dripstone blocks)");
                    return null;
                }
                final float taller = config.get("chance_of_taller_dripstone").getAsFloat(), spreads = config.get("chance_of_directional_spread").getAsFloat(),
                        further = config.get("chance_of_spread_radius2").getAsFloat(), furthest = config.get("chance_of_spread_radius3").getAsFloat();
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        if (!world.isAirBlock(pos)) {
                            return false;
                        }
                        boolean above = dripstoneBase(world, pos.up(), block), below = dripstoneBase(world, pos.down(), block);
                        if (!above && !below) {
                            return false;
                        }
                        // Hanging from the rock above, or standing on the rock below.
                        EnumFacing way = above && (!below || random.nextBoolean()) ? EnumFacing.DOWN : EnumFacing.UP;
                        BlockPos root = pos.offset(way.getOpposite());
                        toDripstone(world, root, block);
                        for (EnumFacing side : EnumFacing.HORIZONTALS) {
                            if (random.nextFloat() > spreads) {
                                continue;
                            }
                            BlockPos next = root.offset(side);
                            toDripstone(world, next, block);
                            if (random.nextFloat() > further) {
                                continue;
                            }
                            next = next.offset(EnumFacing.VALUES[random.nextInt(6)]);
                            toDripstone(world, next, block);
                            if (random.nextFloat() <= furthest) {
                                toDripstone(world, next.offset(EnumFacing.VALUES[random.nextInt(6)]), block);
                            }
                        }
                        growDripstone(world, pos, way, random.nextFloat() < taller ? 2 : 1, spike);
                        return true;
                    }
                };
            }
            case "dripstone_cluster": {
                final IBlockState spike = States.of("minecraft:pointed_dripstone", null), block = States.of("minecraft:dripstone_block", null);
                if (spike == null || block == null) {
                    unknown.add("feature dripstone_cluster (no dripstone blocks)");
                    return null;
                }
                final int search = config.get("floor_to_ceiling_search_range").getAsInt(), heightBias = config.get("max_distance_from_center_affecting_height_bias").getAsInt();
                final int edge = config.get("max_distance_from_edge_affecting_chance_of_dripstone_column").getAsInt(), differ = config.get("max_stalagmite_stalactite_height_diff").getAsInt();
                final float deviation = config.get("height_deviation").getAsFloat(), edgeChance = config.get("chance_of_dripstone_column_at_max_distance_from_center").getAsFloat();
                final Values.Int height = Values.Int.parse(config.get("height"), 4), radius = Values.Int.parse(config.get("radius"), 4),
                        thickness = Values.Int.parse(config.get("dripstone_block_layer_thickness"), 3);
                JsonObject d = config.getAsJsonObject("density");
                final float densityMin = d.get("min_inclusive").getAsFloat(), densityMax = d.get("max_exclusive").getAsFloat();
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        if (!world.isAirBlock(pos)) {
                            return false;
                        }
                        int tall = height.get(random), rx = Math.min(7, radius.get(random)), rz = Math.min(7, radius.get(random));
                        float density = densityMin + random.nextFloat() * (densityMax - densityMin);
                        for (int dx = -rx; dx <= rx; dx++) {
                            for (int dz = -rz; dz <= rz; dz++) {
                                BlockPos at = pos.add(dx, 0, dz);
                                if (!world.isBlockLoaded(at) || !world.isAirBlock(at)) {
                                    continue;
                                }
                                int fromEdge = Math.min(rx - Math.abs(dx), rz - Math.abs(dz));
                                double chance = fromEdge >= edge ? 1.0D : edgeChance + (1.0D - edgeChance) * fromEdge / edge;
                                // The cave's ceiling and floor here, if within reach.
                                int ceiling = -1, floor = -1;
                                for (int i = 1; i <= search && at.getY() + i < 255; i++) {
                                    if (!world.isAirBlock(at.up(i))) {
                                        ceiling = dripstoneBase(world, at.up(i), block) ? at.getY() + i : -1;
                                        break;
                                    }
                                }
                                for (int i = 1; i <= search && at.getY() - i > 0; i++) {
                                    if (!world.isAirBlock(at.down(i))) {
                                        floor = dripstoneBase(world, at.down(i), block) ? at.getY() - i : -1;
                                        break;
                                    }
                                }
                                if (ceiling < 0 && floor < 0) {
                                    continue;
                                }
                                int distance = Math.abs(dx) + Math.abs(dz);
                                int down = 0, up = 0;
                                if (ceiling >= 0 && random.nextDouble() < chance) {
                                    layer(world, new BlockPos(at.getX(), ceiling, at.getZ()), thickness.get(random), EnumFacing.UP, block);
                                    down = spike(random, distance, density, floor >= 0 ? Math.min(tall, ceiling - floor) : tall);
                                }
                                if (floor >= 0 && random.nextDouble() < chance) {
                                    layer(world, new BlockPos(at.getX(), floor, at.getZ()), thickness.get(random), EnumFacing.DOWN, block);
                                    up = ceiling >= 0 ? Math.max(0, down + random.nextInt(differ * 2 + 1) - differ) : spike(random, distance, density, tall);
                                }
                                if (ceiling >= 0 && floor >= 0 && ceiling - down <= floor + up) {
                                    // The two would run into each other: they meet at a point in between.
                                    int low = Math.max(ceiling - down, floor + 1), high = Math.min(floor + up, ceiling - 1);
                                    int meet = low + random.nextInt(Math.max(1, high + 2 - low));
                                    down = ceiling - meet;
                                    up = meet - 1 - floor;
                                }
                                if (ceiling >= 0) {
                                    growDripstone(world, new BlockPos(at.getX(), ceiling - 1, at.getZ()), EnumFacing.DOWN, down, spike);
                                }
                                if (floor >= 0) {
                                    growDripstone(world, new BlockPos(at.getX(), floor + 1, at.getZ()), EnumFacing.UP, up, spike);
                                }
                            }
                        }
                        return true;
                    }

                    private int spike(Random random, int distance, float density, int most) {
                        if (most <= 0 || random.nextFloat() > density) {
                            return 0;
                        }
                        // Taller toward the middle of the cluster.
                        double mean = distance >= heightBias ? 0.0D : most / 2.0D * (1.0D - distance / (double) heightBias);
                        return (int) Math.max(0.0D, Math.min(most, mean + random.nextGaussian() * deviation));
                    }

                    private void layer(World world, BlockPos from, int layers, EnumFacing way, IBlockState with) {
                        for (int i = 0; i < layers; i++) {
                            if (!toDripstone(world, from.offset(way, i), with)) {
                                return;
                            }
                        }
                    }
                };
            }
            case "seagrass": {
                final IBlockState low = States.of("minecraft:seagrass", null), tall = States.of("minecraft:tall_seagrass", null);
                if (low == null) {
                    unknown.add("feature seagrass (no seagrass block)");
                    return null;
                }
                final double tallChance = config.has("probability") ? config.get("probability").getAsDouble() : 0.0D;
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        BlockPos column = pos.add(random.nextInt(8) - random.nextInt(8), 0, random.nextInt(8) - random.nextInt(8));
                        if (!world.isBlockLoaded(column)) {
                            return false;
                        }
                        BlockPos at = new BlockPos(column.getX(), surface(world, column, OCEAN_FLOOR), column.getZ());
                        boolean high = tall != null && random.nextDouble() < tallChance;
                        return ModernContent.placeAquatic(world, at, high ? tall : low, FLAGS) || (high && ModernContent.placeAquatic(world, at, low, FLAGS));
                    }
                };
            }
            case "kelp": {
                final IBlockState top = States.of("minecraft:kelp", null), stalk = States.of("minecraft:kelp_plant", null);
                if (top == null || stalk == null) {
                    unknown.add("feature kelp (no kelp blocks)");
                    return null;
                }
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        BlockPos at = new BlockPos(pos.getX(), surface(world, pos, OCEAN_FLOOR), pos.getZ());
                        int height = 1 + random.nextInt(10), placed = 0;
                        // A stalk of so many pieces with the growing tip on top; it stops two blocks short of the surface at the latest.
                        while (placed <= height && at.getY() < 254 && ModernContent.placeAquatic(world, at, stalk, FLAGS)) {
                            placed++;
                            at = at.up();
                        }
                        if (placed > 0) {
                            world.setBlockState(at.down(), top, FLAGS);
                        }
                        return placed > 0;
                    }
                };
            }
            case "sea_pickle": {
                final IBlockState pickle = States.of("minecraft:sea_pickle", null);
                if (pickle == null) {
                    unknown.add("feature sea_pickle (no sea pickle block)");
                    return null;
                }
                final int tries = config.has("count") ? Values.Int.parse(config.get("count"), 20).get(new Random(0L)) : 20;
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        boolean any = false;
                        for (int i = 0; i < tries; i++) {
                            BlockPos column = pos.add(clamp(random.nextInt(8) - random.nextInt(8)), 0, clamp(random.nextInt(8) - random.nextInt(8)));
                            if (world.isBlockLoaded(column)) {
                                BlockPos at = new BlockPos(column.getX(), surface(world, column, OCEAN_FLOOR), column.getZ());
                                any |= ModernContent.placeAquatic(world, at, States.with(pickle, "pickles", String.valueOf(random.nextInt(4) + 1)), FLAGS);
                            }
                        }
                        return any;
                    }
                };
            }
            case "coral_tree":
            case "coral_claw":
            case "coral_mushroom": {
                final Corals corals = new Corals();
                if (corals.blocks.isEmpty()) {
                    unknown.add("feature " + type + " (no coral blocks)");
                    return null;
                }
                final String shape = type;
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        IBlockState coral = corals.blocks.get(random.nextInt(corals.blocks.size()));
                        if (shape.equals("coral_tree")) {
                            return corals.tree(world, random, pos, coral);
                        }
                        return shape.equals("coral_claw") ? corals.claw(world, random, pos, coral) : corals.mushroom(world, random, pos, coral);
                    }
                };
            }
            case "blue_ice": {
                final IBlockState blue = States.of("minecraft:blue_ice", null);
                return new Thing() {
                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        if (pos.getY() > world.getSeaLevel() - 1 || (world.getBlockState(pos).getMaterial() != Material.WATER
                                && world.getBlockState(pos.down()).getMaterial() != Material.WATER)) {
                            return false;
                        }
                        boolean byIce = false;
                        for (EnumFacing side : EnumFacing.VALUES) {
                            byIce |= side != EnumFacing.DOWN && world.isBlockLoaded(pos.offset(side)) && world.getBlockState(pos.offset(side)).getBlock() == Blocks.PACKED_ICE;
                        }
                        if (!byIce) {
                            return false;
                        }
                        world.setBlockState(pos, blue, FLAGS);
                        for (int i = 0; i < 200; i++) {
                            int dy = random.nextInt(5) - random.nextInt(6), spread = dy < 2 ? 3 + dy / 2 : 3;
                            if (spread < 1) {
                                continue;
                            }
                            BlockPos at = pos.add(random.nextInt(spread) - random.nextInt(spread), dy, random.nextInt(spread) - random.nextInt(spread));
                            if (at.getY() < 1 || !world.isBlockLoaded(at)) {
                                continue;
                            }
                            IBlockState there = world.getBlockState(at);
                            if (there.getMaterial() != Material.AIR && there.getBlock() != Blocks.WATER && there.getBlock() != Blocks.PACKED_ICE && there.getBlock() != Blocks.ICE) {
                                continue;
                            }
                            for (EnumFacing side : EnumFacing.VALUES) {
                                if (world.getBlockState(at.offset(side)).getBlock() == blue.getBlock()) {
                                    world.setBlockState(at, blue, FLAGS);
                                    break;
                                }
                            }
                        }
                        return true;
                    }
                };
            }
            case "geode":
                return geode(id, config);
            case "multiface_growth": {
                final IBlockState growth = States.of(config.get("block").getAsString(), null);
                if (growth == null) {
                    unknown.add("feature " + id + " (no such block)");
                    return null;
                }
                final boolean ceiling = flag(config, "can_place_on_ceiling"), floor = flag(config, "can_place_on_floor"), wall = flag(config, "can_place_on_wall");
                final float spreads = config.has("chance_of_spreading") ? config.get("chance_of_spreading").getAsFloat() : 0.5F;
                return new Thing() {
                    private boolean clings(World world, BlockPos pos) {
                        for (EnumFacing side : EnumFacing.VALUES) {
                            if ((side == EnumFacing.UP && !ceiling) || (side == EnumFacing.DOWN && !floor) || (side.getAxis() != EnumFacing.Axis.Y && !wall)) {
                                continue;
                            }
                            BlockPos next = pos.offset(side);
                            if (world.isBlockLoaded(next) && isBaseStone(world.getBlockState(next))) {
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                        if (!world.isAirBlock(pos)) {
                            return false;
                        }
                        BlockPos at = pos;
                        if (!clings(world, at)) {
                            at = null;
                            for (EnumFacing side : EnumFacing.VALUES) {
                                BlockPos next = pos.offset(side);
                                if (next.getY() > 0 && next.getY() < 255 && world.isBlockLoaded(next) && world.isAirBlock(next) && clings(world, next)) {
                                    at = next;
                                    break;
                                }
                            }
                        }
                        if (at == null) {
                            return false;
                        }
                        world.setBlockState(at, growth, FLAGS);
                        if (random.nextFloat() < spreads) {
                            BlockPos next = at.offset(EnumFacing.VALUES[random.nextInt(6)]);
                            if (next.getY() > 0 && next.getY() < 255 && world.isBlockLoaded(next) && world.isAirBlock(next) && clings(world, next)) {
                                world.setBlockState(next, growth, FLAGS);
                            }
                        }
                        return true;
                    }
                };
            }
            default:
                if (fullType.startsWith(ModernContent.modNamespace() + ":") && Decor.knows(type.substring(type.indexOf(':') + 1))) {
                    final String kind = type.substring(type.indexOf(':') + 1);
                    return new Thing() {
                        @Override
                        public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                            return Decor.generate(kind, world, random, pos);
                        }
                    };
                }
                if (fullType.startsWith(ModernContent.modNamespace() + ":") && type.endsWith("_tree")) {
                    final String kind = type.substring(type.indexOf(':') + 1);
                    final Trees.Shape shape = new Trees.Shape(config);
                    return new Thing() {
                        @Override
                        public boolean generate(World world, Random random, BlockPos pos, Biome biome) {
                            return Trees.grow(kind, world, random, pos, shape);
                        }
                    };
                }
                unknown.add("feature " + type);
                return null;
        }
    }

    /** Coral reefs: stacks of coral blocks in three shapes, with coral, fans and now and then a sea pickle growing on them. */
    private static final class Corals {
        final List<IBlockState> blocks = new ArrayList<IBlockState>(), plants = new ArrayList<IBlockState>(), wallFans = new ArrayList<IBlockState>();
        final IBlockState pickle = States.of("minecraft:sea_pickle", null);

        Corals() {
            for (String kind : new String[] {"tube", "brain", "bubble", "fire", "horn"}) {
                add(blocks, kind + "_coral_block");
                add(plants, kind + "_coral");
                add(plants, kind + "_coral_fan");
                add(wallFans, kind + "_coral_wall_fan");
            }
        }

        private static void add(List<IBlockState> list, String name) {
            IBlockState state = States.of("minecraft:" + name, null);
            if (state != null) {
                list.add(state);
            }
        }

        private static boolean sea(World world, BlockPos pos) {
            return pos.getY() > 0 && pos.getY() < 255 && world.isBlockLoaded(pos) && world.getBlockState(pos).getBlock() == Blocks.WATER;
        }

        /** One coral block where there is water (or coral growth) now and water above, with a chance of something growing on it. */
        boolean block(World world, Random random, BlockPos pos, IBlockState coral) {
            if (pos.getY() < 1 || !world.isBlockLoaded(pos)) {
                return false;
            }
            IBlockState there = world.getBlockState(pos);
            if ((there.getBlock() != Blocks.WATER && !ModernContent.isAquatic(there.getBlock())) || !sea(world, pos.up())) {
                return false;
            }
            world.setBlockState(pos, coral, FLAGS);
            if (random.nextFloat() < 0.25F && !plants.isEmpty()) {
                ModernContent.placeAquatic(world, pos.up(), plants.get(random.nextInt(plants.size())), FLAGS);
            } else if (random.nextFloat() < 0.05F && pickle != null) {
                ModernContent.placeAquatic(world, pos.up(), States.with(pickle, "pickles", String.valueOf(random.nextInt(4) + 1)), FLAGS);
            }
            for (EnumFacing side : EnumFacing.HORIZONTALS) {
                if (random.nextFloat() < 0.2F && !wallFans.isEmpty() && sea(world, pos.offset(side))) {
                    ModernContent.placeAquatic(world, pos.offset(side),
                            States.with(wallFans.get(random.nextInt(wallFans.size())), "facing", side.getName()), FLAGS);
                }
            }
            return true;
        }

        boolean tree(World world, Random random, BlockPos pos, IBlockState coral) {
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(pos);
            for (int i = random.nextInt(3) + 1; i > 0; i--) {
                if (!block(world, random, at.toImmutable(), coral)) {
                    return true;
                }
                at.move(EnumFacing.UP);
            }
            BlockPos top = at.toImmutable();
            List<EnumFacing> ways = new ArrayList<EnumFacing>(java.util.Arrays.asList(EnumFacing.HORIZONTALS));
            Collections.shuffle(ways, random);
            for (EnumFacing way : ways.subList(0, random.nextInt(3) + 2)) {
                at.setPos(top).move(way);
                int length = random.nextInt(5) + 2, straight = 0;
                for (int l = 0; l < length && block(world, random, at.toImmutable(), coral); l++) {
                    straight++;
                    at.move(EnumFacing.UP);
                    if (l == 0 || (straight >= 2 && random.nextFloat() < 0.25F)) {
                        at.move(way);
                        straight = 0;
                    }
                }
            }
            return true;
        }

        boolean claw(World world, Random random, BlockPos pos, IBlockState coral) {
            if (!block(world, random, pos, coral)) {
                return false;
            }
            EnumFacing forward = EnumFacing.HORIZONTALS[random.nextInt(4)];
            List<EnumFacing> ways = new ArrayList<EnumFacing>(java.util.Arrays.asList(forward, forward.rotateY(), forward.rotateYCCW()));
            Collections.shuffle(ways, random);
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (EnumFacing way : ways.subList(0, random.nextInt(2) + 2)) {
                at.setPos(pos).move(way);
                int out = random.nextInt(2) + 1, reach;
                EnumFacing along;
                if (way == forward) {
                    along = forward;
                    reach = random.nextInt(3) + 2;
                } else {
                    at.move(EnumFacing.UP);
                    along = random.nextBoolean() ? way : EnumFacing.UP;
                    reach = random.nextInt(3) + 3;
                }
                for (int j = 0; j < out && block(world, random, at.toImmutable(), coral); j++) {
                    at.move(along);
                }
                at.move(along.getOpposite()).move(EnumFacing.UP);
                for (int j = 0; j < reach; j++) {
                    at.move(forward);
                    if (!block(world, random, at.toImmutable(), coral)) {
                        break;
                    }
                    if (random.nextFloat() < 0.25F) {
                        at.move(EnumFacing.UP);
                    }
                }
            }
            return true;
        }

        boolean mushroom(World world, Random random, BlockPos pos, IBlockState coral) {
            int tall = random.nextInt(3) + 3, wide = random.nextInt(3) + 3, deep = random.nextInt(3) + 3, sunk = random.nextInt(3) + 1;
            for (int x = 0; x <= wide; x++) {
                for (int y = 0; y <= tall; y++) {
                    for (int z = 0; z <= deep; z++) {
                        boolean edgeX = x == 0 || x == wide, edgeY = y == 0 || y == tall, edgeZ = z == 0 || z == deep;
                        // A hollow box with its edges left off, and a block missing here and there.
                        if ((edgeX && edgeY) || (edgeZ && edgeY) || (edgeX && edgeZ) || (!edgeX && !edgeY && !edgeZ) || random.nextFloat() < 0.1F) {
                            continue;
                        }
                        block(world, random, pos.add(x, y - sunk, z), coral);
                    }
                }
            }
            return true;
        }
    }

    private better112.world.noise.NormalNoise geodeNoise;
    private long geodeSeed;

    /**
     * An amethyst geode: a hollow ball of smooth basalt, calcite and amethyst, shaped by a few
     * points scattered near its middle, usually with a crack in one side. Budding amethyst in
     * the inner wall starts with crystals already grown on it.
     */
    private Thing geode(String id, JsonObject config) {
        JsonObject blocks = config.getAsJsonObject("blocks"), layers = config.getAsJsonObject("layers"), crack = config.getAsJsonObject("crack");
        final Values.State inner = Values.State.parse(blocks.get("inner_layer_provider")), budding = Values.State.parse(blocks.get("alternate_inner_layer_provider")),
                middle = Values.State.parse(blocks.get("middle_layer_provider")), outer = Values.State.parse(blocks.get("outer_layer_provider"));
        final List<IBlockState> crystals = new ArrayList<IBlockState>();
        for (JsonElement crystal : blocks.getAsJsonArray("inner_placements")) {
            IBlockState state = States.of(crystal);
            if (state != null) {
                crystals.add(state);
            }
        }
        Random probe = new Random(0L);
        if (inner.get(probe) == null || middle.get(probe) == null || outer.get(probe) == null) {
            unknown.add("feature " + id + " (no geode blocks)");
            return null;
        }
        final double filling = layers.get("filling").getAsDouble(), innerLayer = layers.get("inner_layer").getAsDouble(),
                middleLayer = layers.get("middle_layer").getAsDouble(), outerLayer = layers.get("outer_layer").getAsDouble();
        final double crackSize = crack.get("base_crack_size").getAsDouble(), crackChance = crack.get("generate_crack_chance").getAsDouble();
        final int crackOffset = crack.get("crack_point_offset").getAsInt();
        final Values.Int wall = Values.Int.parse(config.get("outer_wall_distance"), 5), count = Values.Int.parse(config.get("distribution_points"), 3),
                pointOffset = Values.Int.parse(config.get("point_offset"), 1);
        final int wallMax = config.getAsJsonObject("outer_wall_distance").get("max_inclusive").getAsInt();
        final double noiseScale = config.get("noise_multiplier").getAsDouble(), buddingChance = config.get("use_alternate_layer0_chance").getAsDouble(),
                crystalChance = config.get("use_potential_placements_chance").getAsDouble();
        final boolean crystalsNeedBudding = config.get("placements_require_layer0_alternate").getAsBoolean();
        final int tolerated = config.get("invalid_blocks_threshold").getAsInt();
        return new Thing() {
            @Override
            public boolean generate(World world, Random random, BlockPos start, Biome biome) {
                // The ball reaches from 5 below its origin to 16 beyond it; the origin is moved so that all of it lies in chunks that exist.
                int baseX = ((start.getX() - 8) >> 4) << 4, baseZ = ((start.getZ() - 8) >> 4) << 4;
                BlockPos origin = new BlockPos(Math.max(baseX + 5, Math.min(baseX + 15, start.getX())), start.getY(),
                        Math.max(baseZ + 5, Math.min(baseZ + 15, start.getZ())));
                if (geodeNoise == null || geodeSeed != world.getSeed()) {
                    geodeSeed = world.getSeed();
                    geodeNoise = new better112.world.noise.NormalNoise(new better112.world.noise.Xoroshiro(geodeSeed).fork().named("better112:geode"), -4,
                            new double[] {1.0D});
                }
                int points = count.get(random);
                double spread = points / (double) wallMax;
                double fillingAt = 1.0D / Math.sqrt(filling), innerAt = 1.0D / Math.sqrt(innerLayer + spread), middleAt = 1.0D / Math.sqrt(middleLayer + spread),
                        outerAt = 1.0D / Math.sqrt(outerLayer + spread);
                double crackAt = 1.0D / Math.sqrt(crackSize + random.nextDouble() / 2.0D + (points > 3 ? spread : 0.0D));
                boolean cracked = random.nextFloat() < crackChance;
                int[][] centers = new int[points][];
                int bad = 0;
                for (int i = 0; i < points; i++) {
                    BlockPos at = origin.add(wall.get(random), wall.get(random), wall.get(random));
                    IBlockState there = world.isBlockLoaded(at) && at.getY() < 255 ? world.getBlockState(at) : Blocks.AIR.getDefaultState();
                    Material material = there.getMaterial();
                    // A geode forms in rock: not hanging in a cave, not in water, lava or ice.
                    if ((material == Material.AIR || material.isLiquid() || material == Material.ICE || material == Material.PACKED_ICE || there.getBlock() == Blocks.BEDROCK)
                            && ++bad > tolerated) {
                        return false;
                    }
                    centers[i] = new int[] {at.getX(), at.getY(), at.getZ(), pointOffset.get(random)};
                }
                int[][] cracks = new int[0][];
                if (cracked) {
                    int side = random.nextInt(4), off = points * 2 + 1;
                    int cx = side == 0 || side == 2 ? off : 0, cz = side == 1 || side == 2 ? off : 0;
                    cracks = new int[][] {{origin.getX() + cx, origin.getY() + 7, origin.getZ() + cz}, {origin.getX() + cx, origin.getY() + 5, origin.getZ() + cz},
                        {origin.getX() + cx, origin.getY() + 1, origin.getZ() + cz}};
                }
                List<BlockPos> buds = new ArrayList<BlockPos>();
                BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
                for (int x = origin.getX() - 5; x <= origin.getX() + 16; x++) {
                    for (int z = origin.getZ() - 5; z <= origin.getZ() + 16; z++) {
                        if (!world.isBlockLoaded(at.setPos(x, 0, z))) {
                            continue;
                        }
                        for (int y = Math.max(1, origin.getY() - 6); y <= Math.min(254, origin.getY() + 16); y++) {
                            double sum = 0.0D;
                            for (int[] c : centers) {
                                double dx = x - c[0], dy = y - c[1], dz = z - c[2];
                                sum += 1.0D / Math.sqrt(dx * dx + dy * dy + dz * dz + c[3]);
                            }
                            // Far from every point: no need for the noise to know this is outside.
                            if (sum < outerAt - 0.2D) {
                                continue;
                            }
                            double noise = geodeNoise.sample(x, y, z) * noiseScale;
                            sum += noise * centers.length;
                            if (sum < outerAt) {
                                continue;
                            }
                            at.setPos(x, y, z);
                            IBlockState there = world.getBlockState(at);
                            if (there.getBlock() == Blocks.BEDROCK || there.getBlock().hasTileEntity(there) || there.getBlockHardness(world, at) < 0.0F) {
                                continue;
                            }
                            double crackSum = 0.0D;
                            for (int[] c : cracks) {
                                double dx = x - c[0], dy = y - c[1], dz = z - c[2];
                                crackSum += 1.0D / Math.sqrt(dx * dx + dy * dy + dz * dz + crackOffset) + noise;
                            }
                            if (cracked && crackSum >= crackAt && sum < fillingAt) {
                                world.setBlockState(at, Blocks.AIR.getDefaultState(), FLAGS);
                            } else if (sum >= fillingAt) {
                                world.setBlockState(at, Blocks.AIR.getDefaultState(), FLAGS);
                            } else if (sum >= innerAt) {
                                IBlockState bud = random.nextFloat() < buddingChance ? budding.get(random) : null;
                                world.setBlockState(at, bud != null ? bud : inner.get(random), FLAGS);
                                if ((!crystalsNeedBudding || bud != null) && random.nextFloat() < crystalChance) {
                                    buds.add(at.toImmutable());
                                }
                            } else if (sum >= middleAt) {
                                world.setBlockState(at, middle.get(random), FLAGS);
                            } else {
                                world.setBlockState(at, outer.get(random), FLAGS);
                            }
                        }
                    }
                }
                for (BlockPos bud : buds) {
                    if (crystals.isEmpty()) {
                        break;
                    }
                    IBlockState crystal = crystals.get(random.nextInt(crystals.size()));
                    for (EnumFacing side : EnumFacing.VALUES) {
                        BlockPos next = bud.offset(side);
                        if (world.isBlockLoaded(next) && world.isAirBlock(next)) {
                            world.setBlockState(next, States.with(crystal, "facing", side.getName()), FLAGS);
                            break;
                        }
                    }
                }
                return true;
            }
        };
    }

    private static boolean dripstoneBase(World world, BlockPos pos, IBlockState dripstone) {
        if (pos.getY() < 1 || pos.getY() > 254 || !world.isBlockLoaded(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        return state.getBlock() == dripstone.getBlock() || isBaseStone(state);
    }

    private static boolean toDripstone(World world, BlockPos pos, IBlockState dripstone) {
        if (pos.getY() < 1 || pos.getY() > 254 || !world.isBlockLoaded(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() == dripstone.getBlock()) {
            return true;
        }
        if (!isBaseStone(state)) {
            return false;
        }
        world.setBlockState(pos, dripstone, FLAGS);
        return true;
    }

    /** A stalactite (way down) or stalagmite (way up) of so many pieces, starting at "from" and stopping at anything in its way. */
    private static void growDripstone(World world, BlockPos from, EnumFacing way, int pieces, IBlockState spike) {
        IBlockState state = States.with(spike, "vertical_direction", way == EnumFacing.UP ? "up" : "down");
        for (int i = 0; i < pieces; i++) {
            BlockPos at = from.offset(way, i);
            if (at.getY() < 1 || at.getY() > 254 || !world.isAirBlock(at)) {
                return;
            }
            world.setBlockState(at, state, FLAGS);
        }
    }

    /**
     * Runs one of 1.12.2's tree generators, which only grow on dirt and grass. The modern game
     * puts dirt under a trunk wherever a tree may stand (on the snow of a grove, on moss), so
     * the ground is turned to dirt first, and put back if no tree comes of it.
     */
    static boolean onSoil(World world, BlockPos pos, net.minecraft.world.gen.feature.WorldGenerator tree, Random random) {
        BlockPos below = pos.down();
        IBlockState ground = world.getBlockState(below);
        Material material = ground.getMaterial();
        boolean swap = ground.getBlock() != Blocks.DIRT && ground.getBlock() != Blocks.GRASS
                && (material == Material.CRAFTED_SNOW || material == Material.GRASS || material == Material.GROUND);
        if (swap) {
            world.setBlockState(below, Blocks.DIRT.getDefaultState(), FLAGS);
        }
        boolean grown = tree.generate(world, random, pos);
        if (swap && !grown) {
            world.setBlockState(below, ground, FLAGS);
        }
        return grown;
    }

    private static boolean flag(JsonObject json, String key) {
        return json.has(key) && json.get(key).getAsBoolean();
    }

    /**
     * Plain underground rock: stone and its kinds, and the rock other mods put in stone's
     * place (Underground Biomes swaps all stone for its own). Ores are not counted.
     */
    static boolean isBaseStone(IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.STONE) {
            return true;
        }
        if (state.getMaterial() != Material.ROCK || !state.isFullCube() || block.getRegistryName() == null) {
            return false;
        }
        String path = block.getRegistryName().getPath();
        if (block.getRegistryName().getNamespace().equals("better112")) {
            return path.equals("mc_deepslate") || path.equals("mc_tuff") || path.equals("mc_calcite") || path.equals("mc_dripstone_block");
        }
        return !block.getRegistryName().getNamespace().equals("minecraft") && !path.contains("ore") && !path.contains("brick")
                && (path.contains("stone") || path.contains("rock"));
    }

    private static int clamp(int offset) {
        return Math.max(-7, Math.min(7, offset));
    }

    /** Puts one block down the way a patch does: plants only where they can stand, tall plants with both halves. */
    private static boolean set(World world, BlockPos pos, IBlockState state) {
        if (state == null || state.getBlock() == Blocks.AIR || pos.getY() < 1 || pos.getY() > 253) {
            return false;
        }
        Block block = state.getBlock();
        if (block == Blocks.DOUBLE_PLANT) {
            if (world.isAirBlock(pos) && Blocks.DOUBLE_PLANT.canPlaceBlockAt(world, pos)) {
                Blocks.DOUBLE_PLANT.placeAt(world, pos, state.getValue(BlockDoublePlant.VARIANT), FLAGS);
                return true;
            }
            return false;
        }
        if (ModernContent.isTallPlant(block)) {
            return ModernContent.placeTall(world, pos, block, FLAGS);
        }
        if (ModernContent.isAquatic(block)) {
            return ModernContent.placeAquatic(world, pos, state, FLAGS);
        }
        if (block instanceof BlockBush || block == Blocks.REEDS || block == Blocks.CACTUS) {
            if (world.isAirBlock(pos) && canStay(world, pos, state)) {
                world.setBlockState(pos, state, FLAGS);
                return true;
            }
            return false;
        }
        IBlockState there = world.getBlockState(pos);
        if (there.getBlock().isReplaceable(world, pos)) {
            world.setBlockState(pos, state, FLAGS);
            return true;
        }
        return false;
    }
}
