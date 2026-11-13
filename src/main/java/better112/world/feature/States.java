package better112.world.feature;

import java.util.HashMap;
import java.util.Map;


import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockNewLeaf;
import net.minecraft.block.BlockNewLog;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.BlockSand;
import net.minecraft.block.BlockStone;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;

/**
 * Modern block states, as worldgen files write them ({"Name": "minecraft:oak_log",
 * "Properties": {"axis": "y"}}), turned into the 1.12.2 block that is the same thing.
 * Modern Minecraft gave every variant its own name; 1.12.2 keeps them as variants of a few
 * blocks. A block with no counterpart here comes back as null and is simply not placed.
 */
public final class States {

    private States() {}

    private static final Map<String, IBlockState> SIMPLE = new HashMap<String, IBlockState>();
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    static {
        IBlockState dirt = Blocks.DIRT.getDefaultState();
        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState grass = Blocks.TALLGRASS.getDefaultState();
        IBlockState flower = Blocks.RED_FLOWER.getDefaultState();
        Object[] table = {
            "air", Blocks.AIR.getDefaultState(), "cave_air", Blocks.AIR.getDefaultState(),
            "grass_block", Blocks.GRASS.getDefaultState(), "dirt", dirt, "rooted_dirt", dirt, "mud", dirt,
            "coarse_dirt", dirt.withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.COARSE_DIRT),
            "podzol", dirt.withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.PODZOL),
            "mycelium", Blocks.MYCELIUM.getDefaultState(), "farmland", Blocks.FARMLAND.getDefaultState(),
            "stone", stone, "granite", stone.withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE),
            "diorite", stone.withProperty(BlockStone.VARIANT, BlockStone.EnumType.DIORITE),
            "andesite", stone.withProperty(BlockStone.VARIANT, BlockStone.EnumType.ANDESITE),
            "cobblestone", Blocks.COBBLESTONE.getDefaultState(), "mossy_cobblestone", Blocks.MOSSY_COBBLESTONE.getDefaultState(),
            "gravel", Blocks.GRAVEL.getDefaultState(), "sand", Blocks.SAND.getDefaultState(),
            "red_sand", Blocks.SAND.getDefaultState().withProperty(BlockSand.VARIANT, BlockSand.EnumType.RED_SAND),
            "sandstone", Blocks.SANDSTONE.getDefaultState(), "red_sandstone", Blocks.RED_SANDSTONE.getDefaultState(),
            "clay", Blocks.CLAY.getDefaultState(), "terracotta", Blocks.HARDENED_CLAY.getDefaultState(),
            "snow_block", Blocks.SNOW.getDefaultState(), "snow", Blocks.SNOW_LAYER.getDefaultState(),
            "powder_snow", Blocks.SNOW.getDefaultState(), "ice", Blocks.ICE.getDefaultState(), "packed_ice", Blocks.PACKED_ICE.getDefaultState(),
            "water", Blocks.WATER.getDefaultState(), "lava", Blocks.LAVA.getDefaultState(),
            "netherrack", Blocks.NETHERRACK.getDefaultState(), "soul_sand", Blocks.SOUL_SAND.getDefaultState(),
            "glowstone", Blocks.GLOWSTONE.getDefaultState(), "obsidian", Blocks.OBSIDIAN.getDefaultState(),
            "magma_block", Blocks.MAGMA.getDefaultState(), "end_stone", Blocks.END_STONE.getDefaultState(),
            "hay_block", Blocks.HAY_BLOCK.getDefaultState(), "bone_block", Blocks.BONE_BLOCK.getDefaultState(),
            "cobweb", Blocks.WEB.getDefaultState(), "pumpkin", Blocks.PUMPKIN.getDefaultState(), "melon", Blocks.MELON_BLOCK.getDefaultState(),
            "grass", grass.withProperty(BlockTallGrass.TYPE, BlockTallGrass.EnumType.GRASS),
            "short_grass", grass.withProperty(BlockTallGrass.TYPE, BlockTallGrass.EnumType.GRASS),
            "fern", grass.withProperty(BlockTallGrass.TYPE, BlockTallGrass.EnumType.FERN),
            "dead_bush", Blocks.DEADBUSH.getDefaultState(),
            "dandelion", Blocks.YELLOW_FLOWER.getDefaultState(),
            "poppy", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.POPPY),
            "blue_orchid", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.BLUE_ORCHID),
            "allium", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.ALLIUM),
            "azure_bluet", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.HOUSTONIA),
            "red_tulip", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.RED_TULIP),
            "orange_tulip", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.ORANGE_TULIP),
            "white_tulip", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.WHITE_TULIP),
            "pink_tulip", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.PINK_TULIP),
            "oxeye_daisy", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.OXEYE_DAISY),
            // Flowers 1.12.2 does not have: the nearest in colour.
            "cornflower", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.BLUE_ORCHID),
            "lily_of_the_valley", flower.withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.WHITE_TULIP),
            "brown_mushroom", Blocks.BROWN_MUSHROOM.getDefaultState(), "red_mushroom", Blocks.RED_MUSHROOM.getDefaultState(),
            "sugar_cane", Blocks.REEDS.getDefaultState(), "cactus", Blocks.CACTUS.getDefaultState(),
            "lily_pad", Blocks.WATERLILY.getDefaultState(), "vine", Blocks.VINE.getDefaultState(),
            "oak_planks", Blocks.PLANKS.getDefaultState(),
            "packed_mud", dirt.withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.COARSE_DIRT),
            "calcite", stone.withProperty(BlockStone.VARIANT, BlockStone.EnumType.DIORITE),
            "tuff", stone.withProperty(BlockStone.VARIANT, BlockStone.EnumType.ANDESITE), "deepslate", stone,
            "blue_ice", Blocks.PACKED_ICE.getDefaultState(),
        };
        for (int i = 0; i < table.length; i += 2) {
            SIMPLE.put((String) table[i], (IBlockState) table[i + 1]);
        }
    }

    /** A {"Name": ..., "Properties": {...}} object as a 1.12.2 state, or null. */
    public static IBlockState of(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return null;
        }
        JsonObject o = json.getAsJsonObject();
        JsonObject properties = o.has("Properties") ? o.getAsJsonObject("Properties") : null;
        return of(o.get("Name").getAsString(), properties);
    }

    public static IBlockState of(String id, JsonObject properties) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon), name = colon < 0 ? id : id.substring(colon + 1);
        // A block brought over from the modern game or a modern mod: itself, not a look-alike.
        Block imported = Imported.block(id);
        if (imported != null || !namespace.equals("minecraft")) {
            return imported == null ? null : withProperties(imported.getDefaultState(), properties);
        }
        IBlockState simple = SIMPLE.get(name);
        if (simple != null) {
            return simple;
        }
        String half = properties != null && properties.has("half") ? properties.get("half").getAsString() : "lower";
        BlockDoublePlant.EnumPlantType tall = name.equals("tall_grass") ? BlockDoublePlant.EnumPlantType.GRASS
                : name.equals("large_fern") ? BlockDoublePlant.EnumPlantType.FERN : name.equals("sunflower") ? BlockDoublePlant.EnumPlantType.SUNFLOWER
                : name.equals("lilac") ? BlockDoublePlant.EnumPlantType.SYRINGA : name.equals("rose_bush") ? BlockDoublePlant.EnumPlantType.ROSE
                : name.equals("peony") ? BlockDoublePlant.EnumPlantType.PAEONIA : null;
        if (tall != null) {
            return Blocks.DOUBLE_PLANT.getDefaultState().withProperty(BlockDoublePlant.VARIANT, tall).withProperty(BlockDoublePlant.HALF,
                    half.equals("upper") ? BlockDoublePlant.EnumBlockHalf.UPPER : BlockDoublePlant.EnumBlockHalf.LOWER);
        }
        // Woods this version lacks, as the nearest it has: cherry's dark bark is dark oak, mangrove is jungle.
        if (name.contains("cherry_") && !name.endsWith("_leaves")) {
            name = name.replace("cherry_", "dark_oak_");
        } else if (name.contains("mangrove_")) {
            name = name.replace("mangrove_", "jungle_");
        }
        for (int i = 0; i < WOODS.length; i++) {
            String wood = WOODS[i];
            if (!name.startsWith(wood + "_") && !name.equals("stripped_" + wood + "_log") && !name.equals("stripped_" + wood + "_wood")) {
                continue;
            }
            BlockPlanks.EnumType type = BlockPlanks.EnumType.values()[i];
            if (name.endsWith("_leaves")) {
                return i < 4
                        ? Blocks.LEAVES.getDefaultState().withProperty(BlockOldLeaf.VARIANT, type).withProperty(BlockLeaves.CHECK_DECAY, Boolean.FALSE)
                        : Blocks.LEAVES2.getDefaultState().withProperty(BlockNewLeaf.VARIANT, type).withProperty(BlockLeaves.CHECK_DECAY, Boolean.FALSE);
            }
            if (name.endsWith("_log") || name.endsWith("_wood")) {
                String axis = properties != null && properties.has("axis") ? properties.get("axis").getAsString() : "y";
                BlockLog.EnumAxis logAxis = name.endsWith("_wood") ? BlockLog.EnumAxis.NONE
                        : axis.equals("x") ? BlockLog.EnumAxis.X : axis.equals("z") ? BlockLog.EnumAxis.Z : BlockLog.EnumAxis.Y;
                return i < 4 ? Blocks.LOG.getDefaultState().withProperty(BlockOldLog.VARIANT, type).withProperty(BlockLog.LOG_AXIS, logAxis)
                        : Blocks.LOG2.getDefaultState().withProperty(BlockNewLog.VARIANT, type).withProperty(BlockLog.LOG_AXIS, logAxis);
            }
            if (name.endsWith("_planks")) {
                return Blocks.PLANKS.getDefaultState().withProperty(BlockPlanks.VARIANT, type);
            }
        }
        return null;
    }

    /** Carries over the properties both worlds have: a log's axis, a tall plant's half, and so on. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IBlockState withProperties(IBlockState state, JsonObject properties) {
        if (state.getBlock() instanceof BlockLeaves) {
            state = state.withProperty(BlockLeaves.CHECK_DECAY, Boolean.FALSE);
        }
        if (properties == null) {
            return state;
        }
        for (IProperty property : state.getPropertyKeys()) {
            if (!properties.has(property.getName()) || property == BlockLeaves.CHECK_DECAY || property == BlockLeaves.DECAYABLE) {
                continue;
            }
            com.google.common.base.Optional value = property.parseValue(properties.get(property.getName()).getAsString());
            if (value.isPresent()) {
                state = state.withProperty(property, (Comparable) value.get());
            }
        }
        return state;
    }

    /** A state with one property set by name ("leaves" to "large"); unchanged if the block has no such property or value. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static IBlockState with(IBlockState state, String property, String value) {
        if (state == null) {
            return null;
        }
        for (IProperty each : state.getPropertyKeys()) {
            if (each.getName().equals(property)) {
                com.google.common.base.Optional parsed = each.parseValue(value);
                return parsed.isPresent() ? state.withProperty(each, (Comparable) parsed.get()) : state;
            }
        }
        return state;
    }

    /** The same log, lying along another axis. */
    public static IBlockState alongAxis(IBlockState log, EnumFacing.Axis axis) {
        if (log.getBlock() instanceof BlockLog) {
            return log.withProperty(BlockLog.LOG_AXIS, BlockLog.EnumAxis.fromFacingAxis(axis));
        }
        if (log.getBlock() instanceof BlockRotatedPillar) {
            return log.withProperty(BlockRotatedPillar.AXIS, axis);
        }
        return log;
    }
}
