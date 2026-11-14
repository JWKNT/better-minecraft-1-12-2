package better112;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import better112.config.BetterConfig;
import better112.core.ChunkAccess;
import better112.core.PatchState;
import better112.core.SpriteAccess;
import better112.perf.Occlusion;
import better112.perf.ParticleGroups;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleItemPickup;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.item.EntityPainting;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.tileentity.TileEntityEnchantmentTable;
import net.minecraft.tileentity.TileEntityPiston;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * Entry points called from patched Minecraft methods (see core.BetterTransformer). These run
 * in the hottest paths of the renderer, so each one starts with a single flag test and only
 * does real work when a setting asks for it.
 */
public final class Hooks {

    private Hooks() {}

    private static final Map<String, java.util.regex.Pattern> PATTERNS = new ConcurrentHashMap<String, java.util.regex.Pattern>();

    /** String.replaceAll, with the pattern compiled once per expression instead of on every call. */
    public static String replaceAll(String text, String regex, String replacement) {
        java.util.regex.Pattern pattern = PATTERNS.get(regex);
        if (pattern == null) {
            if (PATTERNS.size() > 64) {
                PATTERNS.clear();
            }
            pattern = java.util.regex.Pattern.compile(regex);
            PATTERNS.put(regex, pattern);
        }
        return pattern.matcher(text).replaceAll(replacement);
    }

    /** True once the client has left its world: client-side work for the player has nothing to act on. */
    public static boolean noClientPlayer() {
        return net.minecraft.client.Minecraft.getMinecraft().player == null;
    }

    /** Sets a game rule only when its value changes: setting one makes it parse the text as numbers, which throws for "true". */
    public static void setRuleIfChanged(net.minecraft.world.GameRules rules, String key, String value) {
        if (!rules.hasRule(key) || !value.equals(rules.getString(key))) {
            rules.setOrCreateGameRule(key, value);
        }
    }

    /**
     * JourneyMap's chunk piece of a map image, copied as getData() would but into a buffer of the piece's own
     * size: getData() on a piece of an image allocates one as large as the whole image.
     */
    public static java.awt.image.Raster pieceRaster(java.awt.image.BufferedImage piece) {
        java.awt.image.Raster raster = piece.getRaster();
        return piece.getData(new java.awt.Rectangle(raster.getMinX(), raster.getMinY(), piece.getWidth(), piece.getHeight()));
    }

    /** Frames rendered so far; advanced by ClientTweaks at the start of every frame. */
    public static int frame;

    /** Set by another mod's integration when it already limits texture animation. */
    public static volatile String animationsHandledBy;

    // Derived from BetterConfig by refresh() so the hooks test one boolean each.
    private static boolean tileFilter;
    private static boolean entityFilter;
    private static boolean particleFilter;
    private static boolean celestialFilter;
    private static boolean animationCategoryFilter;
    private static boolean trackSprites;
    private static boolean occlusion;

    private static final ResourceLocation SUN = new ResourceLocation("textures/environment/sun.png");
    private static final ResourceLocation MOON = new ResourceLocation("textures/environment/moon_phases.png");
    public static final ResourceLocation EMPTY_TEXTURE = new ResourceLocation("better112", "empty");

    private static ICamera camera;
    private static int cameraFrame = -1;

    private static final Map<Class<?>, Boolean> BLOCKED_PARTICLES = new ConcurrentHashMap<Class<?>, Boolean>();
    private static final Map<TextureAtlasSprite, Byte> ANIMATION_KIND = new IdentityHashMap<TextureAtlasSprite, Byte>();

    private static volatile Thread mainThread;
    private static final BuildContext MAIN_CONTEXT = new BuildContext();
    private static final ThreadLocal<BuildContext> CONTEXT = new ThreadLocal<BuildContext>() {
        @Override
        protected BuildContext initialValue() {
            return new BuildContext();
        }
    };

