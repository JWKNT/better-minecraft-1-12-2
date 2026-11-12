package better112.world.density;

import better112.world.noise.BlendedNoise;
import better112.world.noise.NormalNoise;

/**
 * A density function: a number for every block position. Modern Minecraft describes its
 * whole terrain as a tree of these in data files (worldgen/density_function and the noise
 * router in worldgen/noise_settings); positive means solid. Each kind the game defines has a
 * small class here, and {@link DensityLoader} builds the tree from the game's own files.
 */
public abstract class Density {

    public abstract double at(int x, int y, int z);

    // ---------------------------------------------------------------- leaves

    static final class Constant extends Density {
        final double value;

        Constant(double value) {
            this.value = value;
        }

        @Override
        public double at(int x, int y, int z) {
            return value;
        }
    }

    static final class Noise extends Density {
        final NormalNoise noise;
        final double xzScale, yScale;

        Noise(NormalNoise noise, double xzScale, double yScale) {
            this.noise = noise;
            this.xzScale = xzScale;
            this.yScale = yScale;
        }

        @Override
        public double at(int x, int y, int z) {
            return noise.sample(x * xzScale, y * yScale, z * xzScale);
        }
    }

    static final class ShiftedNoise extends Density {
        final NormalNoise noise;
        final double xzScale, yScale;
        final Density shiftX, shiftY, shiftZ;

        ShiftedNoise(NormalNoise noise, double xzScale, double yScale, Density shiftX, Density shiftY, Density shiftZ) {
            this.noise = noise;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.shiftZ = shiftZ;
        }

        @Override
        public double at(int x, int y, int z) {
            return noise.sample(x * xzScale + shiftX.at(x, y, z), y * yScale + shiftY.at(x, y, z), z * xzScale + shiftZ.at(x, y, z));
        }
    }

    /** shift_a, shift_b and shift: the offset noise read along different axes. */
    static final class Shift extends Density {
        final NormalNoise noise;
        final int mode;

        Shift(NormalNoise noise, int mode) {
            this.noise = noise;
            this.mode = mode;
        }

        @Override
        public double at(int x, int y, int z) {
            switch (mode) {
                case 0:
                    return noise.sample(x * 0.25D, 0.0D, z * 0.25D) * 4.0D;
                case 1:
                    return noise.sample(z * 0.25D, x * 0.25D, 0.0D) * 4.0D;
                default:
                    return noise.sample(x * 0.25D, y * 0.25D, z * 0.25D) * 4.0D;
            }
        }
    }

    static final class OldBlended extends Density {
        final BlendedNoise noise;

        OldBlended(BlendedNoise noise) {
            this.noise = noise;
        }

        @Override
        public double at(int x, int y, int z) {
            return noise.sample(x, y, z);
        }
    }

    /** Cave tunnels: a noise whose scale changes from place to place. */
    static final class WeirdScaled extends Density {
        final Density input;
        final NormalNoise noise;
        final boolean tunnels;

        WeirdScaled(Density input, NormalNoise noise, boolean tunnels) {
            this.input = input;
            this.noise = noise;
            this.tunnels = tunnels;
        }

        @Override
        public double at(int x, int y, int z) {
            double v = input.at(x, y, z);
            double rarity;
            if (tunnels) {
                rarity = v < -0.5D ? 0.75D : v < 0.0D ? 1.0D : v < 0.5D ? 1.5D : 2.0D;
            } else {
                rarity = v < -0.75D ? 0.5D : v < -0.5D ? 0.75D : v < 0.5D ? 1.0D : v < 0.75D ? 2.0D : 3.0D;
            }
            return rarity * Math.abs(noise.sample(x / rarity, y / rarity, z / rarity));
        }
    }

    static final class YGradient extends Density {
        final double fromY, toY, fromValue, toValue;

        YGradient(double fromY, double toY, double fromValue, double toValue) {
            this.fromY = fromY;
            this.toY = toY;
            this.fromValue = fromValue;
            this.toValue = toValue;
        }

        @Override
        public double at(int x, int y, int z) {
            double t = (y - fromY) / (toY - fromY);
            return t < 0.0D ? fromValue : t > 1.0D ? toValue : fromValue + t * (toValue - fromValue);
        }
    }

    // ---------------------------------------------------------------- arithmetic

    static final class Binary extends Density {
        static final int ADD = 0, MUL = 1, MIN = 2, MAX = 3;
        final int op;
        final Density a, b;

        Binary(int op, Density a, Density b) {
            this.op = op;
            this.a = a;
            this.b = b;
        }

