package better112.world.noise;

/**
 * Two octave stacks sampled at slightly different scales and added, which hides the grid
 * a single stack shows. This is the noise behind every named entry in worldgen/noise.
 */
public final class NormalNoise {

    private static final double INPUT_FACTOR = 1.0181268882175227D;

    private final Octaves first, second;
    private final double factor;

    public NormalNoise(Xoroshiro random, int firstOctave, double[] amplitudes) {
        first = Octaves.named(random, firstOctave, amplitudes);
        second = Octaves.named(random, firstOctave, amplitudes);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int i = 0; i < amplitudes.length; i++) {
            if (amplitudes[i] != 0.0D) {
                min = Math.min(min, i);
                max = Math.max(max, i);
            }
        }
        factor = 0.16666666666666666D / (0.1D * (1.0D + 1.0D / (max - min + 1)));
    }

    public double sample(double x, double y, double z) {
        return (first.sample(x, y, z) + second.sample(x * INPUT_FACTOR, y * INPUT_FACTOR, z * INPUT_FACTOR)) * factor;
    }
}
