package better112.shader;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import better112.Better112;
import better112.Hooks;
import better112.config.BetterConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBHalfFloatPixel;
import org.lwjgl.opengl.ARBTextureFloat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.NVTextureBarrier;

/**
 * The mod's own shaders: no shader pack and no OptiFine, GLSL programs that take over the
 * drawing of the terrain (light, sun shadows, swaying plants, water) and two passes over the
 * finished picture (bloom and a colour grade).
 *
 * The game calls in at four places (see the patches "shader_*"): before and after each terrain
 * layer, in place of the loop that draws the chunk sections of a layer, and once the world is
 * drawn. Everything else the game draws (mobs, block entities, particles, the hand) is left to
 * it. Off by default; Video Settings, Shaders page.
 */
public final class Shaders {

    private Shaders() {}

    // ---------------------------------------------------------------- settings

    public static final int OFF = 0, LIGHT = 1, INTENSE = 2, CUSTOM = 3;

    /** Effective settings, from the preset or (Custom) from BetterConfig. */
    static boolean lighting, waving, bloomOn, rays, ao;
    static int shadows;       // 0 off, 1 near, 2 far, 3 very far
    static int water;         // 0 the game's own, 1 waves and sky, 2 with reflections of the world
    static float bloomStrength, saturation, contrast, vignette, shadeSky;
    static float[] torchTint = {1, 1, 1};
    static float tintStrength;

    /** Why the shaders are not running although asked for, or null. */
    public static volatile String problem;

    private static boolean failed;
    private static int settingsVersion = -1, builtVersion = -2;

    /**
     * What the Light and Intense presets set, as the Custom settings would: lighting, shadows (0-3), swaying,
     * water (0-2), light shafts, ambient occlusion, bloom percent, colour grade percent.
     */
    private static final int[] LIGHT_VALUES = {1, 0, 1, 1, 0, 0, 25, 40};
    private static final int[] INTENSE_VALUES = {1, 2, 1, 2, 1, 1, 45, 70};

    /** The value a preset gives a setting (0 lighting, 1 shadows, 2 swaying, 3 water, 4 shafts, 5 occlusion, 6 bloom, 7 grade). */
    public static int presetValue(int preset, int which) {
        if (preset == LIGHT) {
            return LIGHT_VALUES[which];
        }
        if (preset == INTENSE) {
            return INTENSE_VALUES[which];
        }
        return 0;
    }

    /** Reads the settings again; called whenever the settings are applied. */
    public static void refresh() {
        int level = BetterConfig.shaders;
        int[] v;
        if (level == LIGHT || level == INTENSE) {
            v = level == LIGHT ? LIGHT_VALUES : INTENSE_VALUES;
        } else {
            v = new int[] {BetterConfig.shaderLighting ? 1 : 0, BetterConfig.shaderShadows, BetterConfig.shaderWaving ? 1 : 0,
                    BetterConfig.shaderWater, BetterConfig.shaderRays ? 1 : 0, BetterConfig.shaderAo ? 1 : 0, BetterConfig.shaderBloom,
                    BetterConfig.shaderColor};
        }
        lighting = v[0] != 0;
        shadows = Math.max(0, Math.min(3, v[1]));
        waving = v[2] != 0;
        water = Math.max(0, Math.min(2, v[3]));
        rays = v[4] != 0;
        ao = v[5] != 0;
        bloomOn = v[6] > 0;
        bloomStrength = v[6] / 100.0F;
        saturation = 1.0F + v[7] / 700.0F;
        contrast = v[7] / 250.0F;
        vignette = v[7] / 200.0F;
        // Light keeps more of the sky's light in shade and tints the light less; Intense and Custom go further.
        shadeSky = level == LIGHT ? 0.85F : 0.62F;
        tintStrength = level == LIGHT ? 0.55F : 1.0F;
        torchTint = level == LIGHT ? new float[] {1.06F, 0.98F, 0.88F} : new float[] {1.18F, 0.94F, 0.72F};
        if (!lighting) {
            shadows = 0;
            rays = false;
        }
        settingsVersion++;
        failed = false;
        problem = null;
        Hooks.shadersOn = level != OFF && supported();
        Sway.active = Hooks.shadersOn && waving;
    }

