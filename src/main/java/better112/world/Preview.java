package better112.world;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;

import javax.imageio.ImageIO;

import better112.world.density.WorldData;

/**
 * Renders the terrain to pictures without starting the game: a map seen from above and a
 * vertical cut through the ground. For checking the generator, not part of play.
 *
 *   java better112.world.Preview MODERN_JAR SEED CENTER_X CENTER_Z SIZE_IN_CHUNKS OUT_DIR
 */
public final class Preview {

    private Preview() {}

    public static void main(String[] args) throws Exception {
        File jar = new File(args[0]);
        long seed = Long.parseLong(args[1]);
        int centerX = Integer.parseInt(args[2]) >> 4, centerZ = Integer.parseInt(args[3]) >> 4;
        int chunks = Integer.parseInt(args[4]);
        File out = new File(args[5]);
        out.mkdirs();

        WorldData data = new WorldData(Collections.singletonList(jar));
        long start = System.nanoTime();
        ModernTerrain terrain = new ModernTerrain(data, "minecraft:overworld", seed);
        System.out.printf("loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0E6D);

        int size = chunks * 16;
        int[][] height = new int[size][size];
        int[][] water = new int[size][size];
        float[][] temp = new float[size][size];
        int sliceRow = size / 2;
        boolean[][] sliceSolid = new boolean[size][256];
        int minHeight = 255, maxHeight = 0;
        long caves = 0, underground = 0;

        start = System.nanoTime();
        for (int cx = 0; cx < chunks; cx++) {
            for (int cz = 0; cz < chunks; cz++) {
                int chunkX = centerX - chunks / 2 + cx, chunkZ = centerZ - chunks / 2 + cz;
                terrain.startChunk(chunkX, chunkZ, 256);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = (chunkX << 4) + lx, z = (chunkZ << 4) + lz;
                        int px = cx * 16 + lx, pz = cz * 16 + lz;
                        int top = -1;
                        for (int y = 255; y >= 0; y--) {
                            boolean solid = terrain.density(x, y, z) > 0.0D;
                            if (solid && top < 0) {
                                top = y;
                            }
                            if (top >= 0) {
                                underground++;
                                if (!solid) {
                                    caves++;
                                }
                            }
                            if (pz == sliceRow) {
                                sliceSolid[px][y] = solid;
                            }
                        }
                        height[px][pz] = Math.max(top, 0);
                        water[px][pz] = Math.max(0, terrain.seaLevel - 1 - top);
                        temp[px][pz] = (float) terrain.temperature.at(x, 0, z);
                        minHeight = Math.min(minHeight, top);
                        maxHeight = Math.max(maxHeight, top);
                    }
                }
            }
        }
        double ms = (System.nanoTime() - start) / 1.0E6D;
        System.out.printf("%d chunks in %.0f ms, %.1f ms per chunk%n", chunks * chunks, ms, ms / (chunks * chunks));
        System.out.printf("surface from y %d to %d, %.1f%% of the underground is cave%n", minHeight, maxHeight, 100.0D * caves / underground);

        BufferedImage map = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int h = height[x][z];
                int rgb;
                if (water[x][z] > 0) {
                    int d = Math.min(40, water[x][z]);
                    rgb = rgb(40 - d / 2, 90 - d, 200 - d * 2);
                } else {
                    // slope shading from the neighbour to the north-west
                    int ref = height[Math.max(0, x - 1)][Math.max(0, z - 1)];
                    float shade = Math.max(0.55F, Math.min(1.3F, 1.0F + (h - ref) * 0.12F));
                    float t = temp[x][z];
                    int r, g, b;
                    if (h > 150 || (t < -0.45F && h > 80)) {
                        r = 235; g = 240; b = 245;          // snow
                    } else if (h > 120) {
                        r = 135; g = 135; b = 130;          // bare stone
                    } else if (h <= 64) {
                        r = 215; g = 205; b = 150;          // beach
                    } else if (t > 0.55F) {
                        r = 215; g = 195; b = 120;          // desert
                    } else if (t < -0.45F) {
                        r = 225; g = 232; b = 238;
                    } else {
                        r = 80 + (int) (t * 40); g = 150 - (h - 64) / 2; b = 60;
                    }
                    rgb = rgb((int) (r * shade), (int) (g * shade), (int) (b * shade));
                }
                map.setRGB(x, z, rgb);
            }
        }
        ImageIO.write(map, "png", new File(out, "map.png"));

        int scale = 2;
        BufferedImage slice = new BufferedImage(size * scale, 256 * scale, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            int top = height[x][sliceRow];
            for (int y = 0; y < 256; y++) {
                int rgb;
                if (sliceSolid[x][y]) {
                    rgb = y > top - 4 && y >= 62 ? rgb(95, 140, 60) : y < 8 ? rgb(50, 50, 56) : rgb(120, 120, 124);
                } else if (y > top) {
                    rgb = y < terrain.seaLevel ? rgb(50, 90, 200) : rgb(170, 205, 245);
                } else {
                    rgb = y <= 10 ? rgb(230, 110, 30) : rgb(30, 30, 36);
                }
                for (int sx = 0; sx < scale; sx++) {
                    for (int sy = 0; sy < scale; sy++) {
                        slice.setRGB(x * scale + sx, (255 - y) * scale + sy, rgb);
                    }
                }
            }
        }
        ImageIO.write(slice, "png", new File(out, "slice.png"));
        data.close();
    }

    private static int rgb(int r, int g, int b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
