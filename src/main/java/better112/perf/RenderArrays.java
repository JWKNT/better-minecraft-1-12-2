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
}
