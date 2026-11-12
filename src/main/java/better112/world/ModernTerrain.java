package better112.world;

import better112.world.density.Density;
import better112.world.density.DensityLoader;
import better112.world.density.TerrainGrid;
import better112.world.density.WorldData;

import com.google.gson.JsonObject;

/**
 * The shape of a modern (1.18+) world, computed from the modern game's own data files for a
 * seed: where there is ground, water and cave, and the climate values biomes are chosen by.
 *
 * Modern worlds run from y -64 to 320 and 1.12.2 worlds from 0 to 256, so this samples the
 * modern world at the same heights (sea level stays at 63, which pack ore tables depend on)
 * and leaves out what would lie below zero.
 */
public final class ModernTerrain {

    public final int seaLevel;
    public final int cellWidth, cellHeight;

    private final TerrainGrid grid;
    private final DensityLoader loader;
    private final Density finalDensity;
    /** Up to 1.21.8 the game gives a density to search for the rough surface; from 1.21.9 the surface height itself. */
    private final Density initialDensity, surfaceLevel;
    public final Density temperature, vegetation, continents, erosion, depth, ridges;

    public ModernTerrain(WorldData data, String settings, long seed) {
        JsonObject json = data.read("noise_settings", settings).getAsJsonObject();
        JsonObject noise = json.getAsJsonObject("noise");
        seaLevel = json.get("sea_level").getAsInt();
        cellWidth = noise.get("size_horizontal").getAsInt() * 4;
        cellHeight = noise.get("size_vertical").getAsInt() * 4;
        grid = new TerrainGrid(data, seed, cellWidth, cellHeight);
        loader = grid.loader;
        JsonObject router = json.getAsJsonObject("noise_router");
        finalDensity = loader.load(router.get("final_density"));
        if (router.has("preliminary_surface_level")) {
            surfaceLevel = loader.load(router.get("preliminary_surface_level"));
            initialDensity = null;
        } else {
            surfaceLevel = null;
            initialDensity = loader.load(router.get("initial_density_without_jaggedness"));
        }
        temperature = loader.load(router.get("temperature"));
        vegetation = loader.load(router.get("vegetation"));
        continents = loader.load(router.get("continents"));
        erosion = loader.load(router.get("erosion"));
        depth = loader.load(router.get("depth"));
        ridges = loader.load(router.get("ridges"));
    }

    /** One of the game's named noises for this world ("minecraft:powder_snow"): the ground of some biomes is patterned by them. */
    public better112.world.noise.NormalNoise noise(String id) {
        return loader.noise(id);
    }

    /** Prepares the 16x16 column of blocks of a chunk, from y 0 up to maxY (a multiple of the cell height). */
    public void startChunk(int chunkX, int chunkZ, int maxY) {
        grid.prepare(chunkX << 4, 0, chunkZ << 4, 16, maxY, 16);
    }

    /** Positive where the modern world has solid ground. Call {@link #startChunk} for the chunk first. */
    public double density(int x, int y, int z) {
        return finalDensity.at(x, y, z);
    }

    /**
     * Roughly where the surface is, ignoring caves and the jagged tops of peaks: the game
     * uses this to tell "under the sea" from "in a cave under land".
     */
    public int roughSurface(int x, int z) {
        if (surfaceLevel != null) {
            return Math.max(0, (int) surfaceLevel.at(x, 0, z));
        }
        for (int y = 256; y >= 0; y -= cellHeight) {
            if (initialDensity.at(x, y, z) > 0.390625D) {
                return y;
            }
        }
        return 0;
    }
}
