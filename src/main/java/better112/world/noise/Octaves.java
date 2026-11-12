package better112.world.noise;

/** Several Perlin layers, each twice as fine and half as strong as the one before. */
public final class Octaves {

    private final Perlin[] levels;
    private final double[] amplitudes;
    private final double lowestInput, lowestValue;

    /** The modern layout: every layer seeded from its own name, so layers can be added without reshuffling. */
    public static Octaves named(Xoroshiro random, int firstOctave, double[] amplitudes) {
        Perlin[] levels = new Perlin[amplitudes.length];
        Xoroshiro.Positional fork = random.fork();
        for (int i = 0; i < amplitudes.length; i++) {
            if (amplitudes[i] != 0.0D) {
                levels[i] = new Perlin(fork.named("octave_" + (firstOctave + i)));
            }
        }
        return new Octaves(levels, amplitudes, firstOctave);
    }

    /** The old layout, kept by the blended terrain noise: layers drawn one after another from one generator. */
    public static Octaves sequential(Xoroshiro random, int firstOctave) {
        int n = -firstOctave + 1;
        double[] amplitudes = new double[n];
        Perlin[] levels = new Perlin[n];
        for (int i = n - 1; i >= 0; i--) {
            amplitudes[i] = 1.0D;
            levels[i] = new Perlin(random);
        }
        return new Octaves(levels, amplitudes, firstOctave);
    }

    private Octaves(Perlin[] levels, double[] amplitudes, int firstOctave) {
        this.levels = levels;
        this.amplitudes = amplitudes;
        int n = amplitudes.length;
        this.lowestInput = Math.pow(2.0D, firstOctave);
        this.lowestValue = Math.pow(2.0D, n - 1) / (Math.pow(2.0D, n) - 1.0D);
    }

    public double sample(double x, double y, double z) {
        double sum = 0.0D, input = lowestInput, value = lowestValue;
        for (int i = 0; i < levels.length; i++) {
            Perlin level = levels[i];
            if (level != null) {
                sum += amplitudes[i] * value * level.sample(wrap(x * input), wrap(y * input), wrap(z * input));
            }
            input *= 2.0D;
            value /= 2.0D;
        }
        return sum;
    }

    /** Layer by fineness: 0 is the finest. */
    public Perlin fromFinest(int index) {
        return levels[levels.length - 1 - index];
    }

    public double maxValue() {
        double sum = 0.0D, value = lowestValue;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] != null) {
                sum += amplitudes[i] * 2.0D * value;
            }
            value /= 2.0D;
        }
        return sum;
    }

    /** Keeps coordinates small enough that the noise keeps its precision far from the origin. */
    public static double wrap(double v) {
        return v - Math.floor(v / 3.3554432E7D + 0.5D) * 3.3554432E7D;
    }
}
