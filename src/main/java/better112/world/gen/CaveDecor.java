package better112.world.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import better112.Better112;
import better112.world.ModernTerrain;
import better112.world.feature.Features;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * What a modern world has whatever biome lies above, added by the generator to every chunk
 * of a Modern Terrain world. Underground: glow lichen on the rock, amethyst geodes, and
 * the two cave biomes, lush caves and dripstone caves, where the climate deep down calls
 * for them (1.12.2 keeps one biome per column, so they are not biomes here). Under water:
 * the seagrass and kelp of the oceans, rivers and swamps that stay 1.12.2's own biomes,
 * which know nothing of either.
 *
 * Where they lie is worked out as the modern game does: a spot belongs to whichever biome
 * its climate is nearest to, and the cave biomes ask for depth below the surface plus high
 * humidity (lush) or a place far inland (dripstone).
 */
final class CaveDecor {

    private CaveDecor() {}

    /** Time spent, for the self-test. */
    static long nanos;
    static int chunks, lushChunks, dripstoneChunks, geodes, seaChunks;

    private static final int NONE = 0, LUSH = 1, DRIPSTONE = 2;
    /** The cave biomes' own features, by the names the modern biome files list them under. */
    private static final String[] LUSH_FEATURES = {"lush_caves_ceiling_vegetation", "cave_vines", "lush_caves_clay", "lush_caves_vegetation",
        "rooted_azalea_tree", "spore_blossom", "classic_vines_cave_feature"};
    private static final String[] DRIPSTONE_FEATURES = {"dripstone_cluster", "pointed_dripstone"};

    private static Features.Placed lichen, geode;
    private static List<Features.Placed> lush, dripstone;
    /** The water plants a modern biome grows, by the biome's modern name. */
    private static final java.util.Map<String, List<Features.Placed>> SEA_PLANTS = new java.util.HashMap<String, List<Features.Placed>>();

    private static List<Features.Placed> load(Features reader, String[] names) {
        List<Features.Placed> list = new ArrayList<Features.Placed>();
        for (String name : names) {
            Features.Placed found = reader.placed(new com.google.gson.JsonPrimitive("minecraft:" + name));
            if (found != null && found.usable()) {
                list.add(found);
            }
        }
        return list;
    }

    /** Which cave biome a spot is in, by the modern game's nearest-climate rule cut down to the three numbers that decide it. */
    private static int kind(double humidity, double continents, double depth) {
        // Surface biomes sit at depth 0 (and again at 1, far below); cave biomes span 0.2 to 0.9.
        double surface = Math.min(Math.abs(depth), Math.abs(1.0D - depth));
        surface *= surface;
        double off = depth < 0.2D ? 0.2D - depth : depth > 0.9D ? depth - 0.9D : 0.0D;
        off *= off;
        double dry = Math.max(0.0D, 0.7D - humidity), near = Math.max(0.0D, 0.8D - continents);
        double lushFit = off + dry * dry, dripstoneFit = off + near * near;
        if (lushFit < surface && lushFit <= dripstoneFit) {
            return LUSH;
        }
        return dripstoneFit < surface ? DRIPSTONE : NONE;
    }

