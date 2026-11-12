package better112.cosmetic;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.imageio.ImageIO;

import better112.Better112;
import better112.config.BetterConfig;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.client.renderer.texture.PngSizeInfo;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeColorHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Cosmetic colours for the world: the sky (with the horizon haze to match) and water.
 * Either can stay as the game makes it, take a preset, or take any colour from the picker
 * in the menu.
 *
 * The sky colour replaces the biome's sky tint, so night, rain and thunder still darken
 * it. Water is drawn from a colourless copy of the water texture while a custom colour is
 * on, because the stock texture is already blue and could only be tinted darker.
 */
public final class WorldColors {

    private WorldColors() {}

    public static final int DEFAULT = 0, CUSTOM = 1;

    /** Sky presets after "Default" and "Custom": name key, sky, horizon haze. */
    public static final String[] SKY_NAMES = {"azure", "arctic", "golden", "sunset", "rose", "dusk", "mint", "alien", "crimson",
        "overcast", "void"};
    private static final int[][] SKY = {
        {0x3B8DFF, 0xA9D0FF}, {0xBFE9FF, 0xF0FAFF}, {0xF2B33D, 0xFFE3A3}, {0x6A5ACD, 0xFFA868}, {0xFF8FB1, 0xFFD1DC},
        {0x4B2E83, 0xB58BDA}, {0x7FE5C4, 0xD8FFF1}, {0x3BD16F, 0xB9F7C9}, {0xB3202A, 0xE0707A}, {0x8A97A6, 0xC3CBD3},
        {0x05050A, 0x101018}};

    public static final String[] WATER_NAMES = {"tropical", "crystal", "deep", "emerald", "swamp", "lavender", "rose", "gold",
        "blood", "ink"};
    private static final int[] WATER = {0x43D5EE, 0x9FE8FF, 0x1E4FB5, 0x2EC27E, 0x617B64, 0x9A7BEA, 0xF078A8, 0xE5B53A, 0xA01020,
        0x202838};

    /** The sky colour in use, or -1 for the game's own. */
    public static int sky() {
        int mode = BetterConfig.skyPreset;
        if (mode == CUSTOM) {
            return BetterConfig.skyCustom & 0xFFFFFF;
        }
        return mode >= 2 && mode - 2 < SKY.length ? SKY[mode - 2][0] : -1;
    }

    private static int haze() {
        int mode = BetterConfig.skyPreset;
        if (mode >= 2 && mode - 2 < SKY.length) {
            return SKY[mode - 2][1];
        }
        // For a picked colour: the same colour, washed out toward white, as the stock haze is to the stock sky.
        int sky = BetterConfig.skyCustom;
        int r = (sky >> 16 & 255), g = (sky >> 8 & 255), b = sky & 255;
        return ((r + (255 - r) * 45 / 100) << 16) | ((g + (255 - g) * 45 / 100) << 8) | (b + (255 - b) * 45 / 100);
    }

    /** The water colour in use, or -1 for the game's own. */
    public static int water() {
        int mode = BetterConfig.waterPreset;
        if (mode == CUSTOM) {
            return BetterConfig.waterCustom & 0xFFFFFF;
        }
        return mode >= 2 && mode - 2 < WATER.length ? WATER[mode - 2] : -1;
    }

    // ------------------------------------------------------------------------------------

    /** How often the water tint was asked for; the self-test reads it to see that chunks really use it. */
    public static volatile int tintCalls;
    private static IBlockColor stockWater;
    private static TextureAtlasSprite clearStill, clearFlow;

    /** Called while the mod is constructed: the first texture atlas is built before mods initialise. */
    public static void registerEarly() {
        MinecraftForge.EVENT_BUS.register(new WorldColors());
    }

