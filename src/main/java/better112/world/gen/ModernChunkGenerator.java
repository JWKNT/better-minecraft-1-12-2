package better112.world.gen;


import better112.world.ModernTerrain;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.ChunkGeneratorOverworld;

/**
 * The 1.12.2 overworld generator with its terrain swapped for the modern shape. Everything
 * after the raw stone is still the game's own: biomes put down their grass and sand, mods
 * add their ores, structures and trees through the usual Forge hooks.
 *
 * Caves here are part of the terrain itself, as in modern versions, so the old tunnel
 * carver and the old surface lakes are switched off for this world type.
 */
public final class ModernChunkGenerator extends ChunkGeneratorOverworld {

    /** Old cave tunnels off (the terrain has its own caves), no water lakes, lava lakes rare: as in 1.18+. */
    private static final String SETTINGS = "{\"useCaves\":false,\"useWaterLakes\":false,\"lavaLakeChance\":200}";

    private static final IBlockState STONE = Blocks.STONE.getDefaultState();
    private static final IBlockState WATER = Blocks.WATER.getDefaultState();
    private static final IBlockState LAVA = Blocks.LAVA.getDefaultState();
    private static final IBlockState AIR = Blocks.AIR.getDefaultState();
    private static final int LAVA_LEVEL = 10;

    private static final byte CAVE_AIR = 1, CAVE_WATER = 2, CAVE_LAVA = 3;

    private final ModernTerrain terrain;
    private final World world;
    /** Caves of the chunk being made: hidden while biomes lay their surface, cut out afterwards. */
    private final byte[] caves = new byte[16 * 16 * 256];
    private boolean hasCaves;
    /** Keyed by the packed corner position, without boxing a Long for every lookup. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> corners = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]>();

    public ModernChunkGenerator(World world, long seed, boolean structures, ModernTerrain terrain) {
        super(world, seed, structures, SETTINGS);
        this.terrain = terrain;
        this.world = world;
    }

    @Override
    public void setBlocksInChunk(int chunkX, int chunkZ, ChunkPrimer primer) {
        long start = System.nanoTime();
        synchronized (terrain) {
            shape(chunkX, chunkZ, primer);
        }
        shapeNanos += System.nanoTime() - start;
        shapedChunks++;
    }

    /** Time spent shaping terrain, for the self-test and the log. */
    public static long shapeNanos;
    public static int shapedChunks;

    private void shape(int chunkX, int chunkZ, ChunkPrimer primer) {
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        int sea = terrain.seaLevel;
        // Rough surface height and "is this under the sea" at the four corners of the chunk.
        int[] c00 = corner(x0, z0), c10 = corner(x0 + 16, z0), c01 = corner(x0, z0 + 16), c11 = corner(x0 + 16, z0 + 16);
        terrain.startChunk(chunkX, chunkZ, 256);
        java.util.Arrays.fill(caves, (byte) 0);
        hasCaves = false;
        for (int lx = 0; lx < 16; lx++) {
            float fx = lx / 16.0F;
            for (int lz = 0; lz < 16; lz++) {
                float fz = lz / 16.0F;
                float surface = lerp2(fx, fz, c00[0], c10[0], c01[0], c11[0]);
                float wet = lerp2(fx, fz, c00[1], c10[1], c01[1], c11[1]);
                // Below this, an opening is a cave; above it, it is the land's own shape.
                int caveTop = (int) Math.floor(surface) - 5;
                int x = x0 + lx, z = z0 + lz;
                int base = (lx * 16 + lz) << 8;
                // Set when open water sits right on top of a cave: the cave's first blocks stay stone.
                boolean plug = false;
                for (int y = 255; y >= 0; y--) {
                    if (terrain.density(x, y, z) > 0.0D) {
                        primer.setBlockState(lx, y, lz, STONE);
                    } else if (y >= caveTop) {
                        if (y < sea) {
                            primer.setBlockState(lx, y, lz, WATER);
                            plug = y == caveTop;
                        }
                    } else {
                        primer.setBlockState(lx, y, lz, STONE);
                        if (y >= 5 && !(plug && y >= caveTop - 3)) {
                            byte kind = caveKind(y, surface, wet);
                            if (kind != 0) {
                                caves[base + y] = kind;
                                hasCaves = true;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * What fills a cave here. Caves under the sea are flooded for a few dozen blocks down, as
     * the modern game's aquifers make them; where flooded and dry caves would meet, the cave
     * is closed with stone so that water never stands against air.
     */
    private static byte caveKind(int y, float surface, float wet) {
        if (wet > 0.65F) {
            float depth = surface - y;
            if (depth < 36.0F) {
                return y < 63 ? CAVE_WATER : CAVE_AIR;
            }
            if (depth < 40.0F) {
                return 0;
            }
        } else if (wet > 0.35F) {
            return 0;
        }
        return y <= LAVA_LEVEL ? CAVE_LAVA : CAVE_AIR;
    }

    @Override
    public void replaceBiomeBlocks(int chunkX, int chunkZ, ChunkPrimer primer, Biome[] biomes) {
        super.replaceBiomeBlocks(chunkX, chunkZ, primer, biomes);
        if (!hasCaves) {
            return;
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int base = (lx * 16 + lz) << 8;
                for (int y = 5; y < 256; y++) {
                    byte kind = caves[base + y];
                    if (kind != 0 && primer.getBlockState(lx, y, lz).getBlock() != Blocks.BEDROCK) {
                        primer.setBlockState(lx, y, lz, kind == CAVE_AIR ? AIR : kind == CAVE_WATER ? WATER : LAVA);
                    }
                }
            }
        }
        hasCaves = false;
    }

    @Override
    public void populate(int chunkX, int chunkZ) {
        super.populate(chunkX, chunkZ);
        CaveDecor.decorate(world, terrain, chunkX, chunkZ);
    }

    /** {rough surface height, 1 if the sea covers this spot or one a chunk away, else 0} at a chunk corner. */
    private int[] corner(int x, int z) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        int[] value = corners.get(key);
        if (value == null) {
            if (corners.size() > 4096) {
                corners.clear();
            }
            int here = terrain.roughSurface(x, z);
            int lowest = here;
            lowest = Math.min(lowest, terrain.roughSurface(x - 16, z));
            lowest = Math.min(lowest, terrain.roughSurface(x + 16, z));
            lowest = Math.min(lowest, terrain.roughSurface(x, z - 16));
            lowest = Math.min(lowest, terrain.roughSurface(x, z + 16));
            value = new int[] {here, lowest + 8 < terrain.seaLevel ? 1 : 0};
            corners.put(key, value);
        }
        return value;
    }

    private static float lerp2(float fx, float fz, float v00, float v10, float v01, float v11) {
        float a = v00 + fx * (v10 - v00), b = v01 + fx * (v11 - v01);
        return a + fz * (b - a);
    }
}
