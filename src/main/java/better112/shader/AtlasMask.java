package better112.shader;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;

import better112.Better112;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * What each picture of the block atlas is, as a small texture the shaders can read: leaves,
 * plants, vines, water, glowing things. Chunk vertices carry no block id in 1.12.2, but they
 * do carry where in the atlas their picture is, and pictures are named.
 *
 * One texel per cell of the atlas, a cell being as large as every marked picture lines up to
 * (16 atlas pixels in practice), so a texel is never shared by two marked pictures.
 */
final class AtlasMask {

    static final int NONE = 0, LEAVES = 1, PLANT = 2, PLANT_UPPER = 3, VINE = 4, WATER = 5, GLOW = 6;

    static int texture;
    static int width, height, cell;
    static String summary = "not built";
    private static Field sprites;

    private AtlasMask() {}

    /** Kind of a picture, from its name ("minecraft:blocks/leaves_oak", "biomesoplenty:blocks/flower_rose"). */
    static int kind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int colon = n.indexOf(':');
        String path = colon >= 0 ? n.substring(colon + 1) : n;
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (file.contains("water_still") || file.contains("water_flow") || file.contains("water_overlay")) {
            return WATER;
        }
        if (file.startsWith("lava") || file.contains("_lava") || file.startsWith("fire_layer") || file.equals("glowstone")
                || file.equals("sea_lantern") || file.startsWith("magma") || file.equals("redstone_lamp_on") || file.equals("torch_on")
                || file.equals("end_rod") || file.equals("beacon") || file.equals("pumpkin_face_on") || file.equals("shroomlight")
                || file.equals("glow_lichen") || file.contains("glowstone")) {
            return GLOW;
        }
        if (file.contains("leaves") && !file.contains("pile") && !file.contains("litter") && !file.contains("carpet")) {
            return LEAVES;
        }
        if (file.contains("vine") && !file.contains("vinegar")) {
            return VINE;
        }
        boolean plant = file.equals("tallgrass") || file.equals("fern") || file.equals("deadbush") || file.contains("short_grass")
                || file.contains("tall_grass") || file.contains("double_plant") || file.contains("flower") || file.contains("sapling")
                || file.contains("_stage") || file.startsWith("crop") || file.contains("bush") || file.contains("tulip")
                || file.contains("orchid") || file.contains("dandelion") || file.contains("poppy") || file.contains("allium")
                || file.contains("houstonia") || file.contains("oxeye") || file.contains("cornflower") || file.contains("lily_of_the_valley")
                || file.contains("seagrass") || file.contains("kelp") || file.equals("reeds") || file.contains("dry_grass")
                || file.contains("wildflowers") || file.contains("_rose") || file.startsWith("rose");
        if (!plant || file.contains("pot") || file.contains("petal") || file.contains("leaf_litter") || file.contains("_block")
                || file.contains("grass_side") || file.contains("grass_top") || file.contains("_side") || file.contains("path")) {
            return NONE;
        }
        if (file.contains("reeds") || file.contains("kelp")) {
            return NONE; // stacks of one plant: swaying each block's top would tear the stack apart
        }
        if (file.endsWith("_top") || file.endsWith("_upper") || file.contains("_top_") || file.endsWith("_front") && file.contains("sunflower")) {
            return PLANT_UPPER;
        }
        return PLANT;
    }

    /** Rebuilds the mask after the block atlas has been stitched. */
    @SuppressWarnings("unchecked")
    static void build(TextureMap map) {
        try {
            if (sprites == null) {
                sprites = ReflectionHelper.findField(TextureMap.class, "mapUploadedSprites", "field_94252_e");
                sprites.setAccessible(true);
            }
            Map<String, TextureAtlasSprite> all = (Map<String, TextureAtlasSprite>) sprites.get(map);
            GlStateManager.bindTexture(map.getGlTextureId());
            int atlasW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int atlasH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (atlasW <= 0 || atlasH <= 0) {
                summary = "atlas size unknown";
                return;
            }
            int g = 0, marked = 0;
            int[] counts = new int[8];
            for (TextureAtlasSprite s : all.values()) {
                int k = kind(s.getIconName());
                if (k == NONE) {
                    continue;
                }
                marked++;
                counts[k]++;
                g = gcd(g, gcd(gcd(s.getOriginX(), s.getOriginY()), gcd(s.getIconWidth(), s.getIconHeight())));
            }
            if (g <= 0) {
                g = 16;
            }
            g = Math.min(g, 16);
            while ((atlasW / g) * (long) (atlasH / g) > 16L * 1024 * 1024) {
                g *= 2; // keep the mask small; a rare picture off the grid is then read slightly wrong at its edge
            }
            int w = Math.max(1, atlasW / g), h = Math.max(1, atlasH / g);
            byte[] data = new byte[w * h];
            for (TextureAtlasSprite s : all.values()) {
                int k = kind(s.getIconName());
                if (k == NONE) {
                    continue;
                }
                int x0 = s.getOriginX() / g, y0 = s.getOriginY() / g;
                int x1 = Math.min(w, (s.getOriginX() + s.getIconWidth() + g - 1) / g), y1 = Math.min(h, (s.getOriginY() + s.getIconHeight() + g - 1) / g);
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        data[y * w + x] = (byte) k;
                    }
                }
            }
            if (texture == 0) {
                texture = GlStateManager.generateTexture();
            }
            GlStateManager.bindTexture(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
            buf.put(data).flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_LUMINANCE8, w, h, 0, GL11.GL_LUMINANCE, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GlStateManager.bindTexture(map.getGlTextureId());
            width = w;
            height = h;
            cell = g;
            summary = "atlas " + atlasW + "x" + atlasH + ", cells of " + g + ", " + marked + " pictures marked (leaves " + counts[LEAVES]
                    + ", plants " + (counts[PLANT] + counts[PLANT_UPPER]) + ", vines " + counts[VINE] + ", water " + counts[WATER]
                    + ", glowing " + counts[GLOW] + ")";
            Better112.LOG.info("Shader material mask: {}", summary);
        } catch (Exception e) {
            summary = "failed: " + e;
            Better112.LOG.warn("Could not build the shader material mask", e);
        }
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