    /** Self-test: the nearest spot to the origin whose climate puts a cave biome ("lush" or "dripstone") under it, or null. */
    static BlockPos find(ModernTerrain terrain, String which) {
        boolean wantLush = which.startsWith("lush");
        synchronized (terrain) {
            for (int ring = 0; ring <= 120; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        int x = dx * 64, z = dz * 64;
                        double humidity = terrain.vegetation.at(x, 0, z), continents = terrain.continents.at(x, 0, z);
                        if (wantLush ? humidity > 0.75D && continents > 0.0D : continents > 0.85D && humidity < 0.5D) {
                            return new BlockPos(x, 0, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    static void decorate(World world, final ModernTerrain terrain, int chunkX, int chunkZ) {
        // Lush caves, dripstone, geodes and sea plants are made of imported blocks, which a server without the jars does not have.
        if (!better112.world.feature.Imported.present() || !better112.config.BetterConfig.importModernVanilla) {
            return;
        }
        Features reader = ImportedBiomes.features();
        if (reader == null) {
            return;
        }
        long start = System.nanoTime();
        Random random = new Random(world.getSeed() ^ chunkX * 341873128712L ^ chunkZ * 132897987541L ^ 0x6C696368656EL);
        BlockPos origin = new BlockPos((chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
        try {
            if (lush == null) {
                Features.Placed found = reader.placed(new com.google.gson.JsonPrimitive("minecraft:glow_lichen"));
                lichen = found != null && found.usable() ? found : null;
                found = reader.placed(new com.google.gson.JsonPrimitive("minecraft:amethyst_geode"));
                geode = found != null && found.usable() ? found : null;
                lush = load(reader, LUSH_FEATURES);
                dripstone = load(reader, DRIPSTONE_FEATURES);
            }
            if (geode != null && geode.place(world, random, origin, null)) {
                geodes++;
            }
            if (lichen != null) {
                lichen.place(world, random, origin, null);
            }
            if (seaPlants(world, reader, random, origin)) {
                seaChunks++;
            }
            // Could any spot under this patch of ground be a cave biome? Decided at the most favourable depth, from the corners and the middle.
            boolean maybeLush = false, maybeDripstone = false;
            synchronized (terrain) {
                for (int i = 0; i < 5; i++) {
                    int x = origin.getX() + (i == 4 ? 8 : (i & 1) * 16), z = origin.getZ() + (i == 4 ? 8 : (i >> 1) * 16);
                    double humidity = terrain.vegetation.at(x, 0, z), continents = terrain.continents.at(x, 0, z);
                    maybeLush |= humidity > 0.15D;
                    maybeDripstone |= continents > 0.25D;
                }
            }
            if (maybeLush && !lush.isEmpty()) {
                if (run(world, terrain, random, origin, lush, LUSH)) {
                    lushChunks++;
                }
            }
            if (maybeDripstone && !dripstone.isEmpty()) {
                if (run(world, terrain, random, origin, dripstone, DRIPSTONE)) {
                    dripstoneChunks++;
                }
            }
        } catch (RuntimeException e) {
            Better112.LOG.debug("Cave decoration failed at chunk {} {}: {}", chunkX, chunkZ, e.toString());
        }
        nanos += System.nanoTime() - start;
        chunks++;
    }

    /**
     * Seagrass, kelp and the like for water whose biome is one of 1.12.2's own (or another
     * mod's): the layout knows which modern biome each spot would be, and that biome's file
     * says what grows in its water. The imported biomes do this for themselves.
     */
    private static boolean seaPlants(World world, Features reader, Random random, BlockPos origin) {
        if (!(world.getBiomeProvider() instanceof ModernBiomeProvider)) {
            return false;
        }
        final ModernBiomeProvider provider = (ModernBiomeProvider) world.getBiomeProvider();
        final java.util.Map<Long, String> names = new java.util.HashMap<Long, String>();
        java.util.Set<String> here = new java.util.LinkedHashSet<String>();
        for (int dx = 0; dx <= 16; dx += 4) {
            for (int dz = 0; dz <= 16; dz += 4) {
                here.add(name(provider, names, origin.getX() + dx, origin.getZ() + dz));
            }
        }
        boolean any = false;
        for (final String modern : here) {
            List<Features.Placed> plants = SEA_PLANTS.get(modern);
            if (plants == null) {
                plants = new ArrayList<Features.Placed>();
                for (String id : reader.biomeFeatures("minecraft:" + modern, 9)) {
                    if (id.contains("seagrass") || id.contains("kelp") || id.contains("sea_pickle") || id.contains("warm_ocean_vegetation")) {
                        Features.Placed found = reader.placed(new com.google.gson.JsonPrimitive(id));
                        if (found != null && found.usable()) {
                            plants.add(found);
                        }
                    }
                }
                SEA_PLANTS.put(modern, plants);
            }
            if (plants.isEmpty()) {
                continue;
            }
            Features.Where where = new Features.Where() {
                @Override
                public boolean here(World w, BlockPos pos) {
                    return modern.equals(name(provider, names, pos.getX(), pos.getZ())) && !(w.getBiome(pos) instanceof ImportedBiomes.ModernBiome);
                }
            };
            for (Features.Placed plant : plants) {
                any |= plant.place(world, random, origin, where);
            }
        }
        return any;
    }

    private static String name(ModernBiomeProvider provider, java.util.Map<Long, String> names, int x, int z) {
        Long key = ((long) (x >> 2) << 32) | ((z >> 2) & 0xFFFFFFFFL);
        String name = names.get(key);
        if (name == null) {
            name = provider.nameAt(x, z);
            names.put(key, name);
        }
        return name;
    }

    private static boolean run(World world, final ModernTerrain terrain, Random random, final BlockPos origin, List<Features.Placed> features,
            final int wanted) {
        final boolean[] any = new boolean[1];
        // The climate changes slowly, so it is worked out once per 4x4 column and kept for this chunk: {humidity, inland, depth at y 0, depth at y 128}.
        final int span = 14;
        final double[][] climate = new double[span * span][];
        Features.Where where = new Features.Where() {
            @Override
            public boolean here(World w, BlockPos pos) {
                int qx = (pos.getX() - origin.getX() + 16) >> 2, qz = (pos.getZ() - origin.getZ() + 16) >> 2;
                double[] at = qx >= 0 && qx < span && qz >= 0 && qz < span ? climate[qx * span + qz] : null;
                if (at == null) {
                    int x = pos.getX() & ~3, z = pos.getZ() & ~3;
                    synchronized (terrain) {
                        at = new double[] {terrain.vegetation.at(x, 0, z), terrain.continents.at(x, 0, z), terrain.depth.at(x, 0, z), terrain.depth.at(x, 128, z)};
                    }
                    if (qx >= 0 && qx < span && qz >= 0 && qz < span) {
                        climate[qx * span + qz] = at;
                    }
                }
                // Depth falls evenly with height, so two samples give it at any height.
                double depth = at[2] + (at[3] - at[2]) * pos.getY() / 128.0D;
                boolean found = kind(at[0], at[1], depth) == wanted;
                any[0] |= found;
                return found;
            }
        };
        for (Features.Placed feature : features) {
            try {
                feature.place(world, random, origin, where);
            } catch (RuntimeException e) {
                Better112.LOG.debug("Cave feature failed: {}", e.toString());
            }
        }
        return any[0];
    }
}
