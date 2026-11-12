package better112.world.gen;

import java.util.Random;

import better112.world.ModernTerrain;
import better112.world.feature.States;
import better112.world.noise.NormalNoise;

import net.minecraft.block.BlockStone;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;

/**
 * The ground of the modern biomes that are not grass over dirt: snow and ice on the peaks
 * with bare stone on their steep faces, powder snow in drifts, bands of calcite, mud, sandy
 * sea floors. The modern game writes these as "surface rules" in code; the handful that
 * matter for the biomes brought over are written out again here, each with the same noise
 * and the same numbers.
 */
final class Surfaces {

    private Surfaces() {}

    enum Kind {
        FROZEN_PEAKS, JAGGED_PEAKS, SNOWY_SLOPES, GROVE, STONY_PEAKS, MANGROVE, SANDY, SEA_SAND, SEA_GRAVEL, FROZEN_SEA
    }

    private static final IBlockState STONE = Blocks.STONE.getDefaultState(), SNOW = Blocks.SNOW.getDefaultState(),
            PACKED_ICE = Blocks.PACKED_ICE.getDefaultState(), ICE = Blocks.ICE.getDefaultState(), DIRT = Blocks.DIRT.getDefaultState(),
            SAND = Blocks.SAND.getDefaultState(), GRAVEL = Blocks.GRAVEL.getDefaultState(), WATER = Blocks.WATER.getDefaultState();
    private static IBlockState powder, calcite, mud;

    /** Heights of the chunk being surfaced, for "is this a steep face"; worked out once per chunk. */
    private static ChunkPrimer measured;
    private static final int[] HEIGHTS = new int[256];

    /** The world's noises, looked up once per world. */
    private static ModernTerrain noisesOf;
    private static NormalNoise powderNoise, packedNoise, iceNoise, calciteNoise, swampNoise, bergSurface, bergPillar, bergRoof;
    /** Where a frozen sea is frozen: the old game's own kind of noise, as the modern rule is written for it. */
    private static final net.minecraft.world.gen.NoiseGeneratorPerlin FROZEN = new net.minecraft.world.gen.NoiseGeneratorPerlin(new Random(3456L), 3),
            INFO = new net.minecraft.world.gen.NoiseGeneratorPerlin(new Random(2345L), 1);

    private static void blocks() {
        if (powder == null) {
            powder = orElse(States.of("minecraft:powder_snow", null), SNOW);
            calcite = orElse(States.of("minecraft:calcite", null), STONE.withProperty(BlockStone.VARIANT, BlockStone.EnumType.DIORITE));
            mud = orElse(States.of("minecraft:mud", null), DIRT);
        }
    }

    private static IBlockState orElse(IBlockState state, IBlockState fallback) {
        return state == null ? fallback : state;
    }

    private static void noises(World world) {
        ModernTerrain terrain = world.getBiomeProvider() instanceof ModernBiomeProvider ? ((ModernBiomeProvider) world.getBiomeProvider()).terrain : null;
        if (terrain == noisesOf) {
            return;
        }
        noisesOf = terrain;
        powderNoise = noise(terrain, "minecraft:powder_snow");
        packedNoise = noise(terrain, "minecraft:packed_ice");
        iceNoise = noise(terrain, "minecraft:ice");
        calciteNoise = noise(terrain, "minecraft:calcite");
        swampNoise = noise(terrain, "minecraft:surface_swamp");
        bergSurface = noise(terrain, "minecraft:iceberg_surface");
        bergPillar = noise(terrain, "minecraft:iceberg_pillar");
        bergRoof = noise(terrain, "minecraft:iceberg_pillar_roof");
    }

    /** True where a frozen sea is below freezing: about half of it, in broad patches with a ragged edge. */
    private static boolean frozen(int x, int z) {
        double broad = FROZEN.getValue(x * 0.05D, z * 0.05D), fine = INFO.getValue(x * 0.2D, z * 0.2D);
        return broad + fine >= 0.3D || INFO.getValue(x * 0.09D, z * 0.09D) >= 0.8D;
    }

