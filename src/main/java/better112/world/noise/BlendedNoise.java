package better112.world.noise;

/**
 * The terrain noise Minecraft has used since Beta: two 16-layer noises and a third that
 * picks between them. Modern terrain still adds it on top of its continent shapes.
 */
public final class BlendedNoise {

    private final Octaves minLimit, maxLimit, main;
    private final double xzMultiplier, yMultiplier, xzFactor, yFactor, smear;

    public BlendedNoise(Xoroshiro random, double xzScale, double yScale, double xzFactor, double yFactor, double smearScale) {
        minLimit = Octaves.sequential(random, -15);
        maxLimit = Octaves.sequential(random, -15);
        main = Octaves.sequential(random, -7);
        this.xzMultiplier = 684.412D * xzScale;
        this.yMultiplier = 684.412D * yScale;
        this.xzFactor = xzFactor;
        this.yFactor = yFactor;
        this.smear = smearScale;
    }

    public double sample(int blockX, int blockY, int blockZ) {
        double d = blockX * xzMultiplier, e = blockY * yMultiplier, f = blockZ * xzMultiplier;
        double g = d / xzFactor, h = e / yFactor, i = f / xzFactor;
        double j = yMultiplier * smear;
        double k = j / yFactor;
        double pick = 0.0D, o = 1.0D;
        for (int p = 0; p < 8; p++) {
            Perlin level = main.fromFinest(p);
            if (level != null) {
                pick += level.sample(Octaves.wrap(g * o), Octaves.wrap(h * o), Octaves.wrap(i * o), k * o, h * o) / o;
            }
            o /= 2.0D;
        }
        double q = (pick / 10.0D + 1.0D) / 2.0D;
        boolean onlyMax = q >= 1.0D, onlyMin = q <= 0.0D;
        double low = 0.0D, high = 0.0D;
        o = 1.0D;
        for (int r = 0; r < 16; r++) {
            double s = Octaves.wrap(d * o), t = Octaves.wrap(e * o), u = Octaves.wrap(f * o);
            double v = j * o;
            if (!onlyMax) {
                Perlin level = minLimit.fromFinest(r);
                if (level != null) {
                    low += level.sample(s, t, u, v, e * o) / o;
                }
            }
            if (!onlyMin) {
                Perlin level = maxLimit.fromFinest(r);
                if (level != null) {
                    high += level.sample(s, t, u, v, e * o) / o;
                }
            }
            o /= 2.0D;
        }
        double a = low / 512.0D, b = high / 512.0D;
        double blended = q < 0.0D ? a : q > 1.0D ? b : a + q * (b - a);
        return blended / 128.0D;
    }
}
