package better112.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import better112.Better112;
import better112.config.BetterConfig;
import better112.tree.Ing;
import better112.tree.RecipeTreeKeys;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;

import org.lwjgl.input.Keyboard;

/**
 * Showing an item in chat: hover it and press Shift+T, or write [item] in a message to show
 * what is in your hand.
 *
 * The mod is client-side, so it cannot make the server send a real hoverable item. Instead
 * the message carries the item's name for everyone, followed by a short code that this mod
 * turns back into the item (name in its rarity colour, full tooltip on hover) for players
 * who have it. Messages stay under the game's 256 character limit; an item whose data does
 * not fit is sent without it.
 */
public final class ItemLink {

    public static final KeyBinding KEY_LINK = new KeyBinding("better112.key.link_item", KeyConflictContext.GUI, KeyModifier.SHIFT,
            Keyboard.KEY_T, "better112.key.category");

    /**
     * "[Name]" (optional, replaced along with the code) then the code itself. The code avoids
     * every character chat formatters treat as markup: an item id's underscores are written
     * as "!", and the data is plain base64.
     */
    static final Pattern TOKEN = Pattern.compile(
            "(?:\\[[^\\[\\]\u00AB\u00BB]{0,90}\\])?\u00ABi\\|([a-z0-9!.\\-]+:[a-z0-9!./\\-]+)\\|(\\d{1,5})\\|(\\d{1,3})(?:\\|([A-Za-z0-9+/]{0,340}))?\u00BB");
    private static final Pattern HAND = Pattern.compile("\\[(?:item|i)\\]", Pattern.CASE_INSENSITIVE);
    private static final int CHAT_LIMIT = 256;

    private long lastSent;

