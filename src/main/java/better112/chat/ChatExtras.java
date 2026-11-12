package better112.chat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Mouse;

/**
 * Three things Chat Patches gives modern packs: the time each message arrived in front of it (the full date
 * on hover), a right click on a message to copy it, and a typed message that survives closing the chat
 * with Escape.
 */
public final class ChatExtras {

    private static String draft = "";

    // --- Timestamps ---

    /** After the other chat handlers, so emotes and item links see the message as it came. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (!BetterConfig.chatTimestamps || event.isCanceled() || event.getType() == ChatType.GAME_INFO || event.getMessage() == null) {
            return;
        }
        event.setMessage(stamp(event.getMessage(), new Date()));
    }

    static ITextComponent stamp(ITextComponent message, Date when) {
        ITextComponent time = new TextComponentString("[" + new SimpleDateFormat("HH:mm").format(when) + "] ");
        time.getStyle().setColor(TextFormatting.DARK_GRAY).setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new TextComponentString(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(when))));
        return new TextComponentString("").appendSibling(time).appendSibling(message);
    }

    // --- Copy a message with a right click ---

    @SubscribeEvent
    public void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!BetterConfig.chatCopy || !(event.getGui() instanceof GuiChat) || Mouse.getEventButton() != 1 || !Mouse.getEventButtonState()) {
            return;
        }
        String text = messageAt(Mouse.getX(), Mouse.getY());
        if (text != null) {
            GuiScreen.setClipboardString(text);
            Minecraft.getMinecraft().ingameGUI.setOverlayMessage(I18n.format("better112.chat.copied"), false);
            event.setCanceled(true);
        }
    }

    /** The whole message (not just the wrapped line) under these raw mouse coordinates, without its timestamp, or null. */
    @SuppressWarnings("unchecked")
    public static String messageAt(int mouseX, int mouseY) {
        GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
        ITextComponent piece = chat.getChatComponent(mouseX, mouseY);
        if (piece == null) {
            return null;
        }
        // The game hands back a copy of the piece, so the line is found the way the game found it: by height.
        try {
            List<ChatLine> drawn = ReflectionHelper.getPrivateValue(GuiNewChat.class, chat, "drawnChatLines", "field_146253_i");
            List<ChatLine> whole = ReflectionHelper.getPrivateValue(GuiNewChat.class, chat, "chatLines", "field_146252_h");
            int scrollPos = ReflectionHelper.getPrivateValue(GuiNewChat.class, chat, "scrollPos", "field_146250_j");
            Minecraft mc = Minecraft.getMinecraft();
            int y = better112.core.PatchState.isApplied("gui_scale_chat_y") ? better112.Hooks.chatMouse(mouseY) : mouseY;
            int k = net.minecraft.util.math.MathHelper.floor((y / new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor() - 40)
                    / chat.getChatScale());
            int index = k / mc.fontRenderer.FONT_HEIGHT + scrollPos;
            if (index < 0 || index >= drawn.size()) {
                return plain(piece);
            }
            ChatLine line = drawn.get(index);
            // Wrapped lines carry their colours as codes inside the text; the copy is plain.
            String lineText = plain(line.getChatComponent()).trim();
            for (ChatLine message : whole) {
                String text = plain(message.getChatComponent());
                if (message.getUpdatedCounter() == line.getUpdatedCounter() && text.contains(lineText)) {
                    return withoutTime(text);
                }
            }
            return withoutTime(lineText);
        } catch (Throwable t) {
            return plain(piece);
        }
    }

    private static String plain(ITextComponent component) {
        String text = TextFormatting.getTextWithoutFormattingCodes(component.getUnformattedText());
        return text == null ? "" : text;
    }

    static String withoutTime(String text) {
        return text.replaceFirst("^\\[\\d\\d:\\d\\d\\] ", "");
    }

    // --- Keep what was typed ---

    @SubscribeEvent
    public void onOpen(GuiOpenEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!BetterConfig.chatCopy || !(mc.currentScreen instanceof GuiChat) || event.getGui() instanceof GuiChat) {
            return;
        }
        String text = input((GuiChat) mc.currentScreen);
        List<String> sent = mc.ingameGUI.getChatGUI().getSentMessages();
        boolean wasSent = !sent.isEmpty() && sent.get(sent.size() - 1).equals(text.trim());
        draft = wasSent ? "" : text;
    }

    @SubscribeEvent
    public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!BetterConfig.chatCopy || !(event.getGui() instanceof GuiChat) || draft.isEmpty()) {
            return;
        }
        GuiTextField field = field((GuiChat) event.getGui());
        if (field != null && field.getText().isEmpty()) {
            // Opened with the chat key, not the command key: carry on where the closed chat left off.
            field.setText(draft);
            draft = "";
        }
    }

    private static GuiTextField field(GuiChat chat) {
        try {
            return ReflectionHelper.getPrivateValue(GuiChat.class, chat, "inputField", "field_146415_a");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String input(GuiChat chat) {
        GuiTextField field = field(chat);
        return field == null ? "" : field.getText();
    }

    /** Self-test: what is kept to come back, and what a right click on the newest line would copy. */
    public static String testState() {
        Minecraft mc = Minecraft.getMinecraft();
        String copied = null;
        if (mc.currentScreen instanceof GuiChat) {
            // The newest line is the lowest one with text: walk up from the bottom of the window as a mouse would.
            for (int y = 0; y < mc.displayHeight && copied == null; y += 2) {
                for (int x = 8; x <= 64 && copied == null; x += 8) {
                    copied = messageAt(x, y);
                }
            }
        }
        return "draft '" + draft + "', newest line copies as '" + copied + "'";
    }
}