    /** Recomputes the fast-path flags. Call after any setting changes. */
    public static void refresh() {
        BLOCKED_PARTICLES.clear();
        occlusion = BetterConfig.entityCulling;
        if (!occlusion) {
            Occlusion.resetStats();
        }
        tileFilter = occlusion || !BetterConfig.pistons || !BetterConfig.beaconBeam || !BetterConfig.enchantingBook
                || BetterConfig.blockEntityDistance < 100;
        entityFilter = occlusion || !BetterConfig.itemFrames || !BetterConfig.armorStands || !BetterConfig.paintings;
        particleFilter = !BetterConfig.particles || !BetterConfig.disabledParticles.isEmpty();
        celestialFilter = !BetterConfig.sun || !BetterConfig.moon;
        animationCategoryFilter = !BetterConfig.animWater || !BetterConfig.animLava || !BetterConfig.animFire
                || !BetterConfig.animPortal || !BetterConfig.animBlocks;
        trackSprites = BetterConfig.animateVisibleOnly && animationsHandledBy == null
                && PatchState.isApplied("sprite_animation") && PatchState.isApplied("sprite_getters")
                && PatchState.isApplied("sprite_quads") && PatchState.isApplied("sprite_chunks")
                && PatchState.isApplied("sprite_visible");
    }

    /** Sprites are recreated on every resource reload. */
    public static void clearSpriteCache() {
        ANIMATION_KIND.clear();
    }

    public static void setMainThread(Thread thread) {
        mainThread = thread;
    }

    public static boolean isTrackingSprites() {
        return trackSprites;
    }

    // ------------------------------------------------------------------------------------
    // Block entities and entities
    // ------------------------------------------------------------------------------------

    public static boolean skipTileEntity(TileEntity te) {
        if (!tileFilter || te == null) {
            return false;
        }
        if (te instanceof TileEntityBeacon) {
            if (!BetterConfig.beaconBeam) {
                return true;
            }
            // Beams are far taller than the block; never distance- or occlusion-cull them.
            return false;
        }
        if (!BetterConfig.pistons && te instanceof TileEntityPiston) {
            return true;
        }
        if (!BetterConfig.enchantingBook && te instanceof TileEntityEnchantmentTable) {
            return true;
        }
        int percent = BetterConfig.blockEntityDistance;
        if (percent < 100) {
            double limit = te.getMaxRenderDistanceSquared() * percent * percent / 10000.0D;
            if (te.getDistanceSq(TileEntityRendererDispatcher.staticPlayerX, TileEntityRendererDispatcher.staticPlayerY,
                    TileEntityRendererDispatcher.staticPlayerZ) > limit) {
                return true;
            }
        }
        return occlusion && Occlusion.isHidden(te);
    }

    public static boolean cullEntity(Entity entity) {
        if (!entityFilter || entity == null) {
            return false;
        }
        if (!BetterConfig.itemFrames && entity instanceof EntityItemFrame) {
            return true;
        }
        if (!BetterConfig.armorStands && entity instanceof EntityArmorStand) {
            return true;
        }
        if (!BetterConfig.paintings && entity instanceof EntityPainting) {
            return true;
        }
        if (!occlusion) {
            return false;
        }
        // Leave anything outside the view to the game's own frustum test.
        if (cameraFrame == frame && camera != null && !camera.isBoundingBoxInFrustum(entity.getRenderBoundingBox())) {
            return false;
        }
        return Occlusion.isHidden(entity);
    }

    public static void captureCamera(ICamera cam) {
        camera = cam;
        cameraFrame = frame;
    }

    // ------------------------------------------------------------------------------------
    // Particles
    // ------------------------------------------------------------------------------------

    /** Particle quads can be a few blocks wide, so only cull well outside the view. */
    private static final double PARTICLE_MARGIN = 3.0D;

    public static boolean cullParticle(Particle particle) {
        if (!BetterConfig.particleCulling || cameraFrame != frame || !(camera instanceof Frustum)) {
            return false;
        }
        AxisAlignedBB box = particle.getBoundingBox();
        return !((Frustum) camera).isBoxInFrustum(box.minX - PARTICLE_MARGIN, box.minY - PARTICLE_MARGIN,
                box.minZ - PARTICLE_MARGIN, box.maxX + PARTICLE_MARGIN, box.maxY + PARTICLE_MARGIN,
                box.maxZ + PARTICLE_MARGIN);
    }

