package better112.world.noise;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The random number generator modern Minecraft (1.18+) seeds its terrain noise with:
 * xoroshiro128++, plus the "positional" forks that derive an independent generator from a
 * name or a block position. Written from the published algorithm.
 */
public final class Xoroshiro {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long SILVER = 0x6A09E667F3BCC909L;

    private long lo, hi;

    public Xoroshiro(long seed) {
        long l = seed ^ SILVER;
        long h = l + GOLDEN;
        init(mix(l), mix(h));
    }

    public Xoroshiro(long lo, long hi) {
        init(lo, hi);
    }

    private void init(long l, long h) {
        if ((l | h) == 0L) {
            l = GOLDEN;
            h = SILVER;
        }
        this.lo = l;
        this.hi = h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long nextLong() {
        long s0 = lo, s1 = hi;
        long result = Long.rotateLeft(s0 + s1, 17) + s0;
        s1 ^= s0;
        lo = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
        hi = Long.rotateLeft(s1, 28);
        return result;
    }

    public int nextInt() {
        return (int) nextLong();
    }

    public int nextInt(int bound) {
        long r = Integer.toUnsignedLong(nextInt());
        long m = r * bound;
        long low = m & 0xFFFFFFFFL;
        if (low < bound) {
            int threshold = Integer.remainderUnsigned(~bound + 1, bound);
            while (low < threshold) {
                r = Integer.toUnsignedLong(nextInt());
                m = r * bound;
                low = m & 0xFFFFFFFFL;
            }
        }
        return (int) (m >> 32);
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24F;
    }

    public void skip(int count) {
        for (int i = 0; i < count; i++) {
            nextLong();
        }
    }

    /** A source of independent generators, one per name or per block position. */
    public Positional fork() {
        return new Positional(nextLong(), nextLong());
    }

    public static final class Positional {
        private final long lo, hi;

        Positional(long lo, long hi) {
            this.lo = lo;
            this.hi = hi;
        }

        public Xoroshiro named(String name) {
            try {
                byte[] md5 = MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8));
                long a = 0L, b = 0L;
                for (int i = 0; i < 8; i++) {
                    a = (a << 8) | (md5[i] & 0xFFL);
                    b = (b << 8) | (md5[i + 8] & 0xFFL);
                }
                return new Xoroshiro(a ^ lo, b ^ hi);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        public Xoroshiro at(int x, int y, int z) {
            return new Xoroshiro(positionSeed(x, y, z) ^ lo, hi);
        }
    }

    public static long positionSeed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }
}
