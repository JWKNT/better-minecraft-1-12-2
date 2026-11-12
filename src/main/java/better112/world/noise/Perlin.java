package better112.world.noise;

/** One layer of Ken Perlin's improved noise, with Minecraft's shuffle of the permutation table. */
public final class Perlin {

    private static final int[][] GRADIENT = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}};

    private final byte[] p = new byte[256];
    private final double xo, yo, zo;

    public Perlin(Xoroshiro random) {
        xo = random.nextDouble() * 256.0D;
        yo = random.nextDouble() * 256.0D;
        zo = random.nextDouble() * 256.0D;
        for (int i = 0; i < 256; i++) {
            p[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256 - i);
            byte b = p[i];
            p[i] = p[i + j];
            p[i + j] = b;
        }
    }

    public double sample(double x, double y, double z) {
        return sample(x, y, z, 0.0D, 0.0D);
    }

    /** yScale and yMax smear the noise vertically; only the old blended terrain noise uses them. */
    public double sample(double x, double y, double z, double yScale, double yMax) {
        double d = x + xo, e = y + yo, f = z + zo;
        int i = floor(d), j = floor(e), k = floor(f);
        double g = d - i, h = e - j, l = f - k;
        double o = 0.0D;
        if (yScale != 0.0D) {
            double m = yMax >= 0.0D && yMax < h ? yMax : h;
            o = floor(m / yScale + 1.0E-7D) * yScale;
        }
        return lerped(i, j, k, g, h - o, l, h);
    }

    private double lerped(int i, int j, int k, double x, double y, double z, double smoothY) {
        int a = perm(i), b = perm(i + 1);
        int aa = perm(a + j), ab = perm(a + j + 1), ba = perm(b + j), bb = perm(b + j + 1);
        double n000 = dot(perm(aa + k), x, y, z);
        double n100 = dot(perm(ba + k), x - 1.0D, y, z);
        double n010 = dot(perm(ab + k), x, y - 1.0D, z);
        double n110 = dot(perm(bb + k), x - 1.0D, y - 1.0D, z);
        double n001 = dot(perm(aa + k + 1), x, y, z - 1.0D);
        double n101 = dot(perm(ba + k + 1), x - 1.0D, y, z - 1.0D);
        double n011 = dot(perm(ab + k + 1), x, y - 1.0D, z - 1.0D);
        double n111 = dot(perm(bb + k + 1), x - 1.0D, y - 1.0D, z - 1.0D);
        double sx = smooth(x), sy = smooth(smoothY), sz = smooth(z);
        double front = lerp(sy, lerp(sx, n000, n100), lerp(sx, n010, n110));
        double back = lerp(sy, lerp(sx, n001, n101), lerp(sx, n011, n111));
        return lerp(sz, front, back);
    }

    private int perm(int i) {
        return p[i & 255] & 255;
    }

    private static double dot(int hash, double x, double y, double z) {
        int[] g = GRADIENT[hash & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }

    private static double smooth(double t) {
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