    public static boolean blockParticle(Particle particle) {
        if (particle == null) {
            return false;
        }
        if (BetterConfig.hideOwnEffectParticles && particle instanceof net.minecraft.client.particle.ParticleSpell && ownEffect(particle)) {
            ownEffectsHidden++;
            return true;
        }
        if (!particleFilter) {
            return false;
        }
        Class<?> type = particle.getClass();
        Boolean blocked = BLOCKED_PARTICLES.get(type);
        if (blocked == null) {
            blocked = !(particle instanceof ParticleItemPickup)
                    && (!BetterConfig.particles || ParticleGroups.isDisabled(type));
            BLOCKED_PARTICLES.put(type, blocked);
        }
        return blocked;
    }

    public static int ownEffectsHidden;

    /** A potion swirl coming off the player themselves, in first person, where it only gets in the way of seeing. */
    private static boolean ownEffect(Particle particle) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.player == null || mc.gameSettings.thirdPersonView != 0 || mc.getRenderViewEntity() != mc.player) {
            return false;
        }
        AxisAlignedBB box = particle.getBoundingBox();
        double x = (box.minX + box.maxX) / 2.0, y = box.minY, z = (box.minZ + box.maxZ) / 2.0;
        return mc.player.getEntityBoundingBox().grow(0.15).contains(new net.minecraft.util.math.Vec3d(x, y, z));
    }

    public static boolean noBlockBreakParticles() {
        return !BetterConfig.blockBreak || !BetterConfig.particles;
    }

    public static boolean noBlockHitParticles() {
        return !BetterConfig.blockBreaking || !BetterConfig.particles;
    }

    // ------------------------------------------------------------------------------------
    // Sky and weather
    // ------------------------------------------------------------------------------------

    public static boolean noWeather() {
        return !BetterConfig.weather;
    }

    public static boolean noRainSplash() {
        return !BetterConfig.rainSplash || !BetterConfig.weather || !BetterConfig.particles;
    }

    public static boolean fancyWeather(GameSettings settings) {
        int quality = BetterConfig.weatherQuality;
        return quality == 0 ? settings.fancyGraphics : quality == 1;
    }

    public static boolean noSky() {
        return !BetterConfig.sky;
    }

    public static boolean noStars() {
        return !BetterConfig.stars;
    }

    public static ResourceLocation remapTexture(ResourceLocation location) {
        if (!celestialFilter || location == null) {
            return location;
        }
        if (!BetterConfig.sun && location.equals(SUN)) {
            return EMPTY_TEXTURE;
        }
        if (!BetterConfig.moon && location.equals(MOON)) {
            return EMPTY_TEXTURE;
        }
        return location;
    }

    public static boolean overrideCloudHeight() {
        return BetterConfig.cloudHeight != 128;
    }

    public static float cloudHeight() {
        return BetterConfig.cloudHeight;
    }

    // ------------------------------------------------------------------------------------
    // Chunk building
    // ------------------------------------------------------------------------------------

    public static boolean leavesFancy(boolean fancy) {
        int quality = BetterConfig.leavesQuality;
        return quality == 0 ? fancy : quality != 2;
    }

    /**
     * Smart leaves. Fancy leaves draw every face inside a canopy; fast leaves are solid. Smart
     * draws the outer three layers see-through, one solid layer behind them, and nothing
     * deeper, so a canopy looks like fancy from outside without the cost of its insides.
     *
     * This is the "skip this face" half: in the see-through layer, faces with three or more
     * leaf blocks in front of them go; in the solid layer, only faces with exactly three do.
     */
    public static boolean cullLeafFace(net.minecraft.block.Block self, net.minecraft.world.IBlockAccess world,
            net.minecraft.util.math.BlockPos pos, net.minecraft.util.EnumFacing side) {
        if (BetterConfig.leavesQuality != 3 || !isPlainLeaves(self)) {
            return false;
        }
        int dx = side.getXOffset(), dy = side.getYOffset(), dz = side.getZOffset();
        net.minecraft.util.math.BlockPos.MutableBlockPos probe = LEAF_PROBE.get();
        int depth = 0;
        while (depth < 4) {
            int i = depth + 1;
            probe.setPos(pos.getX() + dx * i, pos.getY() + dy * i, pos.getZ() + dz * i);
            if (!(world.getBlockState(probe).getBlock() instanceof net.minecraft.block.BlockLeaves)) {
                break;
            }
            depth++;
        }
        if (net.minecraftforge.client.MinecraftForgeClient.getRenderLayer() == net.minecraft.util.BlockRenderLayer.SOLID) {
            return depth != 3;
        }
        return depth >= 3;
    }

    /** The other half: in smart mode leaves are drawn in both the see-through and the solid layer. */
    public static boolean leavesInLayer(net.minecraft.block.Block block, net.minecraft.block.state.IBlockState state,
            net.minecraft.util.BlockRenderLayer layer) {
        if (BetterConfig.leavesQuality == 3 && isPlainLeaves(block)) {
            return layer == net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED || layer == net.minecraft.util.BlockRenderLayer.SOLID;
        }
        return block.getRenderLayer() == layer;
    }

    private static final Map<Class<?>, Boolean> PLAIN_LEAVES = new ConcurrentHashMap<Class<?>, Boolean>();

    /**
     * True for leaf blocks that leave layer and face choices to the vanilla leaf class. A mod
     * leaf that makes its own choices is left exactly as it is.
     */
    private static boolean isPlainLeaves(net.minecraft.block.Block block) {
        Class<?> type = block.getClass();
        Boolean plain = PLAIN_LEAVES.get(type);
        if (plain == null) {
            plain = PatchState.isApplied("leaves_layer")
                    && declaredInLeaves(type, new Class<?>[0], "getRenderLayer", "func_180664_k")
                    && declaredInLeaves(type, new Class<?>[] {net.minecraft.block.state.IBlockState.class,
                        net.minecraft.util.BlockRenderLayer.class}, "canRenderInLayer")
                    && declaredInLeaves(type, new Class<?>[] {net.minecraft.block.state.IBlockState.class,
                        net.minecraft.world.IBlockAccess.class, net.minecraft.util.math.BlockPos.class,
                        net.minecraft.util.EnumFacing.class}, "shouldSideBeRendered", "func_176225_a");
            PLAIN_LEAVES.put(type, plain);
        }
        return plain;
    }

    private static boolean declaredInLeaves(Class<?> type, Class<?>[] parameters, String... names) {
        for (String name : names) {
            try {
                return type.getMethod(name, parameters).getDeclaringClass() == net.minecraft.block.BlockLeaves.class;
            } catch (NoSuchMethodException e) {
                // try the other name
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static final ThreadLocal<net.minecraft.util.math.BlockPos.MutableBlockPos> LEAF_PROBE =
            new ThreadLocal<net.minecraft.util.math.BlockPos.MutableBlockPos>() {
                @Override
                protected net.minecraft.util.math.BlockPos.MutableBlockPos initialValue() {
                    return new net.minecraft.util.math.BlockPos.MutableBlockPos();
                }
            };

    public static int dynamicLight(int light, net.minecraft.util.math.BlockPos pos) {
        return better112.perf.DynamicLights.apply(light, pos);
    }

    /** Degrees the camera rolls when hurt; vanilla is 14. */
    /** How far into the portal wobble the camera goes; the wobble's shape is kept, only its depth scales. */
    public static float distortion(float timeInPortal) {
        return timeInPortal * BetterConfig.distortionEffects / 100.0F;
    }

    /** The model a block in a chunk is drawn with (Better Grass may swap it); called from BlockRendererDispatcher.renderBlock. */
    public static net.minecraft.client.renderer.block.model.IBakedModel blockModel(net.minecraft.client.renderer.block.model.IBakedModel model,
            net.minecraft.block.state.IBlockState state, net.minecraft.util.math.BlockPos pos, net.minecraft.world.IBlockAccess world) {
        return BetterConfig.betterGrass == 0 ? model : better112.cosmetic.BetterGrass.model(model, state, pos, world);
    }

    // --- Enchantment glint (1.19's Glint Speed and Glint Strength) ---

    /** The clock the item glint scrolls by, run faster or slower. */
    public static long glintTime(long millis) {
        int speed = BetterConfig.glintSpeed;
        return speed == 100 ? millis : millis * speed / 100L;
    }

    /** The item glint's colour (0xFF8040CC), dimmed; the glint is added to what is under it, so darker is fainter. */
    public static int glintColor(int color) {
        int strength = BetterConfig.glintStrength;
        if (strength >= 100) {
            return color;
        }
        int r = (color >> 16 & 255) * strength / 100, g = (color >> 8 & 255) * strength / 100, b = (color & 255) * strength / 100;
        return color & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** The armor glint's clock (ticks lived), run faster or slower. */
    public static float glintArmorTime(float ticks) {
        return ticks * BetterConfig.glintSpeed / 100.0F;
    }

    /** One colour channel of the armor glint, dimmed. */
    public static float glintArmorChannel(float value) {
        return value * Math.min(100, BetterConfig.glintStrength) / 100.0F;
    }

    public static float damageTilt() {
        return 14.0F * BetterConfig.damageTilt / 100.0F;
    }

    // ------------------------------------------------------------------------------------
    // Half GUI scales
    // ------------------------------------------------------------------------------------

    /*
     * The game only knows whole GUI scales: every interface pixel is 2, 3 or 4 screen pixels
     * wide. At 2.5 a pixel would be 2 screen pixels in one place and 3 in the next, which is
     * what makes text look uneven, and everything that measures in "scale factor" (clipping
     * boxes, chat links) is off, because the factor it is told is a whole number.
     *
     * So a half step is drawn the way a screen with fractional scaling draws: the interface
     * is rendered at twice the size (2.5 becomes a true 5, a whole number again) into a
     * picture twice the window's width and height, on top of an enlarged copy of the world,
     * and that picture is reduced 2:1 onto the window. Every interface pixel then covers
     * exactly 2.5 screen pixels, evenly, and for the time it is being drawn the game is
     * told a window size, a mouse position and a scale factor that all agree.
     */

    private static boolean guiPass, guiSharpFailed;
    private static int realWidth, realHeight, realMouseX, realMouseY, maxTextureSize;
    private static net.minecraft.client.shader.Framebuffer guiBuffer, gameBuffer;
    private static java.lang.reflect.Field framebufferField, mouseXField, mouseYField;
    private static boolean guiReflection;

    private static boolean halfStep(int chosen) {
        int halves = BetterConfig.guiScaleHalves;
        return halves > 1 && (halves & 1) == 1 && chosen == (halves + 1) / 2;
    }

    private static boolean sharpHalves(int width, int height) {
        // (The game's settings do not exist yet while the loading screen is up.)
        return BetterConfig.guiScaleSharp && !guiSharpFailed && net.minecraft.client.Minecraft.getMinecraft().gameSettings != null
                && net.minecraft.client.renderer.OpenGlHelper.isFramebufferEnabled()
                && (maxTextureSize == 0 || (width * 2 <= maxTextureSize && height * 2 <= maxTextureSize));
    }

    /**
     * What a ScaledResolution should hold: {scale factor, width, height, width rounded up,
     * height rounded up}. A half step only applies when the game settled on the whole number
     * just above it, i.e. the window has room for it.
     */
    public static double[] guiScaleValues(int chosen) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        int width = guiPass ? realWidth : mc.displayWidth, height = guiPass ? realHeight : mc.displayHeight;
        double scale = chosen;
        int factor = chosen;
        if (guiPass || halfStep(chosen)) {
            int halves = BetterConfig.guiScaleHalves;
            scale = halves / 2.0D;
            // Drawn sharp, the game is told the doubled scale (a whole number); otherwise the whole number above.
            factor = guiPass || sharpHalves(width, height) ? halves : (halves + 1) / 2;
        }
        double w = width / scale, h = height / scale;
        return new double[] {factor, w, h, net.minecraft.util.math.MathHelper.ceil(w), net.minecraft.util.math.MathHelper.ceil(h)};
    }

    /** True while the interface is being drawn at double size. */
    public static boolean inGuiPass() {
        return guiPass;
    }

    private static boolean guiReflect() {
        if (!guiReflection) {
            guiReflection = true;
            try {
                framebufferField = net.minecraftforge.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.Minecraft.class, "framebuffer", "framebufferMc", "field_147124_at");
                framebufferField.setAccessible(true);
            } catch (RuntimeException e) {
                framebufferField = null;
            }
            try {
                mouseXField = org.lwjgl.input.Mouse.class.getDeclaredField("x");
                mouseYField = org.lwjgl.input.Mouse.class.getDeclaredField("y");
                mouseXField.setAccessible(true);
                mouseYField.setAccessible(true);
            } catch (Exception e) {
                mouseXField = null; // hover positions some mods work out for themselves will be off; everything else still works
            }
        }
        return framebufferField != null;
    }

    /**
     * Draws the whole of one framebuffer onto another, enlarged or reduced, and leaves the
     * target bound with its full size as the viewport and the drawing switches as they were.
     * (The graphics card's own picture copy, glBlitFramebuffer, was tried and measured slower
     * here than drawing a textured rectangle.)
     */
    private static void copy(net.minecraft.client.shader.Framebuffer from, net.minecraft.client.shader.Framebuffer to, boolean smooth) {
        boolean alpha = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_ALPHA_TEST), blend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND),
                depth = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST), lighting = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_LIGHTING),
                material = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_COLOR_MATERIAL);
        to.bindFramebuffer(false);
        from.framebufferRenderExt(to.framebufferWidth, to.framebufferHeight, true);
        if (alpha) {
            net.minecraft.client.renderer.GlStateManager.enableAlpha();
        }
        if (blend) {
            net.minecraft.client.renderer.GlStateManager.enableBlend();
        }
        if (depth) {
            net.minecraft.client.renderer.GlStateManager.enableDepth();
        }
        if (lighting) {
            net.minecraft.client.renderer.GlStateManager.enableLighting();
        }
        if (!material) {
            net.minecraft.client.renderer.GlStateManager.disableColorMaterial();
        }
    }

    /** Called just before the game starts drawing its interface (the in-game overlay, then any open screen) over the finished world. */
    public static void guiPassBegin() {
        if (guiPass) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        int halves = BetterConfig.guiScaleHalves, width = mc.displayWidth, height = mc.displayHeight;
        boolean wanted = halves > 1 && (halves & 1) == 1;
        if (wanted && maxTextureSize == 0) {
            maxTextureSize = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
        }
        wanted = wanted && sharpHalves(width, height) && new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor() == halves && guiReflect();
        if (!wanted) {
            if (guiBuffer != null) {
                guiBuffer.deleteFramebuffer();
                guiBuffer = null;
            }
            return;
        }
        try {
            if (guiBuffer == null || guiBuffer.framebufferWidth != width * 2 || guiBuffer.framebufferHeight != height * 2) {
                if (guiBuffer != null) {
                    guiBuffer.deleteFramebuffer();
                }
                guiBuffer = new net.minecraft.client.shader.Framebuffer(width * 2, height * 2, true);
                // Read back with smoothing: two by two of its pixels make one of the window's.
                guiBuffer.setFramebufferFilter(org.lwjgl.opengl.GL11.GL_LINEAR);
            }
            gameBuffer = mc.getFramebuffer();
            // Mods that clip with the stencil buffer (Better Questing's lists) switch one on for the game's
            // picture; the large picture needs one too, or their lists run out of their boxes.
            if (gameBuffer.isStencilEnabled() && !guiBuffer.isStencilEnabled()) {
                guiBuffer.enableStencil();
                guiBuffer.setFramebufferFilter(org.lwjgl.opengl.GL11.GL_LINEAR);
            }
            // The world as drawn so far, enlarged 2:1 (each pixel becomes four, so reducing it again loses nothing).
            copy(gameBuffer, guiBuffer, false);
            framebufferField.set(mc, guiBuffer);
        } catch (Exception e) {
            guiSharpFailed = true;
            guiBuffer = null;
            Better112.LOG.warn("Half GUI scales cannot be drawn sharp here; falling back to the plain way", e);
            if (gameBuffer != null) {
                gameBuffer.bindFramebuffer(true);
            }
            return;
        }
        realWidth = width;
        realHeight = height;
        mc.displayWidth = width * 2;
        mc.displayHeight = height * 2;
        if (mouseXField != null) {
            try {
                realMouseX = mouseXField.getInt(null);
                realMouseY = mouseYField.getInt(null);
                mouseXField.setInt(null, realMouseX * 2);
                mouseYField.setInt(null, realMouseY * 2);
            } catch (Exception e) {
                mouseXField = null;
            }
        }
        guiPass = true;
    }

    /** Called when everything of a frame's interface has been drawn: puts the game's own numbers back and reduces the picture onto the window. */
    public static void guiPassEnd() {
        if (!guiPass) {
            return;
        }
        guiPass = false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        mc.displayWidth = realWidth;
        mc.displayHeight = realHeight;
        if (mouseXField != null) {
            try {
                mouseXField.setInt(null, realMouseX);
                mouseYField.setInt(null, realMouseY);
            } catch (Exception e) {
                mouseXField = null;
            }
        }
        try {
            framebufferField.set(mc, gameBuffer);
        } catch (Exception e) {
            guiSharpFailed = true;
        }
        // Two by two pixels of the large picture make one of the window's: an even average.
        copy(guiBuffer, gameBuffer, true);
    }

    /**
     * Chat links are found from the raw mouse position divided by the scale factor, which is
     * only right for a whole scale. This gives the position that division needs.
     */
    public static int chatMouse(int raw) {
        int halves = BetterConfig.guiScaleHalves;
        if (guiPass || halves <= 1 || (halves & 1) == 0) {
            return raw; // whole scale, or the mouse is already in the doubled picture's numbers
        }
        int told = new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()).getScaleFactor();
        if (told == halves) {
            return raw * 2; // drawn sharp: the factor the game is told is twice the real scale
        }
        // Drawn the plain way: the game is told the whole number above the real scale.
        return told == (halves + 1) / 2 ? raw * told * 2 / halves : raw;
    }

    public static int chunkThreads(Runtime runtime) {
        int threads = BetterConfig.chunkThreads;
        return threads > 0 ? threads : runtime.availableProcessors();
    }

    public static int blendMin() {
        return -BetterConfig.biomeBlend;
    }

    public static int blendMax() {
        return BetterConfig.biomeBlend;
    }

    public static int blendArea() {
        int side = BetterConfig.biomeBlend * 2 + 1;
        return side * side;
    }

    // ------------------------------------------------------------------------------------
    // Texture animation
    // ------------------------------------------------------------------------------------

    private static final byte KIND_WATER = 1, KIND_LAVA = 2, KIND_FIRE = 3, KIND_PORTAL = 4, KIND_BLOCK = 5;

    public static boolean skipAnimation(TextureAtlasSprite sprite) {
        if (!BetterConfig.animations) {
            return true;
        }
        if (animationCategoryFilter && !kindEnabled(sprite)) {
            return true;
        }
        return trackSprites && frame - ((SpriteAccess) sprite).better112_getLastUse() > 1;
    }

    private static boolean kindEnabled(TextureAtlasSprite sprite) {
        Byte kind = ANIMATION_KIND.get(sprite);
        if (kind == null) {
            String name = sprite.getIconName();
            byte k = KIND_BLOCK;
            if (name != null && name.startsWith("minecraft:blocks/")) {
                String rest = name.substring("minecraft:blocks/".length());
                if (rest.startsWith("water_")) {
                    k = KIND_WATER;
                } else if (rest.startsWith("lava_")) {
                    k = KIND_LAVA;
                } else if (rest.startsWith("fire_layer_")) {
                    k = KIND_FIRE;
                } else if (rest.equals("portal")) {
                    k = KIND_PORTAL;
                }
            }
            kind = k;
            ANIMATION_KIND.put(sprite, kind);
        }
        switch (kind) {
            case KIND_WATER:
                return BetterConfig.animWater;
            case KIND_LAVA:
                return BetterConfig.animLava;
            case KIND_FIRE:
                return BetterConfig.animFire;
            case KIND_PORTAL:
                return BetterConfig.animPortal;
            default:
                return BetterConfig.animBlocks;
        }
    }

    /** A sprite's UVs were read: it is about to be drawn, or meshed into a chunk. */
    public static void spriteUsed(TextureAtlasSprite sprite) {
        if (trackSprites && sprite.hasAnimationMetadata()) {
            markUsed(sprite);
        }
    }

    public static void quadUsed(BakedQuad quad) {
        if (!trackSprites) {
            return;
        }
        TextureAtlasSprite sprite = quad.getSprite();
        if (sprite != null && sprite.hasAnimationMetadata()) {
            markUsed(sprite);
        }
    }

    private static void markUsed(TextureAtlasSprite sprite) {
        BuildContext context = Thread.currentThread() == mainThread ? MAIN_CONTEXT : CONTEXT.get();
        if (context.active) {
            context.add(sprite);
        }
        ((SpriteAccess) sprite).better112_setLastUse(frame);
    }

    public static void beginChunkBuild(RenderChunk chunk) {
        if (!trackSprites) {
            return;
        }
        BuildContext context = Thread.currentThread() == mainThread ? MAIN_CONTEXT : CONTEXT.get();
        context.active = true;
        context.count = 0;
    }

    public static void endChunkBuild(RenderChunk chunk) {
        BuildContext context = Thread.currentThread() == mainThread ? MAIN_CONTEXT : CONTEXT.get();
        if (!context.active) {
            return;
        }
        context.active = false;
        // A build that bailed out early saw nothing; keep what the visible mesh was built from.
        if (context.count > 0 && chunk instanceof ChunkAccess) {
            Object[] sprites = new Object[context.count];
            System.arraycopy(context.sprites, 0, sprites, 0, context.count);
            ((ChunkAccess) chunk).better112_setSprites(sprites);
        }
    }

    /** A chunk mesh is being drawn this frame: keep its animated sprites running. */
    public static void chunkVisible(RenderChunk chunk) {
        if (!trackSprites) {
            return;
        }
        Object[] sprites = ((ChunkAccess) chunk).better112_getSprites();
        if (sprites != null) {
            int now = frame;
            for (Object sprite : sprites) {
                ((SpriteAccess) sprite).better112_setLastUse(now);
            }
        }
    }

    private static final class BuildContext {
        boolean active;
        int count;
        Object[] sprites = new Object[8];

        void add(Object sprite) {
            for (int i = 0; i < count; i++) {
                if (sprites[i] == sprite) {
                    return;
                }
            }
            if (count == sprites.length) {
                Object[] grown = new Object[count * 2];
                System.arraycopy(sprites, 0, grown, 0, count);
                sprites = grown;
            }
            sprites[count++] = sprite;
        }
    }

    /** A cosmetic sky colour is set (menu, Colors page). */
    public static boolean hasSkyColor() {
        return better112.cosmetic.WorldColors.sky() >= 0;
    }

    public static int skyColor() {
        return better112.cosmetic.WorldColors.sky();
    }
}
