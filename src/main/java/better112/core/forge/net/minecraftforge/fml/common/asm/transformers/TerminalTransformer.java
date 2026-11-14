package better112.core.forge.net.minecraftforge.fml.common.asm.transformers;

import better112.core.ForgeFastPath;

/**
 * Stands in for Forge's TerminalTransformer (see {@link ForgeFastPath}). It is one, so that TickCentral, which orders
 * the transformer list and runs its own pass by that class, treats it the same; the class name holds Forge's on purpose.
 */
public final class TerminalTransformer extends net.minecraftforge.fml.common.asm.transformers.TerminalTransformer {

    private final ForgeFastPath fastPath;

    public TerminalTransformer(ForgeFastPath fastPath) {
        this.fastPath = fastPath;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        return fastPath.transform(name, transformedName, bytes);
    }
}
