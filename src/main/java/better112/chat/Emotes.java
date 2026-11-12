package better112.chat;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import better112.Better112;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Chat emotes, written the way Discord does: :smile:, :joy:, :thumbsup:.
 *
 * An emote is one character of text. Minecraft's font already draws any character from a
 * sheet of 16x16 pictures ("unicode pages"), so the mod ships a sheet for the private-use
 * characters U+E000 and up and swaps each :name: for its character when a chat line
 * arrives. What goes over the network stays plain ":smile:", so players without the mod
 * just see the short code.
 *
 * A second sheet is filled in as needed with item icons, so :diamond: or :iron_ingot: work
 * for any item the game knows.
 */
public final class Emotes {

    private Emotes() {}

    static final char FIRST = '';
    static final char FIRST_ITEM = '';
    private static final ResourceLocation ITEM_PAGE = new ResourceLocation("textures/font/unicode_page_e1.png");

    private static final Map<String, Character> BY_NAME = new HashMap<String, Character>();
    private static final Map<Character, String> NAME_OF = new HashMap<Character, String>();
    /** First name of each built-in emote, in sheet order, then nothing else. */
    private static final List<String> NAMES = new ArrayList<String>();

    private static final Map<String, Character> ITEM_CHARS = new HashMap<String, Character>();
    private static Map<String, Item> itemsByPath;
    private static DynamicTexture itemTexture;
    private static int nextItem;

    public static void init() {
        try (InputStream in = Emotes.class.getResourceAsStream("/assets/better112/emotes.txt")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null;) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                char ch = (char) (FIRST + Integer.parseInt(parts[0]));
                for (int i = 1; i < parts.length; i++) {
                    BY_NAME.put(parts[i], ch);
                    // Some servers strip underscores from chat (as markup); the name still has to be found.
                    String squeezed = parts[i].replace("_", "");
                    if (!BY_NAME.containsKey(squeezed)) {
                        BY_NAME.put(squeezed, ch);
                    }
                }
                NAME_OF.put(ch, parts[1]);
                NAMES.add(parts[1]);
            }
        } catch (Exception e) {
            Better112.LOG.warn("Could not read the emote list", e);
        }
        IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
        if (manager instanceof IReloadableResourceManager) {
            // Registered after the font's own listener, so this runs after it has re-read its glyph table.
            ((IReloadableResourceManager) manager).registerReloadListener(new IResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(IResourceManager resourceManager) {
                    reload();
                }
            });
        } else {
            reload();
        }
    }

    /** Tells the font that the emote characters exist and are a full cell wide; forgets item icons drawn from old textures. */
    private static void reload() {
        try {
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            if (font != null) {
                byte[] widths = ReflectionHelper.getPrivateValue(FontRenderer.class, font, "glyphWidth", "field_78287_e");
                for (int i = 0; i < 512; i++) {
                    widths[FIRST + i] = 0x0F;
                }
            }
        } catch (Throwable t) {
            Better112.LOG.warn("Chat emotes cannot be drawn with this font renderer: {}", t.toString());
        }
        ITEM_CHARS.clear();
        itemsByPath = null;
        nextItem = 0;
        if (itemTexture != null) {
            java.util.Arrays.fill(itemTexture.getTextureData(), 0);
            itemTexture.updateDynamicTexture();
        }
    }

    public static int count() {
        return NAMES.size();
    }

    /** The character for a short code (without colons), or null. Item names are tried after the built-in emotes. */
    public static Character find(String name) {
        Character ch = BY_NAME.get(name);
        return ch != null ? ch : itemEmote(name);
    }

    public static String nameOf(char ch) {
        String name = NAME_OF.get(ch);
        if (name != null) {
            return name;
        }
        for (Map.Entry<String, Character> entry : ITEM_CHARS.entrySet()) {
            if (entry.getValue() != null && entry.getValue() == ch) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Built-in emote names starting with (then containing) the text, for completion. */
    public static List<String> matching(String text, int limit) {
        List<String> found = new ArrayList<String>();
        for (int pass = 0; pass < 2 && found.size() < limit; pass++) {
            for (String name : NAMES) {
                boolean hit = pass == 0 ? name.startsWith(text) : !name.startsWith(text) && name.contains(text);
                if (hit && found.size() < limit) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------------------------
    // Item icons as emotes
    // ------------------------------------------------------------------------------------

    private static Character itemEmote(String name) {
        if (ITEM_CHARS.containsKey(name)) {
            return ITEM_CHARS.get(name);
        }
        Character ch = null;
        try {
            Item item = Item.REGISTRY.getObject(new ResourceLocation("minecraft", name));
            if (item == null) {
                item = itemsByPath().get(name);
            }
            if (item != null && nextItem < 256) {
                ch = drawItem(new ItemStack(item));
            }
        } catch (Throwable t) {
            Better112.LOG.debug("No item emote for {}: {}", name, t.toString());
        }
        ITEM_CHARS.put(name, ch);
        return ch;
    }

    /** Mod items by the part of their id after the colon, where that is unambiguous. */
    private static Map<String, Item> itemsByPath() {
        if (itemsByPath == null) {
            itemsByPath = new HashMap<String, Item>();
            Map<String, Integer> seen = new HashMap<String, Integer>();
            for (ResourceLocation id : Item.REGISTRY.getKeys()) {
                Integer n = seen.get(id.getPath());
                seen.put(id.getPath(), n == null ? 1 : n + 1);
            }
            for (ResourceLocation id : Item.REGISTRY.getKeys()) {
                if (seen.get(id.getPath()) == 1) {
                    itemsByPath.put(id.getPath(), Item.REGISTRY.getObject(id));
                }
            }
        }
        return itemsByPath;
    }

    private static Character drawItem(ItemStack stack) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        IBakedModel model = mc.getRenderItem().getItemModelWithOverrides(stack, null, null);
        TextureAtlasSprite sprite = model.getParticleTexture();
        if (sprite == null || sprite.getIconName().contains("missingno")) {
            return null;
        }
        ResourceLocation icon = new ResourceLocation(sprite.getIconName());
        BufferedImage image;
        try (IResource resource = mc.getResourceManager().getResource(
                new ResourceLocation(icon.getNamespace(), "textures/" + icon.getPath() + ".png"))) {
            image = TextureUtil.readBufferedImage(resource.getInputStream());
        }
        if (itemTexture == null) {
            itemTexture = new DynamicTexture(256, 256);
            mc.getTextureManager().loadTexture(ITEM_PAGE, itemTexture);
        }
        int size = Math.min(image.getWidth(), image.getHeight()); // the first frame of an animated texture
        int cell = nextItem++;
        int[] pixels = itemTexture.getTextureData();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixels[((cell / 16) * 16 + y) * 256 + (cell % 16) * 16 + x] = image.getRGB(x * size / 16, y * size / 16);
            }
        }
        itemTexture.updateDynamicTexture();
        return (char) (FIRST_ITEM + cell);
    }
}
