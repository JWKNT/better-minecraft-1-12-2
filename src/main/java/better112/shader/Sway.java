package better112.shader;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockCactus;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockReed;
import net.minecraft.block.BlockVine;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;

/**
 * Marks, while a chunk section is built, how far each vertex of leaves and plants may sway:
 * the shaders move them in the wind. The mark goes in the vertex colour's alpha, which terrain
 * outside the translucent layer does not use (opaque blocks ignore it, cut-out ones only test
 * it against a tenth): 255 means still, 255 - n sways by n steps. Plants sway by their height
 * in the block, so a stem stays rooted and the upper half of a tall flower carries on from the
 * lower half's top; leaves and vines sway as one.
 *
 * Done from the block and the vertex's height rather than from the texture, which cannot tell
 * which corner of a picture a vertex is (and guessing wrong opened hairline gaps in the ground).
 */
public final class Sway {

    private Sway() {}

    /** Set by Shaders.refresh: the shaders are on and plants sway. */
    public static volatile boolean active;

    static final byte NONE = 0, LEAVES = 1, PLANT = 2, VINE = 3;
    private static final ConcurrentHashMap<Block, Byte> KINDS = new ConcurrentHashMap<Block, Byte>();
    private static final ThreadLocal<int[]> START = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    /** Steps of sway: a plant's top, the top of a two-block plant's upper half, leaves, vines. */
    private static final float PLANT_TOP = 23.0F;
    private static final int LEAVES_STEPS = 9, VINE_STEPS = 8;

    static byte kind(Block block) {
        Byte known = KINDS.get(block);
        if (known != null) {
            return known;
        }
        byte kind = NONE;
        Material material = block.getDefaultState().getMaterial();
        if (block instanceof BlockLeaves || material == Material.LEAVES) {
            kind = LEAVES;
        } else if (block instanceof BlockVine) {
            kind = VINE;
        } else if (block instanceof BlockReed || block instanceof BlockCactus) {
            kind = NONE; // stacks of one plant: each block swaying on its own would tear the stack apart
        } else if (block instanceof BlockBush || (material == Material.PLANTS || material == Material.VINE) && !block.getDefaultState().isFullCube()) {
            kind = PLANT;
        }
        KINDS.put(block, kind);
        return kind;
    }

    /** Head of BlockRendererDispatcher.renderBlock. */
    public static void begin(BufferBuilder buffer) {
        if (active) {
            START.get()[0] = buffer.getVertexCount();
        }
    }

    /** End of BlockRendererDispatcher.renderBlock: marks the vertices this block added. */
    public static void end(IBlockState state, BlockPos pos, BufferBuilder buffer) {
        try {
            mark(state, pos, buffer);
        } catch (Throwable t) {
            // Never break the building of a chunk: plants just stop swaying.
            active = false;
            better112.Better112.LOG.warn("Swaying plants switched off", t);
        }
    }

    private static void mark(IBlockState state, BlockPos pos, BufferBuilder buffer) {
        if (!active || buffer.getVertexFormat() != DefaultVertexFormats.BLOCK) {
            return;
        }
        int start = START.get()[0], end = buffer.getVertexCount();
        if (end <= start) {
            return;
        }
        byte kind = kind(state.getBlock());
        if (kind == NONE) {
            return;
        }
        boolean upper = false;
        if (kind == PLANT) {
            for (IProperty<?> property : state.getPropertyKeys()) {
                if ("half".equals(property.getName())) {
                    upper = "upper".equals(String.valueOf(state.getValue(property)).toLowerCase(java.util.Locale.ROOT));
                    break;
                }
            }
        }
        ByteBuffer data = buffer.getByteBuffer();
        int base = pos.getY() & 15;
        for (int v = start; v < end; v++) {
            int at = v * 28;
            int steps;
            if (kind == LEAVES) {
                steps = LEAVES_STEPS;
            } else if (kind == VINE) {
                steps = VINE_STEPS;
            } else {
                float height = data.getFloat(at + 4) - base;
                steps = Math.round(Math.max(0.0F, height + (upper ? 1.0F : 0.0F)) * PLANT_TOP);
            }
            if (steps > 0) {
                data.put(at + 15, (byte) (255 - Math.min(63, steps)));
            }
        }
    }
}