        @Override
        public double at(int x, int y, int z) {
            double v = a.at(x, y, z);
            switch (op) {
                case ADD:
                    return v + b.at(x, y, z);
                case MUL:
                    return v == 0.0D ? 0.0D : v * b.at(x, y, z);
                case MIN:
                    return Math.min(v, b.at(x, y, z));
                default:
                    return Math.max(v, b.at(x, y, z));
            }
        }
    }

    static final class Unary extends Density {
        static final int ABS = 0, SQUARE = 1, CUBE = 2, HALF_NEGATIVE = 3, QUARTER_NEGATIVE = 4, SQUEEZE = 5, INVERT = 6;
        final int op;
        final Density input;

        Unary(int op, Density input) {
            this.op = op;
            this.input = input;
        }

        @Override
        public double at(int x, int y, int z) {
            double v = input.at(x, y, z);
            switch (op) {
                case ABS:
                    return Math.abs(v);
                case SQUARE:
                    return v * v;
                case CUBE:
                    return v * v * v;
                case HALF_NEGATIVE:
                    return v > 0.0D ? v : v * 0.5D;
                case QUARTER_NEGATIVE:
                    return v > 0.0D ? v : v * 0.25D;
                case INVERT:
                    return 1.0D / v;
                default: {
                    double c = v < -1.0D ? -1.0D : v > 1.0D ? 1.0D : v;
                    return c / 2.0D - c * c * c / 24.0D;
                }
            }
        }
    }

    static final class Clamp extends Density {
        final Density input;
        final double min, max;

        Clamp(Density input, double min, double max) {
            this.input = input;
            this.min = min;
            this.max = max;
        }

        @Override
        public double at(int x, int y, int z) {
            double v = input.at(x, y, z);
            return v < min ? min : v > max ? max : v;
        }
    }

    static final class RangeChoice extends Density {
        final Density input, inRange, outOfRange;
        final double min, max;

        RangeChoice(Density input, double min, double max, Density inRange, Density outOfRange) {
            this.input = input;
            this.min = min;
            this.max = max;
            this.inRange = inRange;
            this.outOfRange = outOfRange;
        }

        @Override
        public double at(int x, int y, int z) {
            double v = input.at(x, y, z);
            return v >= min && v < max ? inRange.at(x, y, z) : outOfRange.at(x, y, z);
        }
    }

    /**
     * The height of the highest solid cell in a column, looking down from an upper bound in
     * steps of a cell. The game (1.21.9+) uses it for "roughly where is the surface".
     */
    static final class FindTopSurface extends Density {
        final Density density, upperBound;
        final int lowerBound, cellHeight;

        FindTopSurface(Density density, Density upperBound, int lowerBound, int cellHeight) {
            this.density = density;
            this.upperBound = upperBound;
            this.lowerBound = lowerBound;
            this.cellHeight = cellHeight;
        }

        @Override
        public double at(int x, int y, int z) {
            int top = (int) Math.floor(upperBound.at(x, y, z) / cellHeight) * cellHeight;
            for (int j = top; j >= lowerBound; j -= cellHeight) {
                if (density.at(x, j, z) > 0.0D) {
                    return j;
                }
            }
            return lowerBound;
        }
    }

    /** A cubic spline over another function; values at its points may be splines themselves. */
    static final class Spline extends Density {
        final Density coordinate;
        final float[] locations, derivatives;
        final Density[] values;

        Spline(Density coordinate, float[] locations, Density[] values, float[] derivatives) {
            this.coordinate = coordinate;
            this.locations = locations;
            this.values = values;
            this.derivatives = derivatives;
        }

        @Override
        public double at(int x, int y, int z) {
            float f = (float) coordinate.at(x, y, z);
            int last = locations.length - 1;
            int i = -1;
            int lo = 0, hi = locations.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (f < locations[mid]) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            i = lo - 1;
            if (i < 0) {
                return extend(f, (float) values[0].at(x, y, z), 0);
            }
            if (i == last) {
                return extend(f, (float) values[last].at(x, y, z), last);
            }
            float g = locations[i], h = locations[i + 1];
            float t = (f - g) / (h - g);
            float n = (float) values[i].at(x, y, z), o = (float) values[i + 1].at(x, y, z);
            float p = derivatives[i] * (h - g) - (o - n);
            float q = -derivatives[i + 1] * (h - g) + (o - n);
            return n + t * (o - n) + t * (1.0F - t) * (p + t * (q - p));
        }

        private float extend(float f, float value, int i) {
            float d = derivatives[i];
            return d == 0.0F ? value : value + d * (f - locations[i]);
        }
    }

    // ---------------------------------------------------------------- caches

    /** Remembers the answer for the last column asked about. */
    static final class Cache2D extends Density {
        final Density input;
        private int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        private double last;