    public static void init() {
        Minecraft mc = Minecraft.getMinecraft();
        BlockColors colors = mc.getBlockColors();
        try {
            Map<?, IBlockColor> handlers = ReflectionHelper.getPrivateValue(BlockColors.class, colors, "blockColorMap");
            stockWater = handlers.get(Blocks.WATER.delegate);
        } catch (Throwable t) {
            stockWater = null;
        }
        colors.registerBlockColorHandler(new IBlockColor() {
            @Override
            public int colorMultiplier(IBlockState state, IBlockAccess world, BlockPos pos, int tintIndex) {
                tintCalls++;
                int custom = water();
                if (custom >= 0) {
                    return custom;
                }
                if (stockWater != null) {
                    return stockWater.colorMultiplier(state, world, pos, tintIndex);
                }
                return world != null && pos != null ? BiomeColorHelper.getWaterColorAtPos(world, pos) : -1;
            }
        }, Blocks.WATER, Blocks.FLOWING_WATER);
        IResourceManager manager = mc.getResourceManager();
        if (manager instanceof IReloadableResourceManager) {
            // After the game's own listeners: the fluid renderer has just looked its sprites up again.
            ((IReloadableResourceManager) manager).registerReloadListener(new IResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(IResourceManager resourceManager) {
                    applyWaterSprites();
                }
            });
        }
        applyWaterSprites();
    }

    /** Points the water renderer at the colourless sprites while a custom colour is on, and back otherwise. */
    public static void applyWaterSprites() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getBlockRendererDispatcher() == null) {
                return;
            }
            // Mods wrap the block renderer (CodeChickenLib keeps the original inside its own), and
            // each wrapper has a water renderer of its own; the one that draws is not always the outer one.
            List<BlockFluidRenderer> renderers = new ArrayList<BlockFluidRenderer>();
            collect(mc.getBlockRendererDispatcher(), Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), renderers);
            TextureMap map = mc.getTextureMapBlocks();
            boolean clear = water() >= 0 && clearStill instanceof ClearWater && ((ClearWater) clearStill).loaded
                    && clearFlow instanceof ClearWater && ((ClearWater) clearFlow).loaded;
            for (BlockFluidRenderer fluids : renderers) {
                TextureAtlasSprite[] sprites = ReflectionHelper.getPrivateValue(BlockFluidRenderer.class, fluids, "atlasSpritesWater",
                        "field_178271_b");
                sprites[0] = clear ? clearStill : map.getAtlasSprite("minecraft:blocks/water_still");
                sprites[1] = clear ? clearFlow : map.getAtlasSprite("minecraft:blocks/water_flow");
            }
            waterRenderers = renderers.size();
        } catch (Throwable t) {
            Better112.LOG.warn("Custom water colour is limited to tinting the stock texture: {}", t.toString());
        }
    }

    /** How many water renderers the last swap reached; for the self-test. */
    public static int waterRenderers;

    private static void collect(Object dispatcher, Set<Object> seen, List<BlockFluidRenderer> out) throws IllegalAccessException {
        if (dispatcher == null || !seen.add(dispatcher)) {
            return;
        }
        for (Class<?> type = dispatcher.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (BlockFluidRenderer.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(dispatcher);
                    if (value != null && seen.add(value)) {
                        out.add((BlockFluidRenderer) value);
                    }
                } else if (BlockRendererDispatcher.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    collect(field.get(dispatcher), seen, out);
                }
            }
        }
    }

    @SubscribeEvent
    public void onStitch(TextureStitchEvent.Pre event) {
        if (event.getMap() != Minecraft.getMinecraft().getTextureMapBlocks()) {
            return;
        }
        clearStill = new ClearWater("better112:blocks/water_still_clear", "blocks/water_still");
        clearFlow = new ClearWater("better112:blocks/water_flow_clear", "blocks/water_flow");
        event.getMap().setTextureEntry(clearStill);
        event.getMap().setTextureEntry(clearFlow);
    }

    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.FogColors event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        Material in = event.getState().getMaterial();
        if (in == Material.WATER) {
            int water = water();
            if (water >= 0) {
                // The stock underwater haze is (0.02, 0.02, 0.2); keep its brightness, take the hue.
                float level = event.getBlue() / 0.2F * 0.3F;
                event.setRed((water >> 16 & 255) / 255.0F * level);
                event.setGreen((water >> 8 & 255) / 255.0F * level);
                event.setBlue((water & 255) / 255.0F * level);
            }
            return;
        }
        if (in == Material.LAVA || sky() < 0 || !mc.world.provider.isSurfaceWorld()) {
            return;
        }
        // Scale the haze the game computed (day, night, rain, distance) by how the chosen haze
        // compares with the stock one, channel by channel.
        int haze = haze();
        event.setRed(Math.min(1.0F, event.getRed() * ((haze >> 16 & 255) / 255.0F) / 0.7529412F));
        event.setGreen(Math.min(1.0F, event.getGreen() * ((haze >> 8 & 255) / 255.0F) / 0.84705883F));
        event.setBlue(Math.min(1.0F, event.getBlue() * ((haze & 255) / 255.0F)));
    }

    /** The water texture with its colour taken out and its brightness evened up, frame for frame. */
    private static final class ClearWater extends TextureAtlasSprite {
        private final ResourceLocation source;
        boolean loaded;

        ClearWater(String name, String source) {
            super(name);
            this.source = new ResourceLocation("minecraft", "textures/" + source + ".png");
        }

        @Override
        public boolean hasCustomLoader(IResourceManager manager, ResourceLocation location) {
            return true;
        }

        @Override
        public boolean load(IResourceManager manager, ResourceLocation location, Function<ResourceLocation, TextureAtlasSprite> textureGetter) {
            try (IResource original = manager.getResource(source)) {
                BufferedImage image = TextureUtil.readBufferedImage(original.getInputStream());
                long sum = 0;
                int count = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int argb = image.getRGB(x, y);
                        if ((argb >>> 24) != 0) {
                            sum += Math.max(argb >> 16 & 255, Math.max(argb >> 8 & 255, argb & 255));
                            count++;
                        }
                    }
                }
                double scale = count == 0 || sum == 0 ? 1.0D : 215.0D / (sum / (double) count);
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int argb = image.getRGB(x, y);
                        int value = Math.max(argb >> 16 & 255, Math.max(argb >> 8 & 255, argb & 255));
                        int grey = (int) Math.min(255.0D, value * scale);
                        image.setRGB(x, y, (argb & 0xFF000000) | grey << 16 | grey << 8 | grey);
                    }
                }
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                final byte[] bytes = png.toByteArray();
                IResource grey = new Recolored(original, bytes);
                this.loadSprite(PngSizeInfo.makeFromResource(new Recolored(original, bytes)), original.getMetadata("animation") != null);
                this.loadSpriteFrames(grey, Minecraft.getMinecraft().gameSettings.mipmapLevels + 1);
                loaded = true;
                return false; // false: stitch it onto the atlas like any other sprite
            } catch (Throwable t) {
                Better112.LOG.warn("Could not make a colourless water texture from {}", source, t);
                return true;
            }
        }
    }

    /** The original resource (for its animation data) with different picture bytes. */
    private static final class Recolored implements IResource {
        private final IResource original;
        private final byte[] bytes;

        Recolored(IResource original, byte[] bytes) {
            this.original = original;
            this.bytes = bytes;
        }

        @Override
        public ResourceLocation getResourceLocation() {
            return original.getResourceLocation();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public boolean hasMetadata() {
            return original.hasMetadata();
        }

        @Override
        public <T extends IMetadataSection> T getMetadata(String sectionName) {
            return original.getMetadata(sectionName);
        }

        @Override
        public String getResourcePackName() {
            return original.getResourcePackName();
        }

        @Override
        public void close() throws IOException {
            // the original is closed by whoever opened it
        }
    }
}
