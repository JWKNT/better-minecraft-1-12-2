package better112.perf;

import better112.Hooks;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;

/**
 * Skips entities and block entities that sit behind solid blocks. Vanilla only culls by
 * chunk section; a wall of drawers or a sealed machine room in the same section as the
 * player is still drawn in full.
 *
 * Each object is tested by walking a handful of rays from the camera through the block grid.
 * The work is spread over frames under a fixed time budget, and every rule leans towards
 * "visible": a new object is drawn until proven hidden, a visible one stays visible for half
 * a second, hidden ones are rechecked several times a second against a box grown by half a
 * block so they reappear before they come into view.
 */
public final class Occlusion {

    private Occlusion() {}

    /** Client ticks; advanced by ClientTweaks. */
    public static int tick;

    public static int hiddenEntities;
    public static int hiddenTiles;
    private static int hiddenEntitiesNow;
    private static int hiddenTilesNow;

    private static final long BUDGET_NANOS = 400000L;
    private static final double NEAR = 5.0D;
    private static final double FAR_SQ = 96.0D * 96.0D;
    private static final double MAX_SIZE = 8.0D;
    private static final double GROW = 0.5D;
    private static final int VISIBLE_HOLD_TICKS = 10;
    private static final int HIDDEN_RECHECK_TICKS = 3;

    private static final class State {
        boolean hidden;
        int nextCheck;
        int lastQuery;
    }

    private static final Int2ObjectOpenHashMap<State> ENTITIES = new Int2ObjectOpenHashMap<State>();
    private static final Long2ObjectOpenHashMap<State> TILES = new Long2ObjectOpenHashMap<State>();

    private static WorldClient world;
    private static int preparedFrame = -1;
    private static long frameStart;
    private static boolean outOfBudget;
    private static double camX, camY, camZ;
    private static int lastCleanup;

    private static Chunk cachedChunk;
    private static int cachedChunkX, cachedChunkZ;
    private static boolean cachedChunkValid;

    public static boolean isHidden(Entity entity) {
        if (entity instanceof EntityPlayer || entity.ignoreFrustumCheck || entity.isGlowing()
                || entity.isBeingRidden() || entity.isRiding() || entity.getAlwaysRenderNameTagForRender()) {
            return false;
        }
        prepare();
        if (world == null) {
            return false;
        }
        State state = ENTITIES.get(entity.getEntityId());
        if (state == null) {
            state = newState(entity.getEntityId());
            ENTITIES.put(entity.getEntityId(), state);
        }
        state.lastQuery = tick;
        if (tick >= state.nextCheck && !outOfBudget) {
            update(state, entity.getRenderBoundingBox());
        }
        if (state.hidden) {
            hiddenEntitiesNow++;
        }
        return state.hidden;
    }

    public static boolean isHidden(TileEntity te) {
        prepare();
        if (world == null || te.getWorld() != world) {
            return false;
        }
        long key = te.getPos().toLong();
        State state = TILES.get(key);
        if (state == null) {
            state = newState((int) (key ^ (key >>> 32)));
            TILES.put(key, state);
        }
        state.lastQuery = tick;
        if (tick >= state.nextCheck && !outOfBudget) {
            update(state, te.getRenderBoundingBox());
        }
        if (state.hidden) {
            hiddenTilesNow++;
        }
        return state.hidden;
    }

    public static void clear() {
        ENTITIES.clear();
        TILES.clear();
        cachedChunkValid = false;
        cachedChunk = null;
    }

    private static State newState(int salt) {
        State state = new State();
        // Spread first checks out so a freshly loaded base is not tested in a single frame.
        state.nextCheck = tick + 1 + ((salt & 0x7fffffff) % 4);
        return state;
    }

    private static void update(State state, AxisAlignedBB box) {
        boolean visible = isVisible(box);
        state.hidden = !visible;
        int jitter = (System.identityHashCode(state) >>> 4) & 1;
        state.nextCheck = tick + (visible ? VISIBLE_HOLD_TICKS : HIDDEN_RECHECK_TICKS) + jitter;
        if (System.nanoTime() - frameStart > BUDGET_NANOS) {
            outOfBudget = true;
        }
    }

