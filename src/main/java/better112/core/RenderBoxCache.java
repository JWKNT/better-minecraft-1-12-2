package better112.core;

/** Added to TileEntity by the patcher: the last render box Forge's default worked out, and what it was worked out from. */
public interface RenderBoxCache {

    Object[] better112_getRenderBox();

    void better112_setRenderBox(Object[] entry);
}
