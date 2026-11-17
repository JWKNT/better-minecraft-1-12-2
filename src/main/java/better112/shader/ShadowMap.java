package better112.shader;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Sun shadows from a depth picture of the terrain seen from the sun.
 *
 * Terrain hardly ever changes from one frame to the next, so the picture is not redrawn every
 * frame as shader packs do. It is drawn again only when something it shows has moved: the sun
 * by a fraction of a degree, the player a few blocks from where it was centred, or a chunk
 * section in reach rebuilt. And then not in one go: a new picture is drawn into a second
 * texture a slice of chunk sections per frame, while the finished one is used, and the two
 * swap when it is complete. The cost per frame is therefore a small, steady number of draws,
 * and nothing at all while standing still at noon.
 */
final class ShadowMap {

    /** Fraction of a degree the light may move before a new picture is started. */
    private static final double ANGLE_STEP = Math.toRadians(0.15);

    final int size;
    final double radius;       // blocks from the centre to the picture's edge
    private Target front, back;

    // The finished picture: what it was drawn from.
    boolean ready;
    double originX, originY, originZ;
    final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private final double[] lightDir = new double[3];

    // The picture being drawn.
    private boolean drawing;
    private double nextX, nextY, nextZ;
    private final FloatBuffer nextMatrix = BufferUtils.createFloatBuffer(16);
    private final double[] nextDir = new double[3];
    private final List<RenderChunk> queue = new ArrayList<RenderChunk>();
    private int queued;
    private int drawsPerFrame = 160;

    volatile boolean dirty = true;
    /** Frames a rebuilt chunk section waits before it starts a new picture. */
    private static final int DIRTY_GAP = 30;
    private int finishedFrame;
    int cyclesDone;
    int lastCycleFrames;
    private int cycleStartFrame;

    private static Field frustumField;

    ShadowMap(int size, double radius) {
        this.size = size;
        this.radius = radius;
        front = Target.depth(size, size, false, true);
        back = Target.depth(size, size, false, true);
    }

    int texture() {
        return front.depth;
    }

    void delete() {
        front.delete();
        back.delete();
    }

    /** True when the point (absolute coordinates) is close enough to the picture's centre that a rebuilt chunk there shows in it. */
    boolean covers(double x, double y, double z) {
        double dx = x - originX, dz = z - originZ;
        return dx * dx + dz * dz < (radius + 24) * (radius + 24);
    }

    /**
     * Once per frame, before terrain is drawn. {@code dir} points at the light; {@code eye} is the
     * camera in absolute coordinates. Draws one slice of a new picture if one is under way.
     */
    void update(double[] dir, double eyeX, double eyeY, double eyeZ, Program solid, Program cutout, int frame) {
        if (!drawing) {
            double move = Math.max(Math.abs(eyeX - originX), Math.max(Math.abs(eyeY - originY) * 0.5, Math.abs(eyeZ - originZ)));
            double dot = dir[0] * lightDir[0] + dir[1] * lightDir[1] + dir[2] * lightDir[2];
            boolean turned = Math.acos(Math.min(1.0, dot)) > ANGLE_STEP;
            // A rebuilt chunk section (the game rebuilds several a second while you move and chunks load) waits a
            // short while, so a burst of them costs one new picture; the sun or the player moving does not wait.
            boolean waited = frame - finishedFrame >= DIRTY_GAP;
            if (!ready || turned || move > Math.max(4.0, radius * 0.06) || dirty && waited) {
                begin(dir, eyeX, eyeY, eyeZ, frame);
            }
        }
        if (drawing) {
            slice(solid, cutout);
            if (queued >= queue.size()) {
                Target t = front;
                front = back;
                back = t;
                originX = nextX;
                originY = nextY;
                originZ = nextZ;
                matrix.clear();
                nextMatrix.rewind();
                matrix.put(nextMatrix).flip();
                nextMatrix.rewind();
                System.arraycopy(nextDir, 0, lightDir, 0, 3);
                ready = true;
                drawing = false;
                queue.clear();
                cyclesDone++;
                lastCycleFrames = frame - cycleStartFrame + 1;
                finishedFrame = frame;
            }
        }
    }

