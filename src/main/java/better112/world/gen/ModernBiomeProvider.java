package better112.world.gen;

import java.util.List;
import java.util.Random;

import better112.world.ModernTerrain;
import better112.world.noise.NormalNoise;
import better112.world.noise.Xoroshiro;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;

/**
 * Lays biomes out the modern way: from the climate at each spot, the same climate the
 * terrain is shaped by, so rivers lie in valleys and peaks biomes sit on actual peaks.
 */
public final class ModernBiomeProvider extends BiomeProvider {

    private static final int CACHE = 4096, MASK = CACHE - 1;

    final ModernTerrain terrain;
    private final long seed;
    private final int[] cacheX = new int[CACHE], cacheZ = new int[CACHE];
    private final Biome[] cache = new Biome[CACHE];
    private final NormalNoise variant;

    public ModernBiomeProvider(ModernTerrain terrain, long seed) {
        this.terrain = terrain;
        this.seed = seed;
        this.variant = new NormalNoise(new Xoroshiro(seed).fork().named("better112:biome_variant"), -6, new double[] {1.0D, 1.0D});
        java.util.Arrays.fill(cacheX, Integer.MIN_VALUE);
    }

    /** The biome of a 4x4 column of blocks, which is the grain the modern game decides biomes at. */
    Biome quart(int qx, int qz) {
        int slot = ((qx * 31 + qz) & MASK);
        synchronized (terrain) {
            if (cacheX[slot] != qx || cacheZ[slot] != qz || cache[slot] == null) {
                cache[slot] = ModernBiomes.choose(modernName(qx, qz), patch(qx << 2, qz << 2));
                cacheX[slot] = qx;
                cacheZ[slot] = qz;
            }
            return cache[slot];
        }
    }

    /**
     * A number from 0 to 1 that is the same across a patch of land a few hundred blocks wide
     * and unrelated in the next patch. It decides which of a climate's biomes (the vanilla
     * stand-in or one of the mod biomes) a place gets, so each shows up as a region of its
     * own. Patches are the cells around scattered points, with their borders bent by noise.
     */
    private double patch(int x, int z) {
        final double size = 320.0D;
        double wx = x + variant.sample(x / 1.5D, 0.0D, z / 1.5D) * 70.0D;
        double wz = z + variant.sample(z / 1.5D, 37.0D, x / 1.5D) * 70.0D;
        int cx = (int) Math.floor(wx / size), cz = (int) Math.floor(wz / size);
        double best = Double.MAX_VALUE;
        long bestHash = 0L;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long h = mix(seed ^ (cx + dx) * 341873128712L ^ (cz + dz) * 132897987541L);
                double px = (cx + dx + (h & 0xFFFF) / 65536.0D) * size, pz = (cz + dz + (h >> 16 & 0xFFFF) / 65536.0D) * size;
                double d = (px - wx) * (px - wx) + (pz - wz) * (pz - wz);
                if (d < best) {
                    best = d;
                    bestHash = h;
                }
            }
        }
        return (mix(bestHash) >>> 11) * 0x1.0p-53;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The modern biome's name at a block, for the self-test and debugging. */
    public String nameAt(int x, int z) {
        return modernName(x >> 2, z >> 2);
    }

    String modernName(int qx, int qz) {
        int x = qx << 2, z = qz << 2;
        synchronized (terrain) {
            return ModernBiomes.pick(terrain.temperature.at(x, 0, z), terrain.vegetation.at(x, 0, z), terrain.continents.at(x, 0, z),
                    terrain.erosion.at(x, 0, z), terrain.ridges.at(x, 0, z));
        }
    }

    @Override
    public Biome[] getBiomesForGeneration(Biome[] biomes, int x, int z, int width, int height) {
        if (biomes == null || biomes.length < width * height) {
            biomes = new Biome[width * height];
        }
        for (int dz = 0; dz < height; dz++) {
            for (int dx = 0; dx < width; dx++) {
                biomes[dx + dz * width] = quart(x + dx, z + dz);
            }
        }
        return biomes;
    }

    @Override
    public Biome[] getBiomes(Biome[] listToReuse, int x, int z, int width, int length, boolean cacheFlag) {
        if (listToReuse == null || listToReuse.length < width * length) {
            listToReuse = new Biome[width * length];
        }
        for (int dz = 0; dz < length; dz++) {
            for (int dx = 0; dx < width; dx++) {
                listToReuse[dx + dz * width] = blockBiome(x + dx, z + dz);
            }
        }
        return listToReuse;
    }

    /** The biome of one block column. Biomes are decided per 4x4 blocks; a little scatter keeps borders from being stair steps. */
    private Biome blockBiome(int bx, int bz) {
        long h = bx * 3129871L ^ bz * 116129781L ^ seed;
        h = h * h * 42317861L + h * 11L;
        int jx = (int) ((h >> 16) & 3) * 2 - 3, jz = (int) ((h >> 20) & 3) * 2 - 3;
        return quart((bx * 2 + jx) >> 3, (bz * 2 + jz) >> 3);
    }

    /**
     * The biome at a block of a chunk that is not loaded (the game asks this while making chunks, and Better Caves
     * for every block a ravine digs). The game's own answer goes through its biome cache, which fills a 16x16 table
     * from {@link #getBiomes} and reads one entry, and in Divine Journey 2 also through NoiseThreader's wrapper (two
     * boxed numbers and an array per call); this is the same entry, worked out directly.
     */
    @Override
    public Biome getBiome(BlockPos pos, Biome defaultBiome) {
        Biome biome = blockBiome(pos.getX(), pos.getZ());
        return biome == null ? defaultBiome : biome;
    }

    @Override
    public boolean areBiomesViable(int x, int z, int radius, List<Biome> allowed) {
        int x0 = (x - radius) >> 2, z0 = (z - radius) >> 2, x1 = (x + radius) >> 2, z1 = (z + radius) >> 2;
        for (int qz = z0; qz <= z1; qz++) {
            for (int qx = x0; qx <= x1; qx++) {
                if (!allowed.contains(quart(qx, qz))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public BlockPos findBiomePosition(int x, int z, int range, List<Biome> biomes, Random random) {
        int x0 = (x - range) >> 2, z0 = (z - range) >> 2, x1 = (x + range) >> 2, z1 = (z + range) >> 2;
        BlockPos found = null;
        int count = 0;
        // Every other cell is plenty for "somewhere in this biome", and four times cheaper.
        for (int qz = z0; qz <= z1; qz += 2) {
            for (int qx = x0; qx <= x1; qx += 2) {
                if (biomes.contains(quart(qx, qz))) {
                    if (found == null || random.nextInt(count + 1) == 0) {
                        found = new BlockPos(qx << 2, 0, qz << 2);
                    }
                    count++;
                }
            }
        }
        return found;
    }
}