        Cache2D(Density input) {
            this.input = input;
        }

        @Override
        public double at(int x, int y, int z) {
            if (x != lastX || z != lastZ) {
                last = input.at(x, y, z);
                lastX = x;
                lastZ = z;
            }
            return last;
        }
    }

    /** Remembers the answer for the last block asked about. */
    static final class CacheOnce extends Density {
        final Density input;
        private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        private double last;

        CacheOnce(Density input) {
            this.input = input;
        }

        @Override
        public double at(int x, int y, int z) {
            if (x != lastX || y != lastY || z != lastZ) {
                last = input.at(x, y, z);
                lastX = x;
                lastY = y;
                lastZ = z;
            }
            return last;
        }
    }

    /** One value per 4x4 column of blocks, kept in a small table. */
    static final class FlatCache extends Density {
        private static final int SIZE = 16, MASK = SIZE - 1;
        final Density input;
        private final int[] keyX = new int[SIZE * SIZE], keyZ = new int[SIZE * SIZE];
        private final double[] values = new double[SIZE * SIZE];

        FlatCache(Density input) {
            this.input = input;
            java.util.Arrays.fill(keyX, Integer.MIN_VALUE);
        }

        @Override
        public double at(int x, int y, int z) {
            int qx = x >> 2, qz = z >> 2;
            int slot = (qx & MASK) * SIZE + (qz & MASK);
            if (keyX[slot] != qx || keyZ[slot] != qz) {
                values[slot] = input.at(qx << 2, 0, qz << 2);
                keyX[slot] = qx;
                keyZ[slot] = qz;
            }
            return values[slot];
        }
    }

    /**
     * Computed only at the corners of cells (4x8x4 blocks in the overworld) and blended in
     * between. This is what makes the expensive parts of the terrain affordable, and also
     * what gives modern terrain its smooth shapes.
     */
    public static final class Interpolated extends Density {
        final Density input;
        final Grid grid;
        private double[] corners;
        private boolean ready;

        Interpolated(Density input, Grid grid) {
            this.input = input;
            this.grid = grid;
        }

        void fill() {
            Grid g = grid;
            if (corners == null || corners.length != g.nx * g.ny * g.nz) {
                corners = new double[g.nx * g.ny * g.nz];
            }
            int i = 0;
            for (int ix = 0; ix < g.nx; ix++) {
                int x = g.x0 + ix * g.cellWidth;
                for (int iz = 0; iz < g.nz; iz++) {
                    int z = g.z0 + iz * g.cellWidth;
                    for (int iy = 0; iy < g.ny; iy++) {
                        corners[i++] = input.at(x, g.y0 + iy * g.cellHeight, z);
                    }
                }
            }
            ready = true;
        }

        void clear() {
            ready = false;
        }

        @Override
        public double at(int x, int y, int z) {
            Grid g = grid;
            if (!ready || g.filling) {
                return input.at(x, y, z);
            }
            int dx = x - g.x0, dy = y - g.y0, dz = z - g.z0;
            int ix = dx / g.cellWidth, iy = dy / g.cellHeight, iz = dz / g.cellWidth;
            if (dx < 0 || dy < 0 || dz < 0 || ix >= g.nx - 1 || iy >= g.ny - 1 || iz >= g.nz - 1) {
                return input.at(x, y, z);
            }
            double tx = (dx - ix * g.cellWidth) / (double) g.cellWidth;
            double ty = (dy - iy * g.cellHeight) / (double) g.cellHeight;
            double tz = (dz - iz * g.cellWidth) / (double) g.cellWidth;
            int base = (ix * g.nz + iz) * g.ny + iy;
            int nextX = g.nz * g.ny, nextZ = g.ny;
            double[] c = corners;
            double c000 = c[base], c010 = c[base + 1];
            double c001 = c[base + nextZ], c011 = c[base + nextZ + 1];
            double c100 = c[base + nextX], c110 = c[base + nextX + 1];
            double c101 = c[base + nextX + nextZ], c111 = c[base + nextX + nextZ + 1];
            double x00 = c000 + tx * (c100 - c000), x10 = c010 + tx * (c110 - c010);
            double x01 = c001 + tx * (c101 - c001), x11 = c011 + tx * (c111 - c011);
            double y0 = x00 + ty * (x10 - x00), y1 = x01 + ty * (x11 - x01);
            return y0 + tz * (y1 - y0);
        }
    }

    /** The block of cells the interpolated functions are currently prepared for. */
    public static final class Grid {
        public int cellWidth = 4, cellHeight = 8;
        int x0, y0, z0, nx, ny, nz;
        boolean filling;
    }
}