    private void begin(double[] dir, double eyeX, double eyeY, double eyeZ, int frame) {
        dirty = false;
        drawing = true;
        cycleStartFrame = frame;
        // Centre on whole blocks so the picture does not shimmer from frame to frame of the same cycle.
        nextX = Math.floor(eyeX);
        nextY = Math.floor(eyeY);
        nextZ = Math.floor(eyeZ);
        System.arraycopy(dir, 0, nextDir, 0, 3);
        lightMatrix(dir, radius, nextMatrix);
        queue.clear();
        queued = 0;
        ViewFrustum frustum = frustum();
        skippedBuried = 0;
        if (frustum != null && frustum.renderChunks != null) {
            net.minecraft.client.multiplayer.WorldClient world = Minecraft.getMinecraft().world;
            java.util.HashMap<Long, Integer> buried = new java.util.HashMap<Long, Integer>();
            float[] m = new float[16];
            nextMatrix.rewind();
            nextMatrix.get(m);
            nextMatrix.rewind();
            for (RenderChunk rc : frustum.renderChunks) {
                CompiledChunk compiled = rc.compiledChunk;
                if (compiled == null || compiled.isEmpty() || (compiled.isLayerEmpty(BlockRenderLayer.SOLID)
                        && compiled.isLayerEmpty(BlockRenderLayer.CUTOUT_MIPPED) && compiled.isLayerEmpty(BlockRenderLayer.CUTOUT))) {
                    continue;
                }
                BlockPos p = rc.getPosition();
                // A section below the lowest ground of its chunk and of the eight around it is buried on every side: it
                // neither sees the sun nor throws a shadow anywhere the sun reaches, so it is left out of the picture.
                if (world != null && p.getY() + 16 < buriedBelow(world, p.getX() >> 4, p.getZ() >> 4, buried)) {
                    skippedBuried++;
                    continue;
                }
                float cx = (float) (p.getX() + 8 - nextX), cy = (float) (p.getY() + 8 - nextY), cz = (float) (p.getZ() + 8 - nextZ);
                // Chunk section centre in the light's picture space (before distortion); 14 blocks covers a section's corners.
                float sx = m[0] * cx + m[4] * cy + m[8] * cz + m[12];
                float sy = m[1] * cx + m[5] * cy + m[9] * cz + m[13];
                float reach = (float) (14.0 / radius);
                if (Math.abs(sx) > 1.0F + reach || Math.abs(sy) > 1.0F + reach) {
                    continue;
                }
                queue.add(rc);
            }
            // Nearest first, so a half-finished new picture is never missing what is closest.
            final double ox = nextX, oz = nextZ;
            queue.sort((a, b) -> Double.compare(dist2(a, ox, oz), dist2(b, ox, oz)));
            lastQueue = queue.size();
        }
        back.bind();
        GlStateManager.depthMask(true);
        GL11.glClearDepth(1.0D);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        Shaders.restoreMainTarget();
    }

    int skippedBuried;
    private int lastQueue;

    int queueSize() {
        return lastQueue;
    }

