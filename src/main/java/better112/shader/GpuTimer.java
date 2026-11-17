package better112.shader;

import org.lwjgl.opengl.EXTTimerQuery;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

/**
 * How long each part of the shaders takes: on the graphics card, from timer queries read a few
 * frames late so the game never waits for them, and on the game thread (the calls themselves,
 * which on a Mac's OpenGL are often the larger part). Off unless the self-test asks (gputime:on).
 */
public final class GpuTimer {

    static final int SHADOW = 0, TERRAIN = 1, COPY = 2, TRANSLUCENT = 3, RAYS = 4, POST = 5, SETUP = 6;
    static final String[] NAMES = {"shadow", "terrain", "copies+ao", "water layer", "rays", "bloom+grade", "setup"};
    private static final int DEPTH = 4;

    public static boolean enabled;
    private static int[][] queries;
    private static boolean[][] pending;
    private static int slot;
    private static int open = -1;
    private static boolean queryOpen;
    private static long openedAt;
    private static final long[] TOTAL = new long[NAMES.length];
    private static final int[] COUNT = new int[NAMES.length];
    private static final long[] CPU = new long[NAMES.length];
    private static int frames;

    private GpuTimer() {}

    static void begin(int section) {
        if (!enabled || open >= 0) {
            return;
        }
        open = section;
        openedAt = System.nanoTime();
        queryOpen = false;
        if (!GLContext.getCapabilities().GL_EXT_timer_query) {
            return;
        }
        if (queries == null) {
            queries = new int[DEPTH][NAMES.length];
            pending = new boolean[DEPTH][NAMES.length];
            for (int i = 0; i < DEPTH; i++) {
                for (int j = 0; j < NAMES.length; j++) {
                    queries[i][j] = GL15.glGenQueries();
                }
            }
        }
        if (pending[slot][section]) {
            return; // already timed this part this frame (two terrain layers): the first one counts on the card
        }
        GL15.glBeginQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT, queries[slot][section]);
        queryOpen = true;
    }

    static void end() {
        if (open < 0) {
            return;
        }
        CPU[open] += System.nanoTime() - openedAt;
        if (queryOpen) {
            GL15.glEndQuery(EXTTimerQuery.GL_TIME_ELAPSED_EXT);
            pending[slot][open] = true;
        }
        open = -1;
        queryOpen = false;
    }

    /** Once a frame: collects the results of the oldest frame in the ring. */
    static void frameDone() {
        if (!enabled) {
            return;
        }
        frames++;
        if (queries == null) {
            return;
        }
        slot = (slot + 1) % DEPTH;
        for (int j = 0; j < NAMES.length; j++) {
            if (pending[slot][j]) {
                long ns = EXTTimerQuery.glGetQueryObjectuEXT(queries[slot][j], GL15.GL_QUERY_RESULT);
                TOTAL[j] += ns;
                COUNT[j]++;
                pending[slot][j] = false;
            }
        }
    }

    /** Timing of the game's own terrain drawing, with the shaders off, for comparison. */
    public static void vanillaBegin(net.minecraft.util.BlockRenderLayer layer) {
        begin(layer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT ? TRANSLUCENT : TERRAIN);
    }

    public static void vanillaEnd() {
        end();
    }

    public static void vanillaFrame() {
        frameDone();
    }

    /** Average milliseconds per frame of each part since the last reset: card / game thread. */
    public static String report() {
        StringBuilder b = new StringBuilder();
        for (int j = 0; j < NAMES.length; j++) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(NAMES[j]).append(' ');
            b.append(COUNT[j] == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.3f", TOTAL[j] / 1.0E6 / COUNT[j]));
            b.append('/').append(frames == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.3f", CPU[j] / 1.0E6 / frames));
        }
        return b + " ms card/thread (" + frames + " frames)";
    }

    public static void reset() {
        java.util.Arrays.fill(TOTAL, 0);
        java.util.Arrays.fill(COUNT, 0);
        java.util.Arrays.fill(CPU, 0);
        frames = 0;
    }
}
