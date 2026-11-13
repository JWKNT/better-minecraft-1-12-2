package better112.perf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import better112.config.BetterConfig;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.ForgeModContainer;

/**
 * Light from things that carry it: a torch in the hand, a dropped glowstone block, a mob on
 * fire. Modern packs get this from LambDynamicLights and older ones from OptiFine.
 *
 * Nothing is added to the world's light data. Every "how bright is this spot" lookup the
 * renderer makes passes through {@link #apply}, which raises the block light near a source,
 * and the chunk meshes around a source are rebuilt when it has moved far enough to matter.
 */
public final class DynamicLights {

    private DynamicLights() {}

    /**
     * How far a light reaches, in blocks per light level: a torch (14) reaches just under
     * eight blocks, the distance LambDynamicLights uses. Keeping the reach under half a chunk
     * is what keeps this cheap: a moving light then touches about seven chunk sections
     * instead of twenty-seven.
     */
    private static final float REACH = 0.55F;

    private static final class Source {
        final double x, y, z;
        final float level;
        final double radiusSq;

        Source(double x, double y, double z, float level) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.level = level;
            double r = level * REACH;
            this.radiusSq = r * r;
        }
    }

    private static final class Tracked {
        double x, y, z;
        float level;
        int updatedAt;
        boolean seen;
    }

    private static final Source[] NONE = new Source[0];
    /** Read from chunk builder threads, replaced whole from the client thread. */
    private static volatile Source[] sources = NONE;

    private static final Int2ObjectOpenHashMap<Tracked> TRACKED = new Int2ObjectOpenHashMap<Tracked>();
    private static final Map<Long, Integer> ITEM_LEVELS = new HashMap<Long, Integer>();
    private static final double RANGE_SQ = 56.0D * 56.0D;
    private static final int MAX_SOURCES = 24;
    private static int tick;

    /** The player's own "defer chunk updates" choice, kept while lights force it on. */
    private static boolean forcingDefer;
    private static boolean userDefer;

    public static boolean active() {
        return sources.length > 0;
    }

    public static int sourceCount() {
        return sources.length;
    }

    /** Raises the block-light part of a packed light value near a source. */
    public static int apply(int light, BlockPos pos) {
        Source[] list = sources;
        if (list.length == 0) {
            return light;
        }
        double px = pos.getX() + 0.5D, py = pos.getY() + 0.5D, pz = pos.getZ() + 0.5D;
        float best = 0.0F;
        for (Source s : list) {
            double dx = s.x - px, dy = s.y - py, dz = s.z - pz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < s.radiusSq) {
                // Full strength at the source, fading faster toward the edge.
                float value = s.level * (float) (1.0D - d2 / s.radiusSq);
                if (value > best) {
                    best = value;
                }
            }
        }
        if (best <= 0.0F) {
            return light;
        }
        int dynamic = Math.min(240, (int) (best * 16.0F));
        return dynamic > (light & 0xFFFF) ? (light & 0xFFFF0000) | dynamic : light;
    }

    /** Once per client tick: find the sources and rebuild chunks around those that moved. */
    public static void tick() {
        tick++;
        Minecraft mc = Minecraft.getMinecraft();
        int mode = BetterConfig.dynamicLights;
        if (mode == 0 || mc.world == null || mc.getRenderViewEntity() == null) {
            if (!TRACKED.isEmpty() || sources.length > 0) {
                // Redrawn without their light, unless the world has been left: then its chunks are gone too.
                if (mc.world != null) {
                    for (Tracked t : TRACKED.values()) {
                        markDirty(mc, t.x, t.y, t.z, t.level);
                    }
                }
                TRACKED.clear();
                sources = NONE;
            }
            setDeferForced(false);
            return;
        }
        // Fast refreshes the light a little over twice a second; fancy follows closely.
        double moveSq = mode == 2 ? 0.25D : 1.0D;
        int interval = mode == 2 ? 2 : 8;

        for (Tracked t : TRACKED.values()) {
            t.seen = false;
        }
        Entity view = mc.getRenderViewEntity();
        boolean changed = false;
        List<Entity> entities = mc.world.loadedEntityList;
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            float level = levelOf(entity);
            if (level <= 0.0F || entity.getDistanceSq(view) > RANGE_SQ) {
                continue;
            }
            double x = entity.posX, y = entity.posY + entity.height * 0.6D, z = entity.posZ;
            Tracked t = TRACKED.get(entity.getEntityId());
            if (t == null) {
                if (TRACKED.size() >= MAX_SOURCES) {
                    continue;
                }
                t = new Tracked();
                t.x = x;
                t.y = y;
                t.z = z;
                t.level = level;
                t.updatedAt = tick;
                TRACKED.put(entity.getEntityId(), t);
                markDirty(mc, x, y, z, level);
                changed = true;
            } else {
                double dx = x - t.x, dy = y - t.y, dz = z - t.z;
                boolean moved = dx * dx + dy * dy + dz * dz >= moveSq || Math.abs(level - t.level) >= 1.0F;
                if (moved && tick - t.updatedAt >= interval) {
                    markDirty(mc, t.x, t.y, t.z, t.level);
                    t.x = x;
                    t.y = y;
                    t.z = z;
                    t.level = level;
                    t.updatedAt = tick;
                    markDirty(mc, x, y, z, level);
                    changed = true;
                }
            }
            t.seen = true;
        }
        for (Iterator<Int2ObjectMap.Entry<Tracked>> it = TRACKED.int2ObjectEntrySet().iterator(); it.hasNext();) {
            Tracked t = it.next().getValue();
            if (!t.seen) {
                markDirty(mc, t.x, t.y, t.z, t.level);
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            List<Source> list = new ArrayList<Source>(TRACKED.size());
            for (Tracked t : TRACKED.values()) {
                list.add(new Source(t.x, t.y, t.z, t.level));
            }
            sources = list.isEmpty() ? NONE : list.toArray(new Source[0]);
        }
        // Vanilla rebuilds nearby chunks on the render thread, which would hitch every step
        // taken with a torch out. While a source exists, rebuilds go to the builder threads.
        setDeferForced(!TRACKED.isEmpty());
    }

    private static void setDeferForced(boolean force) {
        if (force == forcingDefer) {
            return;
        }
        if (force) {
            userDefer = ForgeModContainer.alwaysSetupTerrainOffThread;
            ForgeModContainer.alwaysSetupTerrainOffThread = true;
        } else {
            ForgeModContainer.alwaysSetupTerrainOffThread = userDefer;
        }
        forcingDefer = force;
    }

    /** What "Always Defer Chunk Updates" is really set to, whatever lights are doing right now. */
    public static boolean userDefer() {
        return forcingDefer ? userDefer : ForgeModContainer.alwaysSetupTerrainOffThread;
    }

    public static void setUserDefer(boolean value) {
        if (forcingDefer) {
            userDefer = value;
        } else {
            ForgeModContainer.alwaysSetupTerrainOffThread = value;
        }
    }

    /** Chunk sections queued for a rebuild since the game started; read by the self-test. */
    public static int sectionsMarked;

    /** Queues a rebuild of every chunk section the light at this spot reaches. */
    private static void markDirty(Minecraft mc, double x, double y, double z, float level) {
        if (mc.world == null) {
            return; // no world, no chunks to redraw: the renderer has let go of them
        }
        // One block more than the reach: a block face is lit by the air in front of it.
        double r = level * REACH + 1.0D;
        int x0 = MathHelper.floor(x - r) >> 4, x1 = MathHelper.floor(x + r) >> 4;
        int y0 = Math.max(0, MathHelper.floor(y - r) >> 4), y1 = Math.min(15, MathHelper.floor(y + r) >> 4);
        int z0 = MathHelper.floor(z - r) >> 4, z1 = MathHelper.floor(z + r) >> 4;
        for (int sx = x0; sx <= x1; sx++) {
            for (int sy = y0; sy <= y1; sy++) {
                for (int sz = z0; sz <= z1; sz++) {
                    // The light is a ball, so the corners of that box are usually out of reach.
                    double dx = x - MathHelper.clamp(x, sx << 4, (sx << 4) + 16);
                    double dy = y - MathHelper.clamp(y, sy << 4, (sy << 4) + 16);
                    double dz = z - MathHelper.clamp(z, sz << 4, (sz << 4) + 16);
                    if (dx * dx + dy * dy + dz * dz <= r * r) {
                        int bx = sx << 4, by = sy << 4, bz = sz << 4;
                        // The method widens the range by one block; this marks exactly one section.
                        mc.renderGlobal.markBlockRangeForRenderUpdate(bx + 1, by + 1, bz + 1, bx + 14, by + 14, bz + 14);
                        sectionsMarked++;
                    }
                }
            }
        }
    }

    private static float levelOf(Entity entity) {
        if (entity.isDead) {
            return 0.0F;
        }
        float level = 0.0F;
        if (entity instanceof EntityItem) {
            level = levelOf(((EntityItem) entity).getItem());
        } else if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            if (entity instanceof EntityPlayer || !living.getHeldItemMainhand().isEmpty() || !living.getHeldItemOffhand().isEmpty()) {
                level = Math.max(levelOf(living.getHeldItemMainhand()), levelOf(living.getHeldItemOffhand()));
            }
        }
        if (entity.isBurning()) {
            level = Math.max(level, 12.0F);
        }
        return level;
    }

    /** How much light an item gives off when carried: its block's light, or a short list of glowing items. */
    public static int levelOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        long key = ((long) Item.getIdFromItem(item) << 16) | (stack.getMetadata() & 0xFFFF);
        Integer cached = ITEM_LEVELS.get(key);
        if (cached != null) {
            return cached;
        }
        int level = 0;
        try {
            Block block = Block.getBlockFromItem(item);
            if (block != Blocks.AIR) {
                try {
                    level = block.getStateFromMeta(stack.getMetadata()).getLightValue();
                } catch (Throwable t) {
                    level = block.getDefaultState().getLightValue();
                }
            } else if (item == Items.LAVA_BUCKET) {
                level = 15;
            } else if (item == Items.BLAZE_ROD || item == Items.BLAZE_POWDER) {
                level = 10;
            } else if (item == Items.GLOWSTONE_DUST || item == Items.NETHER_STAR || item == Items.MAGMA_CREAM
                    || item == Items.FIRE_CHARGE) {
                level = 8;
            }
        } catch (Throwable t) {
            level = 0;
        }
        level = Math.max(0, Math.min(15, level));
        ITEM_LEVELS.put(key, level);
        return level;
    }
}