    private static void prepare() {
        int frame = Hooks.frame;
        if (preparedFrame == frame) {
            return;
        }
        preparedFrame = frame;
        hiddenEntities = hiddenEntitiesNow;
        hiddenTiles = hiddenTilesNow;
        hiddenEntitiesNow = 0;
        hiddenTilesNow = 0;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != world) {
            world = mc.world;
            clear();
        }
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null) {
            world = null;
            return;
        }
        Vec3d cam = ActiveRenderInfo.projectViewFromEntity(view, mc.getRenderPartialTicks());
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        frameStart = System.nanoTime();
        outOfBudget = false;
        cachedChunkValid = false;

        if (tick - lastCleanup > 200) {
            lastCleanup = tick;
            prune(ENTITIES.values().iterator());
            prune(TILES.values().iterator());
        }
    }

    private static void prune(ObjectIterator<State> it) {
        while (it.hasNext()) {
            if (tick - it.next().lastQuery > 100) {
                it.remove();
            }
        }
    }

    /** Called when culling is switched off so the counters do not keep their last values. */
    public static void resetStats() {
        hiddenEntities = 0;
        hiddenTiles = 0;
        hiddenEntitiesNow = 0;
        hiddenTilesNow = 0;
    }

    // ------------------------------------------------------------------------------------
    // Visibility test
    // ------------------------------------------------------------------------------------

    private static boolean isVisible(AxisAlignedBB box) {
        double sizeX = box.maxX - box.minX, sizeY = box.maxY - box.minY, sizeZ = box.maxZ - box.minZ;
        if (!(sizeX <= MAX_SIZE && sizeY <= MAX_SIZE && sizeZ <= MAX_SIZE)) {
            return true; // also true for infinite and NaN boxes
        }
        // Distance from the camera to the nearest point of the box.
        double nx = clamp(camX, box.minX, box.maxX) - camX;
        double ny = clamp(camY, box.minY, box.maxY) - camY;
        double nz = clamp(camZ, box.minZ, box.maxZ) - camZ;
        double distSq = nx * nx + ny * ny + nz * nz;
        if (distSq < NEAR * NEAR || distSq > FAR_SQ) {
            return true;
        }

        // Blocks the object itself occupies never count as occluders.
        int ownMinX = floor(box.minX + 1.0E-4D), ownMaxX = floor(box.maxX - 1.0E-4D);
        int ownMinY = floor(box.minY + 1.0E-4D), ownMaxY = floor(box.maxY - 1.0E-4D);
        int ownMinZ = floor(box.minZ + 1.0E-4D), ownMaxZ = floor(box.maxZ - 1.0E-4D);

        double minX = box.minX - GROW, minY = box.minY - GROW, minZ = box.minZ - GROW;
        double maxX = box.maxX + GROW, maxY = box.maxY + GROW, maxZ = box.maxZ + GROW;
        double midX = (minX + maxX) * 0.5D, midY = (minY + maxY) * 0.5D, midZ = (minZ + maxZ) * 0.5D;

        if (rayClear(midX, midY, midZ, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ)) {
            return true;
        }
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? minX : maxX;
            double y = (i & 2) == 0 ? minY : maxY;
            double z = (i & 4) == 0 ? minZ : maxZ;
            if (rayClear(x, y, z, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ)) {
                return true;
            }
        }
        // Face centres catch objects seen through a one-block gap.
        return rayClear(minX, midY, midZ, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ)
                || rayClear(maxX, midY, midZ, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ)
                || rayClear(midX, minY, midZ, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ)
                || rayClear(midX, maxY, midZ, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ)
                || rayClear(midX, midY, minZ, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ)
                || rayClear(midX, midY, maxZ, ownMinX, ownMinY, ownMinZ, ownMaxX, ownMaxY, ownMaxZ);
    }

    /** Walks the block grid from the camera to the target; false if a solid cube is in the way. */
    private static boolean rayClear(double tx, double ty, double tz,
            int ownMinX, int ownMinY, int ownMinZ, int ownMaxX, int ownMaxY, int ownMaxZ) {
        int x = floor(camX), y = floor(camY), z = floor(camZ);
        int endX = floor(tx), endY = floor(ty), endZ = floor(tz);
        double dx = tx - camX, dy = ty - camY, dz = tz - camZ;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double deltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dx);
        double deltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dy);
        double deltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dz);
        double maxX = stepX == 0 ? Double.MAX_VALUE : ((stepX > 0 ? x + 1 - camX : camX - x) * deltaX);
        double maxY = stepY == 0 ? Double.MAX_VALUE : ((stepY > 0 ? y + 1 - camY : camY - y) * deltaY);
        double maxZ = stepZ == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? z + 1 - camZ : camZ - z) * deltaZ);

        for (int guard = 0; guard < 400; guard++) {
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
            double t;
            if (maxX < maxY) {
                if (maxX < maxZ) {
                    t = maxX;
                    x += stepX;
                    maxX += deltaX;
                } else {
                    t = maxZ;
                    z += stepZ;
                    maxZ += deltaZ;
                }
            } else if (maxY < maxZ) {
                t = maxY;
                y += stepY;
                maxY += deltaY;
            } else {
                t = maxZ;
                z += stepZ;
                maxZ += deltaZ;
            }
            if (t > 1.0D) {
                return true;
            }
            if (x >= ownMinX && x <= ownMaxX && y >= ownMinY && y <= ownMaxY && z >= ownMinZ && z <= ownMaxZ) {
                continue;
            }
            if (isOccluder(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOccluder(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return false;
        }
        int chunkX = x >> 4, chunkZ = z >> 4;
        if (!cachedChunkValid || chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
            cachedChunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
            cachedChunkX = chunkX;
            cachedChunkZ = chunkZ;
            cachedChunkValid = true;
        }
        return cachedChunk != null && cachedChunk.getBlockState(x, y, z).isOpaqueCube();
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : v > max ? max : v;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