    /**
     * Icebergs, as the modern game raises them: where two noises agree, packed ice fills the
     * water from some way below the surface and stands up out of it, with snow on the tallest.
     */
    private static void iceberg(ChunkPrimer primer, int lx, int lz, int x, int z, int sea, boolean melting, long seed) {
        if (bergSurface == null || bergPillar == null || bergRoof == null) {
            return;
        }
        double strength = Math.min(Math.abs(bergSurface.sample(x, 0.0D, z) * 8.25D), bergPillar.sample(x * 1.28D, 0.0D, z * 1.28D) * 15.0D);
        if (strength <= 1.8D) {
            return;
        }
        double roof = Math.abs(bergRoof.sample(x * 1.17D, 0.0D, z * 1.17D) * 1.5D);
        double above = Math.min(strength * strength * 1.2D, Math.ceil(roof * 40.0D) + 14.0D);
        if (melting) {
            above -= 2.0D;
        }
        if (above <= 2.0D) {
            return;
        }
        int bottom = (int) (sea - above - 7.0D), top = (int) (above + sea);
        Random random = new Random(seed ^ x * 341873128712L ^ z * 132897987541L);
        int snowDepth = 2 + random.nextInt(4), snowFrom = sea + 18 + random.nextInt(10), snow = 0;
        for (int y = Math.min(254, top); y > Math.max(1, bottom); y--) {
            Material material = primer.getBlockState(lx, y, lz).getMaterial();
            boolean fill = material == Material.AIR ? y < top && random.nextDouble() > 0.01D
                    : material == Material.WATER && y < sea && random.nextDouble() > 0.15D;
            if (!fill) {
                continue;
            }
            if (snow <= snowDepth && y > snowFrom) {
                primer.setBlockState(lx, y, lz, SNOW);
                snow++;
            } else {
                primer.setBlockState(lx, y, lz, PACKED_ICE);
            }
        }
    }

    private static NormalNoise noise(ModernTerrain terrain, String id) {
        try {
            return terrain == null ? null : terrain.noise(id);
        } catch (RuntimeException e) {
            return null; // an older game version without this noise: no patches of that kind
        }
    }

    private static boolean within(NormalNoise noise, int x, int z, double min, double max) {
        if (noise == null) {
            return false;
        }
        double value = noise.sample(x, 0.0D, z);
        return value >= min && value <= max;
    }