    /** The lowest ground height over a chunk and the eight around it (0 where one is not loaded). */
    private static int buriedBelow(net.minecraft.client.multiplayer.WorldClient world, int chunkX, int chunkZ,
            java.util.HashMap<Long, Integer> cache) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        Integer known = cache.get(key);
        if (known != null) {
            return known;
        }
        int lowest = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1 && lowest > 0; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                net.minecraft.world.chunk.Chunk c = world.getChunkProvider().getLoadedChunk(chunkX + dx, chunkZ + dz);
                int h = c == null || c.isEmpty() ? 0 : c.getLowestHeight();
                lowest = Math.min(lowest, h);
            }
        }
        cache.put(key, lowest);
        return lowest;
    }

    private static double dist2(RenderChunk rc, double x, double z) {
        BlockPos p = rc.getPosition();
        double dx = p.getX() + 8 - x, dz = p.getZ() + 8 - z;
        return dx * dx + dz * dz;
    }

    /** Draws the next sections of the queue into the picture under way. */
    private void slice(Program solid, Program cutout) {
        if (queued >= queue.size()) {
            return;
        }
        int end = Math.min(queue.size(), queued + drawsPerFrame);
        back.bind();
        GlStateManager.colorMask(false, false, false, false);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.disableCull();
        GlStateManager.disableBlend();
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(1.1F, 4.0F);
        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        boolean vao = Vaos.usable();
        for (int pass = 0; pass < 2; pass++) {
            Program program = pass == 0 ? solid : cutout;
            program.use();
            program.setMatrix("shadowMatrix", nextMatrix);
            program.set("tex", 0);
            // The section's place as a constant vertex attribute: far cheaper per draw on a Mac than a uniform.
            int offset = program.attribute("chunkOffsetA");
            for (int i = queued; i < end; i++) {
                RenderChunk rc = queue.get(i);
                CompiledChunk compiled = rc.compiledChunk;
                BlockPos p = rc.getPosition();
                boolean any = false;
                for (BlockRenderLayer layer : pass == 0 ? SOLID : CUTOUTS) {
                    if (compiled.isLayerEmpty(layer)) {
                        continue;
                    }
                    VertexBuffer vb = rc.getVertexBufferByLayer(layer.ordinal());
                    if (vb == null) {
                        continue;
                    }
                    if (!any) {
                        GL20.glVertexAttrib3f(offset, (float) (p.getX() - nextX), (float) (p.getY() - nextY), (float) (p.getZ() - nextZ));
                        any = true;
                    }
                    if (vao) {
                        Vaos.bind(vb);
                    } else {
                        vb.bindBuffer();
                        GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, 28, 0);
                        GlStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16);
                    }
                    vb.drawArrays(GL11.GL_QUADS);
                }
            }
        }
        queued = end;
        // Constant attribute values are shared by every program: leave them at zero for whatever draws next.
        for (Program program : new Program[] {solid, cutout}) {
            int attribute = program.attribute("chunkOffsetA");
            if (attribute >= 0) {
                GL20.glVertexAttrib3f(attribute, 0.0F, 0.0F, 0.0F);
            }
        }
        if (vao) {
            Vaos.unbind();
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GL20.glUseProgram(0);
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.disablePolygonOffset();
        GlStateManager.enableCull();
        GlStateManager.colorMask(true, true, true, true);
        Shaders.restoreMainTarget();
    }

    private static final BlockRenderLayer[] SOLID = {BlockRenderLayer.SOLID};
    private static final BlockRenderLayer[] CUTOUTS = {BlockRenderLayer.CUTOUT_MIPPED, BlockRenderLayer.CUTOUT};

    private static ViewFrustum frustum() {
        try {
            if (frustumField == null) {
                frustumField = ReflectionHelper.findField(RenderGlobal.class, "viewFrustum", "field_175008_n");
                frustumField.setAccessible(true);
            }
            return (ViewFrustum) frustumField.get(Minecraft.getMinecraft().renderGlobal);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The light's view and an orthographic box of half-width {@code radius}, column-major, for points
     * given from the picture's centre: x and y across the picture, z the depth from the light (towards
     * the light is nearer).
     */
    static void lightMatrix(double[] dir, double radius, FloatBuffer out) {
        double zx = dir[0], zy = dir[1], zz = dir[2];
        // The sun moves east to west, so north (0, 0, 1) is never along it.
        double ux = 0, uy = 0, uz = 1;
        double xx = uy * zz - uz * zy, xy = uz * zx - ux * zz, xz = ux * zy - uy * zx;
        double xl = Math.sqrt(xx * xx + xy * xy + xz * xz);
        xx /= xl;
        xy /= xl;
        xz /= xl;
        double yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
        double depth = 192.0;
        float[] m = new float[16];
        // Row 0: x / radius; row 1: y / radius; row 2: -z / depth; row 3: 1.
        m[0] = (float) (xx / radius);
        m[4] = (float) (xy / radius);
        m[8] = (float) (xz / radius);
        m[1] = (float) (yx / radius);
        m[5] = (float) (yy / radius);
        m[9] = (float) (yz / radius);
        m[2] = (float) (-zx / depth);
        m[6] = (float) (-zy / depth);
        m[10] = (float) (-zz / depth);
        m[15] = 1.0F;
        out.clear();
        out.put(m).flip();
    }
}
