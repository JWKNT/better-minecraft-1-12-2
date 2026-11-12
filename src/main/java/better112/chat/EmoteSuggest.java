package better112.chat;

import java.util.Collections;
import java.util.List;

import better112.config.BetterConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import org.lwjgl.input.Keyboard;

/**
 * While the chat box is open: typing a colon and a couple of letters lists the emotes that
 * match just above the box, and Tab finishes the first one.
 */
public final class EmoteSuggest {

    private static final int LIMIT = 8;

    private static GuiTextField inputOf(GuiChat chat) {
        try {
            return ReflectionHelper.getPrivateValue(GuiChat.class, chat, "inputField", "field_146415_a");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Where the short code being typed starts (index of its colon), or -1. */
    static int partialStart(String text, int cursor) {
        int i = Math.min(cursor, text.length()) - 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ':') {
                // An even number of colons before this one means it opens a code rather than closing one.
                return cursor - i - 1 >= 2 && (i == 0 || !isNameChar(text.charAt(i - 1))) ? i : -1;
            }
            if (!isNameChar(c)) {
                return -1;
            }
            i--;
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '+' || c == '-';
    }

    /** Self-test and Tab: the completions for the text as typed so far. */
    public static List<String> suggestions(String text, int cursor) {
        int start = partialStart(text, cursor);
        return start < 0 ? Collections.<String>emptyList() : Emotes.matching(text.substring(start + 1, cursor), LIMIT);
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!BetterConfig.chatEmotes || !(event.getGui() instanceof GuiChat)) {
            return;
        }
        GuiTextField input = inputOf((GuiChat) event.getGui());
        if (input == null) {
            return;
        }
        List<String> found = suggestions(input.getText(), input.getCursorPosition());
        if (found.isEmpty()) {
            return;
        }
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int x = 2, y = event.getGui().height - 14 - 12;
        int width = 0;
        for (String name : found) {
            width += 9 + 2 + font.getStringWidth(":" + name + ":") + 8;
        }
        Gui.drawRect(x, y - 2, Math.min(event.getGui().width - 2, x + width + 4), y + 10, 0xD0000000);
        int at = x + 3;
        for (int i = 0; i < found.size(); i++) {
            String name = found.get(i);
            Character ch = Emotes.find(name);
            if (ch != null) {
                font.drawStringWithShadow(String.valueOf(ch.charValue()), at, y, 0xFFFFFFFF);
            }
            font.drawStringWithShadow(":" + name + ":", at + 11, y, i == 0 ? 0xFF94E4D3 : 0xFFAAAAAA);
            at += 9 + 2 + font.getStringWidth(":" + name + ":") + 8;
        }
    }

    @SubscribeEvent
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!BetterConfig.chatEmotes || !(event.getGui() instanceof GuiChat) || !Keyboard.getEventKeyState()
                || Keyboard.getEventKey() != Keyboard.KEY_TAB) {
            return;
        }
        GuiTextField input = inputOf((GuiChat) event.getGui());
        if (input == null) {
            return;
        }
        String done = complete(input.getText(), input.getCursorPosition());
        if (done != null) {
            int tail = input.getText().length() - input.getCursorPosition();
            input.setText(done);
            input.setCursorPosition(done.length() - tail);
            event.setCanceled(true); // otherwise Tab would also try to complete a player name
        }
    }

    /** The text with the short code under the cursor finished, or null if there is nothing to finish. */
    public static String complete(String text, int cursor) {
        List<String> found = suggestions(text, cursor);
        if (found.isEmpty()) {
            return null;
        }
        int start = partialStart(text, cursor);
        return text.substring(0, start) + ":" + found.get(0) + ": " + text.substring(cursor);
    }
}
