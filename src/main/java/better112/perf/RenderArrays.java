package better112.perf;

import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;

/**
 * The arrays an enum's values() copies on every call, made once, for patched chunk-building code that
 * only reads them (the section builder and Forge's block model renderer call values() for every block).
 * Loaded only by that client code; nothing may write into these.
 */
public final class RenderArrays {

    private RenderArrays() {}

    public static final BlockRenderLayer[] RENDER_LAYERS = BlockRenderLayer.values();
    public static final EnumFacing[] FACINGS = EnumFacing.values();

    private static final class LitLists {
        final java.util.ArrayList<Object> quads = new java.util.ArrayList<Object>(64);
        final java.util.ArrayList<Object> segment = new java.util.ArrayList<Object>(64);
        int depth;
    }

    private static final ThreadLocal<LitLists> LIT = new ThreadLocal<LitLists>() {
        @Override
        protected LitLists initialValue() {
            return new LitLists();
        }
    };

    /**
     * Forge's renderLitItem gathers an item model's quads into a new list, and each run of quads with the same lighting
     * into another, for every item drawn: every slot, the hotbar and JEI's list, every frame. The two lists are kept per
     * thread and emptied instead (0: the quads, 1: the run); a call made while another is still running on the same
     * thread gets new ones, as before.
     */
    public static java.util.ArrayList<Object> litList(int which) {
        LitLists lists = LIT.get();
        if (which == 0) {
            lists.depth++;
        }
        if (lists.depth != 1) {
            return new java.util.ArrayList<Object>();
        }
        java.util.ArrayList<Object> list = which == 0 ? lists.quads : lists.segment;
        list.clear();
        return list;
    }

    /**
     * The size of the buffer the game uploads an image texture through. It always makes one of 4,194,304 ints (16 MB),
     * even for a 16 by 16 picture, every time a texture is loaded from a file: the first time each mob, block entity or
     * screen picture is drawn. The upload fills width * rows ints of it per pass, at most as many rows as the image has,
     * so a buffer of that size does the same work.
     */
    public static int uploadBufferSize(int rowsPerPass, int width, java.awt.image.BufferedImage image) {
        return width * Math.min(rowsPerPass, image.getHeight());
    }

    /** End of renderLitItem (each return). A call that ended by an exception leaves later calls on new lists. */
    public static void litDone() {
        LitLists lists = LIT.get();
        if (lists.depth > 0) {
            lists.depth--;
        }
    }
}