    /** Hardware and other mods allow it. */
    static boolean supported() {
        if (!OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled()) {
            problem = "needs framebuffers and GLSL, which this graphics driver does not offer";
            return false;
        }
        if (FMLClientHandler.instance().hasOptifine()) {
            problem = "OptiFine is installed; use its shaders";
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- GL resources

    private static final Map<String, Program> PROGRAMS = new HashMap<String, Program>();
    private static ShadowMap shadow;
    private static Target sceneDepth, sceneColor, finalCopy, aoTarget, raysTarget;
    /** 0 until the bloom targets hold a picture (new targets, new settings). */
    private static int bloomFresh;
    private static Target[] bloom;
    private static int targetWidth, targetHeight;
    private static boolean targetStencil;

    private static void deleteAll() {
        Vaos.deleteAll();
        for (Program p : PROGRAMS.values()) {
            p.delete();
        }
        PROGRAMS.clear();
        if (shadow != null) {
            shadow.delete();
            shadow = null;
        }
        deleteTargets();
    }

    private static void deleteTargets() {
        for (Target t : new Target[] {sceneDepth, sceneColor, finalCopy, aoTarget, raysTarget}) {
            if (t != null) {
                t.delete();
            }
        }
        sceneDepth = sceneColor = finalCopy = aoTarget = raysTarget = null;
        if (bloom != null) {
            for (Target t : bloom) {
                t.delete();
            }
            bloom = null;
        }
        targetWidth = targetHeight = 0;
        bloomFresh = 0;
    }

    /** Compiles what the current settings need; false (and the shaders switched off with a reason) when that fails. */
    private static boolean ready() {
        if (failed) {
            return false;
        }
        if (builtVersion != settingsVersion) {
            deleteAll();
            builtVersion = settingsVersion;
            try {
                if (AtlasMask.texture == 0) {
                    AtlasMask.build(Minecraft.getMinecraft().getTextureMapBlocks());
                }
                for (BlockRenderLayer layer : BlockRenderLayer.values()) {
                    terrain(layer);
                }
                if (shadows > 0) {
                    shadow(false);
                    shadow(true);
                    shadow = new ShadowMap(shadowSize(), shadowRadius());
                }
                if (bloomOn || saturation != 1.0F || contrast > 0 || vignette > 0) {
                    program("quad", "final", bloomOn ? "#define BLOOM\n" : "");
                }
                if (ao) {
                    program("quad", "ao", "");
                    program("quad", "ao_apply", "");
                }
                if (rays) {
                    program("quad", "rays", "");
                    program("quad", "rays_apply", "");
                }
                if (bloomOn) {
                    program("quad", "bloom_down", "#define FIRST\n");
                    program("quad", "bloom_down", "");
                    program("quad", "bloom_up", "");
                }
                restoreMainTarget();
                Better112.LOG.info("Shaders ready: lighting {}, shadows {}, waving {}, water {}, bloom {}; {}", lighting, shadows, waving,
                        water, bloomOn, AtlasMask.summary);
            } catch (RuntimeException e) {
                fail(e);
                return false;
            }
        }
        return true;
    }

    private static void fail(Throwable e) {
        failed = true;
        problem = "could not start: " + e.getMessage();
        Better112.LOG.error("Shaders switched off", e);
        try {
            GL20.glUseProgram(0);
            deleteAll();
            restoreMainTarget();
        } catch (RuntimeException ignored) {
            // leave the rest to the game
        }
        Hooks.shadersOn = false;
        Sway.active = false;
    }

    private static Program program(String vertex, String fragment, String defines) {
        String key = vertex + "|" + fragment + "|" + defines;
        Program p = PROGRAMS.get(key);
        if (p == null) {
            String common = "#define SHADOW_DISTORT 0.8\n#define SHADOW_DEPTH 192.0\n"
                    + "#define SHADOW_RADIUS " + shadowRadius() + "\n"
                    + "#define SHADOW_SIZE " + shadowSize() + ".0\n";
            if (!defines.contains("SSR_STEPS")) {
                common += "#define SSR_STEPS 0\n";
            }
            p = Program.create(vertex, fragment, common + defines);
            PROGRAMS.put(key, p);
        }
        return p;
    }

    /** Shadow picture size and reach for the shadow setting (1 near, 2 far, 3 very far). */
    static int shadowSize() {
        return shadows <= 1 ? 1024 : shadows == 2 ? 2048 : 4096;
    }

    static double shadowRadius() {
        return shadows <= 1 ? 64.0 : shadows == 2 ? 112.0 : 176.0;
    }

    private static boolean vboChunks() {
        return OpenGlHelper.useVbo();
    }

    private static Program terrain(BlockRenderLayer layer) {
        StringBuilder d = new StringBuilder();
        boolean translucent = layer == BlockRenderLayer.TRANSLUCENT;
        d.append(translucent ? "#define LAYER_TRANSLUCENT\n" : layer == BlockRenderLayer.SOLID ? "#define LAYER_SOLID\n" : "#define LAYER_CUTOUT\n");
        if (lighting) {
            d.append("#define LIGHTING\n");
        }
        if (shadows > 0) {
            d.append("#define SHADOWS\n");
        }
        if (lighting || (translucent && water > 0) || waving) {
            d.append("#define MASK\n");
        }
        if (offsetAttribute) {
            d.append("#define OFFSET_ATTRIBUTE\n");
        }
        if (waving && !translucent) {
            d.append("#define WAVING\n");
        }
        if (translucent && water > 0 && Target.canBlit()) {
            d.append("#define WATER\n");
            d.append("#define SSR_STEPS ").append(water >= 2 ? 22 : 0).append("\n");
        }
        return program("terrain", "terrain", d.toString());
    }

    private static Program shadow(boolean cutout) {
        return program("shadow", "shadow", cutout ? "#define ALPHA_TEST\n" : "");
    }

    /** The game's picture is bound again, as the whole window. */
    static void restoreMainTarget() {
        Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
        if (main != null && OpenGlHelper.isFramebufferEnabled()) {
            main.bindFramebuffer(true);
        } else {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        }
    }

    private static void ensureTargets(Framebuffer main) {
        int w = main.framebufferWidth, h = main.framebufferHeight;
        boolean stencil = main.isStencilEnabled();
        if (w == targetWidth && h == targetHeight && stencil == targetStencil && finalCopy != null) {
            return;
        }
        deleteTargets();
        targetWidth = w;
        targetHeight = h;
        targetStencil = stencil;
        sceneDepth = Target.depth(w, h, stencil, false);
        sceneColor = Target.color(w, h, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
        finalCopy = Target.color(w, h, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
        if (ao) {
            aoTarget = Target.color(Math.max(1, w / 2), Math.max(1, h / 2), GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
        }
        if (rays) {
            raysTarget = Target.color(Math.max(1, w / 4), Math.max(1, h / 4), GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
        }
        if (bloomOn) {
            boolean floats = GLContext.getCapabilities().GL_ARB_texture_float && GLContext.getCapabilities().GL_ARB_half_float_pixel;
            // From a quarter of the picture's size down to a thirty-second: the first level reads four smoothed
            // taps that together cover the 4x4 pixels under each of its own.
            bloom = new Target[4];
            int bw = w / 2, bh = h / 2;
            for (int i = 0; i < bloom.length; i++) {
                bw = Math.max(1, bw / 2);
                bh = Math.max(1, bh / 2);
                bloom[i] = floats
                        ? Target.color(bw, bh, ARBTextureFloat.GL_RGBA16F_ARB, GL11.GL_RGBA, ARBHalfFloatPixel.GL_HALF_FLOAT_ARB)
                        : Target.color(bw, bh, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            }
        }
        restoreMainTarget();
    }

    // ---------------------------------------------------------------- per frame

    static int frame;
    private static boolean frameStarted;
    private static Program bound;
    private static BlockRenderLayer boundLayer;
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer VIEW_INVERSE = BufferUtils.createFloatBuffer(16);
    private static final float[] MV = new float[16];
    private static double originX, originY, originZ;
    private static final double[] SUN = new double[3], LIGHT_DIR = new double[3];
    private static final float[] LIGHT_VIEW = new float[3];
    private static final float[] SKY = new float[3];
    private static float strength, wind, time, eyeInWater;
    private static boolean skyLit;

    /** How far the sun's path leans towards the south, as shader packs do, so noon shadows are not straight down. */
    static final float SUN_TILT = 22.0F;

    /** The tilt for the sky being drawn now: none for a dimension that draws its own sky. */
    public static float skyTilt() {
        Minecraft mc = Minecraft.getMinecraft();
        return Hooks.shadersOn && shadows > 0 && mc.world != null && mc.world.provider.getSkyRenderer() == null ? SUN_TILT : 0.0F;
    }

    /** Head of RenderGlobal.renderBlockLayer(layer, partialTicks, pass, entity). */
    public static void beginLayer(BlockRenderLayer layer, double partialTicks, Entity entity) {
        if (!ready()) {
            return;
        }
        try {
            if (layer == BlockRenderLayer.SOLID || !frameStarted) {
                GpuTimer.begin(GpuTimer.SETUP);
                startFrame((float) partialTicks, entity);
                GpuTimer.end();
                updateShadow();
            }
            Program p = terrain(layer);
            boolean translucent = layer == BlockRenderLayer.TRANSLUCENT;
            if (translucent && wantsDepth()) {
                beforeTranslucent();
            }
            GpuTimer.begin(translucent ? GpuTimer.TRANSLUCENT : GpuTimer.TERRAIN);
            p.use();
            terrainUniforms(p, translucent);
            bound = p;
            boundLayer = layer;
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** End of RenderGlobal.renderBlockLayer. */
    public static void endLayer(BlockRenderLayer layer) {
        if (bound != null) {
            GL20.glUseProgram(0);
            bound = null;
            boundLayer = null;
            GpuTimer.end();
        }
    }

    private static void startFrame(float partialTicks, Entity entity) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        frame++;
        frameStarted = true;
        depthCopied = false;
        if (entity == null) {
            entity = mc.getRenderViewEntity();
        }
        originX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        originY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        originZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        GlStateManager.getFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GlStateManager.getFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        MODELVIEW.rewind();
        MODELVIEW.get(MV);
        MODELVIEW.rewind();
        float[] inv = Matrix.invert(MV);
        VIEW_INVERSE.clear();
        VIEW_INVERSE.put(inv).flip();

        // Sun and moon.
        float angle = world.getCelestialAngle(partialTicks) * (float) (Math.PI * 2.0);
        double tilt = Math.toRadians(skyTilt());
        SUN[0] = -Math.sin(angle);
        SUN[1] = Math.cos(angle) * Math.cos(tilt);
        SUN[2] = Math.cos(angle) * Math.sin(tilt);
        boolean day = SUN[1] > 0;
        for (int i = 0; i < 3; i++) {
            LIGHT_DIR[i] = day ? SUN[i] : -SUN[i];
        }
        float elevation = (float) Math.abs(SUN[1]);
        float rain = world.getRainStrength(partialTicks), thunder = world.getThunderStrength(partialTicks);
        skyLit = world.provider.hasSkyLight() && !world.provider.isNether();
        float rise = smooth(0.0F, 0.14F, elevation);
        strength = skyLit ? (day ? rise : rise * 0.42F) * (1.0F - 0.88F * rain) * (1.0F - 0.5F * thunder) : 0.0F;
        // Camera-space light direction.
        for (int i = 0; i < 3; i++) {
            LIGHT_VIEW[i] = (float) (MV[i] * LIGHT_DIR[0] + MV[4 + i] * LIGHT_DIR[1] + MV[8 + i] * LIGHT_DIR[2]);
        }
        normalize(LIGHT_VIEW);
        Vec3d sky = world.getSkyColor(entity, partialTicks);
        set(SKY, (float) sky.x, (float) sky.y, (float) sky.z);
        wind = 1.0F + rain * 0.8F + thunder * 0.8F;
        time = (float) (((world.getTotalWorldTime() % 24000L) + partialTicks) / 20.0D);
        eyeInWater = entity.isInsideOfMaterial(net.minecraft.block.material.Material.WATER) ? 1.0F : 0.0F;

        eyeHeight = entity.getEyeHeight();
    }

    private static float eyeHeight;

    /** Once per frame, after the frame's numbers are known: a slice of the shadow picture under way, if any. */
    private static void updateShadow() {
        if (shadow != null && skyLit && strength > 0.0F && vboChunks()) {
            GpuTimer.begin(GpuTimer.SHADOW);
            shadow.update(LIGHT_DIR, originX, originY + eyeHeight, originZ, shadow(false), shadow(true), frame);
            GpuTimer.end();
            restoreMainTarget();
        }
    }

    private static void terrainUniforms(Program p, boolean translucent) {
        p.set("fogMode", fogMode());
        if (offsetAttribute) {
            int attribute = p.attribute("chunkOffsetA");
            if (attribute >= 0) {
                GL20.glVertexAttrib3f(attribute, 0.0F, 0.0F, 0.0F);
            }
        }
        if (p.frameSet == frame) {
            return; // the same program again this frame (the two cutout layers): the rest is already set
        }
        p.frameSet = frame;
        p.set("tex", 0);
        p.set("lightmap", 1);
        p.set("maskTex", 2);
        p.set("shadowTex", 3);
        p.set("sceneDepth", 4);
        p.set("sceneColor", 5);
        p.set("chunkOffset", 0.0F, 0.0F, 0.0F);
        p.setMatrix("viewInverse", VIEW_INVERSE);
        p.set("originWrap", (float) (originX % 4096.0), (float) (originY % 4096.0), (float) (originZ % 4096.0));
        p.set("time", time);
        p.set("wind", wind);
        p.set("maskSize", AtlasMask.width, AtlasMask.height);
        p.set("lightDir", LIGHT_VIEW);
        p.set("sunElev", (float) SUN[1]);
        p.set("tintAmount", tintStrength);
        p.set("torchTint", torchTint);
        p.set("shadowStrength", strength);
        p.set("shadeSky", shadeSky);
        if (shadow != null && shadow.ready) {
            p.setMatrix("shadowMatrix", shadow.matrix);
            p.set("shadowShift", (float) (originX - shadow.originX), (float) (originY - shadow.originY), (float) (originZ - shadow.originZ));
            p.set("shadowTexel", 1.0F / shadow.size);
        } else if (shadow != null) {
            p.set("shadowShift", 1.0E6F, 0.0F, 0.0F); // nothing drawn yet: everything reads as outside the picture, so lit
        }
        if (translucent) {
            Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
            p.set("screenSize", main.framebufferWidth, main.framebufferHeight);
            p.set("skyColor", SKY);
            p.set("horizonColor", fogColor());
            p.set("eyeInWater", eyeInWater);
            p.set("lightDirWorld", (float) LIGHT_DIR[0], (float) LIGHT_DIR[1], (float) LIGHT_DIR[2]);
        }
        bindExtra(2, AtlasMask.texture);
        if (shadow != null) {
            bindExtra(3, shadow.texture());
        }
        if (translucent && sceneDepth != null) {
            bindExtra(4, sceneDepth.depth);
            bindExtra(5, sceneColor.color);
        }
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void bindExtra(int unit, int texture) {
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + unit);
        GlStateManager.bindTexture(texture);
    }

    private static final FloatBuffer FOG = BufferUtils.createFloatBuffer(16);

    private static float[] fogColor() {
        FOG.clear();
        GL11.glGetFloat(GL11.GL_FOG_COLOR, FOG);
        return new float[] {FOG.get(0), FOG.get(1), FOG.get(2)};
    }

    private static float fogMode() {
        if (!GL11.glIsEnabled(GL11.GL_FOG)) {
            return 0.0F;
        }
        int mode = GL11.glGetInteger(GL11.GL_FOG_MODE);
        return mode == GL11.GL_LINEAR ? 1.0F : mode == GL11.GL_EXP ? 2.0F : 3.0F;
    }

    /** Something reads the depth of the opaque world: water, ambient occlusion or light shafts. */
    private static boolean wantsDepth() {
        return Target.canBlit() && (water > 0 || ao || rays);
    }

    /**
     * Before water: the depth of what is drawn so far is copied (water's depth below its surface, the ambient
     * occlusion and the light shafts read it), the ambient occlusion darkens it, and then its colour is copied
     * for water to see through and reflect.
     */
    private static void beforeTranslucent() {
        Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
        ensureTargets(main);
        GpuTimer.begin(GpuTimer.COPY);
        // (glCopyTexSubImage2D from the bound picture measured twice the game-thread time of this blit.)
        Target.blit(main.framebufferObject, sceneDepth.fbo, targetWidth, targetHeight, GL11.GL_DEPTH_BUFFER_BIT);
        depthCopied = true;
        restoreMainTarget();
        if (ao && aoTarget != null) {
            ambientOcclusion(main);
        }
        if (water >= 2) {
            Target.blit(main.framebufferObject, sceneColor.fbo, targetWidth, targetHeight, GL11.GL_COLOR_BUFFER_BIT);
        }
        GpuTimer.end();
        restoreMainTarget();
    }

    private static boolean depthCopied;

    /** Saves the switches a full-picture pass changes; {@link #endPass} puts them back. */
    private static boolean savedDepthTest, savedBlend, savedAlpha, savedCull, savedFog, savedDepthMask;

    private static void beginPass() {
        savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        savedAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        savedFog = GL11.glIsEnabled(GL11.GL_FOG);
        savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableFog();
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void endPass() {
        GL20.glUseProgram(0);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        if (savedDepthTest) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
        GlStateManager.depthMask(savedDepthMask);
        if (savedBlend) {
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        if (savedAlpha) {
            GlStateManager.enableAlpha();
        }
        if (savedCull) {
            GlStateManager.enableCull();
        }
        if (savedFog) {
            GlStateManager.enableFog();
        }
    }

    private static final FloatBuffer PROJ_INVERSE = BufferUtils.createFloatBuffer(16);

    private static void ambientOcclusion(Framebuffer main) {
        Program pass = PROGRAMS.get("quad|ao|"), apply = PROGRAMS.get("quad|ao_apply|");
        if (pass == null || apply == null) {
            return;
        }
        int blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC), blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        beginPass();
        float[] proj = new float[16];
        PROJECTION.rewind();
        PROJECTION.get(proj);
        PROJECTION.rewind();
        PROJ_INVERSE.clear();
        PROJ_INVERSE.put(Matrix.invert(proj)).flip();
        aoTarget.bind();
        pass.use();
        pass.set("depthTex", 0);
        pass.set("texel", 1.0F / targetWidth, 1.0F / targetHeight);
        pass.setMatrix("projInverse", PROJ_INVERSE);
        pass.set("pixelsPerUnit", proj[5] * targetHeight * 0.5F);
        GlStateManager.bindTexture(sceneDepth.depth);
        quad();
        main.bindFramebuffer(true);
        apply.use();
        apply.set("aoTex", 0);
        apply.set("depthTex", 2);
        apply.set("aoTexel", 1.0F / aoTarget.width, 1.0F / aoTarget.height);
        bindExtra(2, sceneDepth.depth);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(aoTarget.color);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_DST_COLOR, GL11.GL_ZERO);
        quad();
        GlStateManager.blendFunc(blendSrc, blendDst);
        endPass();
    }

    /** After everything of the world but the hand: light shafts from the sun. */
    public static void beforeHand() {
        if (!frameStarted || failed || !rays || raysTarget == null || !depthCopied || strength <= 0.0F || SUN[1] <= 0.0) {
            return;
        }
        Program pass = PROGRAMS.get("quad|rays|"), apply = PROGRAMS.get("quad|rays_apply|");
        if (pass == null || apply == null) {
            return;
        }
        // The sun on the screen: its direction through the camera's rotation and the projection.
        double vx = MV[0] * SUN[0] + MV[4] * SUN[1] + MV[8] * SUN[2];
        double vy = MV[1] * SUN[0] + MV[5] * SUN[1] + MV[9] * SUN[2];
        double vz = MV[2] * SUN[0] + MV[6] * SUN[1] + MV[10] * SUN[2];
        float[] proj = new float[16];
        PROJECTION.rewind();
        PROJECTION.get(proj);
        PROJECTION.rewind();
        double cx = proj[0] * vx + proj[4] * vy + proj[8] * vz;
        double cy = proj[1] * vx + proj[5] * vy + proj[9] * vz;
        double cw = proj[3] * vx + proj[7] * vy + proj[11] * vz;
        // Facing: 1 looking straight at the sun, 0 at right angles or away.
        double facing = -vz;
        if (facing <= 0.05 || cw <= 0.0) {
            return;
        }
        float sx = (float) (cx / cw * 0.5 + 0.5), sy = (float) (cy / cw * 0.5 + 0.5);
        // Under water the open sky above the surface would read as clear sky everywhere: a third of the shafts only.
        float amount = (float) (Math.pow(facing, 1.5) * strength * 0.4 * (eyeInWater > 0.5F ? 0.3 : 1.0));
        try {
            Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
            int blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC), blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
            GpuTimer.begin(GpuTimer.RAYS);
            beginPass();
            raysTarget.bind();
            pass.use();
            pass.set("depthTex", 0);
            pass.set("sunPos", sx, sy);
            GlStateManager.bindTexture(sceneDepth.depth);
            quad();
            main.bindFramebuffer(true);
            apply.use();
            apply.set("raysTex", 0);
            apply.set("sunPos", sx, sy);
            apply.set("strength", amount);
            apply.set("sunElev", (float) SUN[1]);
            apply.set("tintAmount", tintStrength);
            apply.set("aspect", (float) targetWidth / targetHeight);
            GlStateManager.bindTexture(raysTarget.color);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
            quad();
            GlStateManager.blendFunc(blendSrc, blendDst);
            endPass();
            GpuTimer.end();
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    // ---------------------------------------------------------------- chunk sections

    private static Field renderChunks, viewX, viewY, viewZ, initialized;
    private static boolean chunkFieldsFailed;
    private static Object hammerFeatures;

    /**
     * In place of VboRenderList.renderChunkLayer while a terrain program is bound: the same
     * draws, with each section's position handed to the program instead of moving the game's
     * matrix for every section (a push, a translate, a multiply and a pop that the driver has
     * to turn into new matrices each time).
     */
    public static boolean drawChunks(ChunkRenderContainer container, BlockRenderLayer layer) {
        if (bound == null || layer != boundLayer || !vboChunks() || chunkFieldsFailed || !ownLoop) {
            return false;
        }
        try {
            if (renderChunks == null) {
                renderChunks = ReflectionHelper.findField(ChunkRenderContainer.class, "renderChunks", "field_178009_a");
                viewX = ReflectionHelper.findField(ChunkRenderContainer.class, "viewEntityX", "field_178008_c");
                viewY = ReflectionHelper.findField(ChunkRenderContainer.class, "viewEntityY", "field_178005_d");
                viewZ = ReflectionHelper.findField(ChunkRenderContainer.class, "viewEntityZ", "field_178006_e");
                initialized = ReflectionHelper.findField(ChunkRenderContainer.class, "initialized", "field_178007_b");
                for (Field f : new Field[] {renderChunks, viewX, viewY, viewZ, initialized}) {
                    f.setAccessible(true);
                }
                try {
                    // HammerCore can post an event before each section; when a mod has asked for it, the game's loop must run.
                    hammerFeatures = Class.forName("com.zeitheron.hammercore.asm.McHooks").getField("FEATURES").get(null);
                } catch (Throwable notThere) {
                    hammerFeatures = null;
                }
            }
            if (hammerFeatures instanceof boolean[] && ((boolean[]) hammerFeatures)[0]) {
                return false;
            }
            if (!initialized.getBoolean(container)) {
                return true;
            }
            @SuppressWarnings("unchecked")
            List<RenderChunk> list = (List<RenderChunk>) renderChunks.get(container);
            double vx = viewX.getDouble(container), vy = viewY.getDouble(container), vz = viewZ.getDouble(container);
            int offset = bound.uniform("chunkOffset");
            int attribute = offsetAttribute ? bound.attribute("chunkOffsetA") : -1;
            int ordinal = layer.ordinal();
            boolean vao = Vaos.usable();
            for (int i = 0, n = list.size(); i < n; i++) {
                RenderChunk rc = list.get(i);
                VertexBuffer vb = rc.getVertexBufferByLayer(ordinal);
                BlockPos pos = rc.getPosition();
                if (attribute >= 0) {
                    GL20.glVertexAttrib3f(attribute, (float) (pos.getX() - vx), (float) (pos.getY() - vy), (float) (pos.getZ() - vz));
                } else {
                    GL20.glUniform3f(offset, (float) (pos.getX() - vx), (float) (pos.getY() - vy), (float) (pos.getZ() - vz));
                }
                if (vao) {
                    Vaos.bind(vb);
                } else {
                    vb.bindBuffer();
                    GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, 28, 0);
                    GlStateManager.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12);
                    GlStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16);
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
                    GlStateManager.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24);
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                }
                vb.drawArrays(GL11.GL_QUADS);
            }
            if (vao) {
                Vaos.unbind();
            }
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            GlStateManager.resetColor();
            list.clear();
            GL20.glUniform3f(offset, 0.0F, 0.0F, 0.0F);
            if (attribute >= 0) {
                GL20.glVertexAttrib3f(attribute, 0.0F, 0.0F, 0.0F);
            }
            chunksDrawn += 1;
            return true;
        } catch (ReflectiveOperationException e) {
            chunkFieldsFailed = true;
            Better112.LOG.warn("Shaders: drawing chunk sections the game's way", e);
            return false;
        } catch (RuntimeException e) {
            // Whatever went wrong, the game's own loop draws this layer and the shaders switch off.
            Vaos.unbind();
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            fail(e);
            return false;
        }
    }

    static int chunksDrawn;

    /**
     * Experiments for the self-test (shaderexp:NAME:on|off). The section's position goes to the program as a
     * constant vertex attribute: on a Mac, changing a uniform before every draw cost the game thread about a
     * millisecond a frame more than the game's own matrix per section, the attribute about the same or less.
     */
    static boolean ownLoop = true, offsetAttribute = true, quadBuffer;

    public static String experiment(String name, boolean on) {
        switch (name) {
            case "ownloop":
                ownLoop = on;
                break;
            case "attrib":
                offsetAttribute = on;
                settingsVersion++;
                break;
            case "quadvbo":
                quadBuffer = on;
                break;
            default:
                return "unknown experiment " + name;
        }
        return "experiment " + name + " " + on;
    }

    /** A chunk section got a new mesh: the shadow picture is redrawn if it shows that place. */
    public static void chunkCompiled(RenderChunk rc) {
        ShadowMap s = shadow;
        if (s != null && s.ready) {
            BlockPos p = rc.getPosition();
            if (s.covers(p.getX() + 8, p.getY() + 8, p.getZ() + 8)) {
                s.dirty = true;
            }
        }
    }

    // ---------------------------------------------------------------- the finished picture

    /** After the game has drawn the world (hand included), before anything of the interface. */
    public static void afterWorld() {
        if (!frameStarted) {
            return;
        }
        frameStarted = false;
        Program fin = PROGRAMS.get("quad|final|" + (bloomOn ? "#define BLOOM\n" : ""));
        if (fin == null || failed) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Framebuffer main = mc.getFramebuffer();
            ensureTargets(main);
            GpuTimer.begin(GpuTimer.POST);
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND),
                    alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                    fog = GL11.glIsEnabled(GL11.GL_FOG);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.disableFog();
            GlStateManager.colorMask(true, true, true, true);

            // With a texture barrier the grade reads the very picture it writes (each pixel only its own), so the
            // picture need not be copied first; bloom reads it before that, while another target is bound.
            boolean inPlace = barrier();
            int source = main.framebufferTexture;
            if (!inPlace) {
                Target.blit(main.framebufferObject, finalCopy.fbo, targetWidth, targetHeight, GL11.GL_COLOR_BUFFER_BIT);
                source = finalCopy.color;
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            // Bloom is worked out every other frame (a glow a frame behind is not seen above a hundred frames a
            // second); the grade below uses the last one every frame.
            if (bloomOn && bloom != null && (bloomFresh == 0 || (frame & 1) == 0)) {
                runBloom(source);
                bloomFresh = 1;
            }
            main.bindFramebuffer(true);
            if (inPlace) {
                NVTextureBarrier.glTextureBarrierNV();
            }
            fin.use();
            fin.set("scene", 0);
            fin.set("bloomTex", 2);
            fin.set("bloomStrength", bloomStrength);
            fin.set("saturation", saturation);
            fin.set("contrast", contrast);
            fin.set("vignette", vignette);
            fin.set("sunElev", (float) SUN[1]);
            fin.set("tintAmount", skyLit ? tintStrength : 0.0F); // no sun or moon in the Nether's or the End's colour
            if (bloomOn && bloom != null) {
                bindExtra(2, bloom[0].color);
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            }
            GlStateManager.bindTexture(source);
            quad();
            GL20.glUseProgram(0);

            if (depthTest) {
                GlStateManager.enableDepth();
            }
            GlStateManager.depthMask(depthMask);
            if (blend) {
                GlStateManager.enableBlend();
            }
            if (alpha) {
                GlStateManager.enableAlpha();
            }
            if (cull) {
                GlStateManager.enableCull();
            }
            if (fog) {
                GlStateManager.enableFog();
            }
            GpuTimer.end();
            GpuTimer.frameDone();
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** Self-test switch: grade the picture in place (texture barrier) or from a copy. */
    static boolean useBarrier = !"false".equals(System.getProperty("better112.shaderBarrier"));

    private static boolean barrier() {
        ContextCapabilities caps = GLContext.getCapabilities();
        return useBarrier && (caps.GL_NV_texture_barrier || caps.GL_ARB_texture_barrier);
    }

    public static void setBarrier(boolean on) {
        useBarrier = on;
    }

    private static void runBloom(int source) {
        Program first = PROGRAMS.get("quad|bloom_down|#define FIRST\n");
        Program down = PROGRAMS.get("quad|bloom_down|");
        Program up = PROGRAMS.get("quad|bloom_up|");
        first.use();
        first.set("source", 0);
        first.set("depthTex", 3);
        first.set("hasDepth", depthCopied ? 1.0F : 0.0F);
        first.set("texel", 1.0F / targetWidth, 1.0F / targetHeight);
        if (depthCopied) {
            bindExtra(3, sceneDepth.depth);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
        bloom[0].bind();
        GlStateManager.bindTexture(source);
        // Read with smoothing, so each tap averages four pixels; the game's picture is read without it otherwise.
        int filter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        quad();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        down.use();
        down.set("source", 0);
        for (int i = 1; i < bloom.length; i++) {
            bloom[i].bind();
            down.set("texel", 1.0F / bloom[i - 1].width, 1.0F / bloom[i - 1].height);
            GlStateManager.bindTexture(bloom[i - 1].color);
            quad();
        }
        up.use();
        up.set("source", 0);
        up.set("weight", 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
        for (int i = bloom.length - 1; i > 0; i--) {
            bloom[i - 1].bind();
            up.set("texel", 1.0F / bloom[i].width, 1.0F / bloom[i].height);
            GlStateManager.bindTexture(bloom[i].color);
            quad();
        }
        GlStateManager.disableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static int quadVbo;

    private static void quad() {
        if (quadBuffer) {
            if (quadVbo == 0) {
                quadVbo = OpenGlHelper.glGenBuffers();
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, quadVbo);
                java.nio.ByteBuffer data = BufferUtils.createByteBuffer(32);
                data.asFloatBuffer().put(new float[] {-1, -1, 1, -1, 1, 1, -1, 1});
                OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, data, 35044);
            } else {
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, quadVbo);
            }
            boolean colors = GL11.glIsEnabled(GL11.GL_COLOR_ARRAY), texcoords = GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY),
                    vertices = GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY), normals = GL11.glIsEnabled(GL11.GL_NORMAL_ARRAY);
            if (colors) {
                GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            }
            if (texcoords) {
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            }
            if (normals) {
                GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            }
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glVertexPointer(2, GL11.GL_FLOAT, 8, 0L);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);
            if (!vertices) {
                GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            }
            if (colors) {
                GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            }
            if (texcoords) {
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            }
            if (normals) {
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            }
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            return;
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1.0F, -1.0F);
        GL11.glVertex2f(1.0F, -1.0F);
        GL11.glVertex2f(1.0F, 1.0F);
        GL11.glVertex2f(-1.0F, 1.0F);
        GL11.glEnd();
    }

    /** The block atlas was stitched again (resource reload): the material mask follows it. */
    public static void atlasStitched(TextureMap map) {
        if (map == Minecraft.getMinecraft().getTextureMapBlocks() && (Hooks.shadersOn || AtlasMask.texture != 0)) {
            AtlasMask.build(map);
        }
    }

    /** Self-test switch for the vertex array objects. */
    public static void setVaos(boolean on) {
        Vaos.enabled = on;
        if (!on) {
            Vaos.deleteAll();
        }
    }

    /** Reloads every program (for tuning with -Dbetter112.shaderDir). */
    public static void reloadPrograms() {
        settingsVersion++;
        failed = false;
        problem = null;
    }

    /** Whether the shaders can run in this game at all (graphics driver, OptiFine). */
    public static boolean possible() {
        if (!OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled()) {
            problem = "needs framebuffers and GLSL, which this graphics driver does not offer";
            return false;
        }
        if (FMLClientHandler.instance().hasOptifine()) {
            problem = "OptiFine is installed; use its shaders";
            return false;
        }
        return true;
    }

    /** The line under the Shaders option: what is running, or why not. */
    public static String note() {
        if (BetterConfig.shaders == OFF) {
            return null;
        }
        if (problem != null) {
            return "Off: " + problem + ".";
        }
        if (!OpenGlHelper.useVbo()) {
            return "Turn on Use VBOs (Performance page) for shadows and swaying plants.";
        }
        return null;
    }

    /** One line for the self-test and the settings screen. */
    public static String info() {
        StringBuilder b = new StringBuilder();
        b.append("preset ").append(BetterConfig.shaders).append(", on ").append(Hooks.shadersOn);
        if (problem != null) {
            b.append(", problem: ").append(problem);
        }
        b.append(", lighting ").append(lighting).append(", shadows ").append(shadows).append(", waving ").append(waving)
                .append(", water ").append(water).append(", bloom ").append(bloomOn);
        if (shadow != null) {
            b.append(", shadow map ").append(shadow.size).append(" over ").append((int) shadow.radius).append(" blocks, ready ")
                    .append(shadow.ready).append(", pictures drawn ").append(shadow.cyclesDone).append(" (last took ")
                    .append(shadow.lastCycleFrames).append(" frames, ").append(shadow.queueSize()).append(" sections, ")
                    .append(shadow.skippedBuried).append(" buried ones left out)");
        }
        b.append(", strength ").append(String.format(java.util.Locale.ROOT, "%.2f", strength));
        b.append(", ").append(AtlasMask.summary);
        b.append(", own chunk loop ").append(chunksDrawn > 0 ? "used" : "not used").append(", vertex array objects ")
                .append(Vaos.usable() ? Vaos.count() : "off");
        return b.toString();
    }

    // ---------------------------------------------------------------- small maths

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0.0F, Math.min(1.0F, (x - a) / (b - a)));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float lerp(float neutral, float value, float amount) {
        return neutral + (value - neutral) * amount;
    }

    private static void set(float[] v, float x, float y, float z) {
        v[0] = x;
        v[1] = y;
        v[2] = z;
    }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 0) {
            v[0] /= l;
            v[1] /= l;
            v[2] /= l;
        }
    }
}