    /** The chat text that shows a stack, at most {@code room} characters, or null if even the bare item does not fit. */
    static String encode(ItemStack stack, int room) {
        ResourceLocation id = stack.getItem().getRegistryName();
        if (id == null) {
            return null;
        }
        String name = TextFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        name = ChatAllowedCharacters.filterAllowedCharacters(name == null ? "" : name).replace('[', '(').replace(']', ')')
                .replace('«', '<').replace('»', '>').replace('_', ' ').replace('*', ' ').replace('~', ' ');
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        String shown = "[" + name + (stack.getCount() > 1 ? " x" + stack.getCount() : "") + "]";
        String bare = "\u00ABi|" + id.toString().replace('_', '!') + "|" + stack.getMetadata() + "|" + Math.min(999, stack.getCount());
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && !tag.isEmpty()) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
                try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater))) {
                    CompressedStreamTools.write(tag, out);
                }
                deflater.end();
                String data = Base64.getEncoder().withoutPadding().encodeToString(bytes.toByteArray());
                String full = shown + bare + "|" + data + "»";
                if (data.length() <= 340 && full.length() <= room) {
                    return full;
                }
            } catch (Exception e) {
                Better112.LOG.debug("Item data left out of the chat link: {}", e.toString());
            }
        }
        String plain = shown + bare + "»";
        return plain.length() <= room ? plain : null;
    }

    /** The stack a code stands for, or null if this game does not have the item. */
    static ItemStack decode(Matcher token) {
        try {
            Item item = Item.REGISTRY.getObject(new ResourceLocation(token.group(1).replace('!', '_')));
            if (item == null) {
                return null;
            }
            ItemStack stack = new ItemStack(item, Math.max(1, Integer.parseInt(token.group(3))), Integer.parseInt(token.group(2)));
            String data = token.group(4);
            if (data != null && !data.isEmpty()) {
                byte[] packed = Base64.getDecoder().decode(data);
                // Bounded on both sides: the text is short, and the reader stops at 64 KB of item data.
                try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed),
                        new java.util.zip.Inflater(true)))) {
                    stack.setTagCompound(CompressedStreamTools.read(in, new NBTSizeTracker(65536L)));
                }
            }
            return stack;
        } catch (Throwable t) {
            return null;
        }
    }

    /** What a code is shown as: the item's own chat component (hover shows its tooltip), plus the amount. */
    static ITextComponent component(ItemStack stack) {
        ITextComponent text = stack.getTextComponent();
        if (stack.getCount() > 1) {
            text.appendSibling(new TextComponentString(" x" + stack.getCount()));
        }
        return text;
    }

    static void send(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        String text = stack.isEmpty() ? null : encode(stack, CHAT_LIMIT);
        if (text != null && mc.player != null) {
            mc.player.sendChatMessage(text);
        }
    }

    /** Self-test: link the item in an inventory slot. */
    public static String testSend(int slot) {
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack stack = mc.player.inventory.getStackInSlot(slot);
        String text = encode(stack, CHAT_LIMIT);
        send(stack);
        return text;
    }

    @SubscribeEvent
    public void onGuiKey(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (!BetterConfig.chatItemLinks || !Keyboard.getEventKeyState()) {
            return;
        }
        int key = Keyboard.getEventKey();
        GuiScreen gui = event.getGui();
        if (key == Keyboard.KEY_NONE || gui == null || gui instanceof GuiChat || !KEY_LINK.isActiveAndMatches(key)) {
            return;
        }
        if (RecipeTreeKeys.source().hasTextFocus() || RecipeTreeKeys.isTyping(gui)) {
            return;
        }
        ItemStack stack = ItemStack.EMPTY;
        boolean slotItem = false;
        if (gui instanceof GuiContainer) {
            Slot slot = ((GuiContainer) gui).getSlotUnderMouse();
            if (slot != null && slot.getHasStack()) {
                stack = slot.getStack();
                slotItem = true;
            }
        }
        if (stack.isEmpty()) {
            Ing ing = RecipeTreeKeys.source().hovered(gui);
            if (ing != null && ing.item != null) {
                stack = ing.item;
            }
        }
        if (stack.isEmpty() || (slotItem && quarkLinksItems())) {
            return; // Quark, with its half on the server, links inventory items itself and does it better
        }
        long now = System.currentTimeMillis();
        if (now - lastSent > 1000L) {
            lastSent = now;
            send(stack);
        }
    }

    /** "[item]" in an outgoing message becomes the item in the player's hand. */
    @SubscribeEvent
    public void onSend(ClientChatEvent event) {
        String message = event.getMessage();
        Minecraft mc = Minecraft.getMinecraft();
        if (!BetterConfig.chatItemLinks || message == null || message.startsWith("/") || mc.player == null) {
            return;
        }
        Matcher hand = HAND.matcher(message);
        if (!hand.find()) {
            return;
        }
        ItemStack held = mc.player.getHeldItemMainhand();
        if (held.isEmpty()) {
            held = mc.player.getHeldItemOffhand();
        }
        if (held.isEmpty()) {
            return;
        }
        String link = encode(held, CHAT_LIMIT - (message.length() - hand.group().length()));
        if (link != null) {
            event.setMessage(message.substring(0, hand.start()) + link + message.substring(hand.end()));
        }
    }

    private static Boolean quarkFeature;

    /** True when Quark's own "press T to link items" is on and the server has Quark to carry it. */
    private static boolean quarkLinksItems() {
        if (quarkFeature == null) {
            quarkFeature = Boolean.FALSE;
            if (Loader.isModLoaded("quark")) {
                try {
                    Class<?> feature = Class.forName("vazkii.quark.management.feature.LinkItems");
                    Class<?> loader = Class.forName("vazkii.quark.base.module.ModuleLoader");
                    quarkFeature = (Boolean) loader.getMethod("isFeatureEnabled", Class.class).invoke(null, feature);
                } catch (Throwable t) {
                    quarkFeature = Boolean.FALSE;
                }
            }
        }
        if (!quarkFeature) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.isSingleplayer()) {
                return true;
            }
            NetworkDispatcher dispatcher = NetworkDispatcher.get(mc.getConnection().getNetworkManager());
            Object mods = dispatcher == null ? null : dispatcher.getClass().getMethod("getModList").invoke(dispatcher);
            return mods instanceof Map && ((Map<?, ?>) mods).containsKey("quark");
        } catch (Throwable t) {
            return false;
        }
    }
}
