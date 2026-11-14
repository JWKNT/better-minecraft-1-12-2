package better112.core.forge.net.minecraftforge.fml.common.asm.transformers;

import net.minecraft.launchwrapper.IClassTransformer;

import better112.core.ForgeFastPath;

/** Stands in for Forge's SideTransformer (see {@link ForgeFastPath}); the class name holds Forge's on purpose. */
public final class SideTransformer extends ForgeFastPath {

    public SideTransformer(IClassTransformer original) {
        super(original, SIDE);
    }
}