    private static int height(ChunkPrimer primer, int lx, int lz) {
        if (measured != primer) {
            measured = primer;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int y = 255;
                    while (y > 0) {
                        Material material = primer.getBlockState(x, y, z).getMaterial();
                        if (material != Material.AIR && !material.isLiquid()) {
                            break;
                        }
                        y--;
                    }
                    HEIGHTS[x << 4 | z] = y;
                }
            }
        }
        return HEIGHTS[lx << 4 | lz];
    }

    /** The modern game's test for a steep face: the ground rises four or more to the south or to the west. */
    private static boolean steep(ChunkPrimer primer, int lx, int lz) {
        int north = height(primer, lx, Math.max(lz - 1, 0)), south = height(primer, lx, Math.min(lz + 1, 15));
        if (south >= north + 4) {
            return true;
        }
        int west = height(primer, Math.max(lx - 1, 0), lz), east = height(primer, Math.min(lx + 1, 15), lz);
        return west >= east + 4;
    }

    /**
     * Lays the ground of one column. The arguments are the ones 1.12.2 hands a biome, with its
     * quirk kept: "x" and "z" arrive swapped within the chunk.
     */
    static void lay(Kind kind, Biome biome, World world, Random random, ChunkPrimer primer, int x, int z, double noiseVal) {
        blocks();
        noises(world);
        int lx = z & 15, lz = x & 15;
        // The true block position, for the noises: chunk origin plus the unswapped offsets.
        int bx = (x & ~15) + lx, bz = (z & ~15) + lz;
        IBlockState top, filler;
        switch (kind) {
            case FROZEN_PEAKS:
                if (steep(primer, lx, lz)) {
                    top = filler = PACKED_ICE;
                } else {
                    top = within(packedNoise, bx, bz, 0.0D, 0.2D) ? PACKED_ICE : within(iceNoise, bx, bz, 0.0D, 0.025D) ? ICE : SNOW;
                    filler = within(packedNoise, bx, bz, -0.5D, 0.2D) ? PACKED_ICE : within(iceNoise, bx, bz, -0.0625D, 0.025D) ? ICE : SNOW;
                }
                break;
            case JAGGED_PEAKS:
                top = steep(primer, lx, lz) ? STONE : SNOW;
                filler = STONE;
                break;
            case SNOWY_SLOPES:
                if (steep(primer, lx, lz)) {
                    top = filler = STONE;
                } else {
                    top = within(powderNoise, bx, bz, 0.35D, 0.6D) ? powder : SNOW;
                    filler = within(powderNoise, bx, bz, 0.45D, 0.58D) ? powder : SNOW;
                }
                break;
            case GROVE:
                top = within(powderNoise, bx, bz, 0.35D, 0.6D) ? powder : SNOW;
                filler = within(powderNoise, bx, bz, 0.45D, 0.58D) ? powder : DIRT;
                break;
            case STONY_PEAKS:
                top = filler = within(calciteNoise, bx, bz, -0.0125D, 0.0125D) ? calcite : STONE;
                break;
            case MANGROVE:
                top = filler = mud;
                // Ground at the waterline sinks a block wherever the swamp noise says so: the shallows and pools of a swamp.
                int ground = height(primer, lx, lz);
                if (ground >= 60 && ground <= 62 && swampNoise != null && swampNoise.sample(bx, 0.0D, bz) >= 0.0D) {
                    primer.setBlockState(lx, ground, lz, WATER);
                }
                break;
            case SANDY:
                top = filler = SAND;
                break;
            default:
                top = biome.topBlock;
                filler = biome.fillerBlock;
                break;
        }
        IBlockState keepTop = biome.topBlock, keepFiller = biome.fillerBlock;
        biome.topBlock = top;
        biome.fillerBlock = filler;
        biome.generateBiomeTerrain(world, random, primer, x, z, noiseVal);
        biome.topBlock = keepTop;
        biome.fillerBlock = keepFiller;
        if (kind == Kind.SEA_SAND || kind == Kind.SEA_GRAVEL || kind == Kind.SANDY || kind == Kind.FROZEN_SEA) {
            // The old game floors every sea with dirt in the shallows and gravel in the deep; modern warm seas are sand, cold ones gravel.
            IBlockState floor = kind == Kind.SEA_GRAVEL || kind == Kind.FROZEN_SEA ? GRAVEL : SAND;
            int y = world.getSeaLevel() - 1;
            if (primer.getBlockState(lx, y, lz).getMaterial() == Material.WATER) {
                while (y > 1 && primer.getBlockState(lx, y, lz).getMaterial() == Material.WATER) {
                    y--;
                }
                for (int depth = 0; depth < 3 && y - depth > 1; depth++) {
                    IBlockState at = primer.getBlockState(lx, y - depth, lz);
                    if (at.getBlock() == Blocks.DIRT || at.getBlock() == Blocks.GRAVEL || at.getBlock() == Blocks.GRASS
                            || (depth == 0 && at.getBlock() == Blocks.STONE)) {
                        primer.setBlockState(lx, y - depth, lz, floor);
                    }
                }
            }
        }
        if (kind == Kind.FROZEN_SEA) {
            boolean cold = frozen(bx, bz);
            boolean ices = cold && biome instanceof ImportedBiomes.ModernBiome && ((ImportedBiomes.ModernBiome) biome).icePatches;
            int sea = world.getSeaLevel();
            if (ices && primer.getBlockState(lx, sea - 1, lz).getMaterial() == Material.WATER) {
                primer.setBlockState(lx, sea - 1, lz, ICE);
            }
            iceberg(primer, lx, lz, bx, bz, sea, !ices, world.getSeed());
        }
    }
}
