package better112.world.density;

/** Owns the loader and the cell grid that the interpolated functions of one world share. */
public final class TerrainGrid {

    public final DensityLoader loader;
    private final Density.Grid grid = new Density.Grid();

    public TerrainGrid(WorldData data, long seed, int cellWidth, int cellHeight) {
        grid.cellWidth = cellWidth;
        grid.cellHeight = cellHeight;
        grid.sizesSet();
        loader = new DensityLoader(data, seed, grid);
    }

    /** Computes every interpolated function at the cell corners of the given box of blocks. */
    public void prepare(int x0, int y0, int z0, int sizeX, int sizeY, int sizeZ) {
        grid.x0 = x0;
        grid.y0 = y0;
        grid.z0 = z0;
        grid.nx = sizeX / grid.cellWidth + 1;
        grid.ny = sizeY / grid.cellHeight + 1;
        grid.nz = sizeZ / grid.cellWidth + 1;
        grid.filling = true;
        try {
            for (Density.Interpolated node : loader.interpolated) {
                node.fill();
            }
        } finally {
            grid.filling = false;
        }
    }
}
