package better112.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import better112.Better112;
import better112.config.BetterConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.network.play.client.CPacketTabComplete;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.network.play.server.SPacketTabComplete;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

/**
 * Command suggestions as the game has had them since 1.13: while a command is typed, the commands or
 * arguments that fit are listed above the chat line. Up and Down pick one, Tab takes it, Escape puts the
 * list away. The list is the server's own Tab answer, asked for on every change instead of on Tab.
 */
public final class CommandSuggest {

    private static final int ROWS = 10;

    /** The server's answer for the word that starts at {@link #wordAt}. */
    private static List<String> answer = Collections.emptyList();
    /** What this client's own commands answered for the last question. */
    private static String[] local = new String[0];
    private static String asked;
    private static int wordAt = -1;
    private static int selected;
    private static int top;
    private static String hiddenFor;
    /** Questions sent and not yet answered, and when the last one went. */
    private static final AtomicInteger waiting = new AtomicInteger();
    private static volatile long askedAt;
    /** Lines kept out of the chat, for the self-test. */
    public static volatile int hiddenLines;

    /**
     * Whether a chat line is a side effect of asking for suggestions. To list who may use a command,
     * the server runs each command's permission check, and some mods' checks write to the player
     * (Tool Progression's /tpr: "this command can only be used in single player mode"). Vanilla asks
     * only on Tab; asked on every key, such a line would fill the chat. The server answers a question
     * after everything it sends while working it out, so a plain system line that comes while a
     * question is open is taken for one of these. Player chat and translated game messages (joins,
     * deaths, announcements) always pass.
     */
    private static boolean provoked(SPacketChat packet) {
        if (waiting.get() <= 0 || System.currentTimeMillis() - askedAt > 3000L || packet.getType() != ChatType.SYSTEM
                || !(packet.getChatComponent() instanceof TextComponentString)) {
            return false;
        }
        hiddenLines++;
        Better112.LOG.debug("Kept out of the chat while asking for command suggestions: {}", packet.getChatComponent().getUnformattedText());
        return true;
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        try {
            ChannelPipeline pipeline = event.getManager().channel().pipeline();
            if (pipeline.get("better112_suggest") != null || pipeline.get("packet_handler") == null) {
                return;
            }
            waiting.set(0);
            pipeline.addBefore("packet_handler", "better112_suggest", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                    if (message instanceof SPacketTabComplete) {
                        waiting.updateAndGet(n -> Math.max(0, n - 1));
                        final String[] matches = ((SPacketTabComplete) message).getMatches().clone();
                        Minecraft.getMinecraft().addScheduledTask(() -> answered(matches));
                    } else if (message instanceof SPacketChat && provoked((SPacketChat) message)) {
                        return;
                    }
                    super.channelRead(context, message);
                }
            });
        } catch (Throwable t) {
            Better112.LOG.warn("Command suggestions cannot listen on this connection: {}", t.toString());
        }
    }

    private static GuiTextField inputOf(GuiScreen screen) {
        if (!(screen instanceof GuiChat)) {
            return null;
        }
        try {
            return ReflectionHelper.getPrivateValue(GuiChat.class, (GuiChat) screen, "inputField", "field_146415_a");
        } catch (Throwable t) {
            return null;
        }
    }

    /** The command as typed up to the cursor, or null when the line is not a command. */
    private static String typed(GuiTextField input) {
        String text = input.getText();
        String upTo = text.substring(0, Math.min(input.getCursorPosition(), text.length()));
        return upTo.startsWith("/") ? upTo : null;
    }

    private static void ask(GuiTextField input) {
        Minecraft mc = Minecraft.getMinecraft();
        String typed = typed(input);
        if (typed == null || mc.player == null) {
            asked = null;
            answer = Collections.emptyList();
            wordAt = -1;
            return;
        }
        if (typed.equals(asked)) {
            return;
        }
        asked = typed;
        selected = 0;
        top = 0;
        int at = typed.lastIndexOf(' ') + 1;
        if (at != wordAt) {
            // Another argument: the answer in hand says nothing about it.
            wordAt = at;
            answer = Collections.emptyList();
        }
        try {
            ClientCommandHandler.instance.autoComplete(typed);
            String[] here = ClientCommandHandler.instance.latestAutoComplete;
            local = here == null ? new String[0] : here.clone();
        } catch (Throwable t) {
            local = new String[0];
        }
        RayTraceResult hit = mc.objectMouseOver;
        BlockPos target = hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK ? hit.getBlockPos() : null;
        // The last flag is for command blocks: with it the server leaves the slash off command names.
        askedAt = System.currentTimeMillis();
        waiting.incrementAndGet();
        mc.player.connection.sendPacket(new CPacketTabComplete(typed, target, false));
    }

    private static void answered(String[] matches) {
        GuiTextField input = inputOf(Minecraft.getMinecraft().currentScreen);
        if (input == null || typed(input) == null) {
            return;
        }
        TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String each : local) {
            String plain = TextFormatting.getTextWithoutFormattingCodes(each);
            if (plain != null && !plain.isEmpty()) {
                sorted.add(plain);
            }
        }
        for (String each : matches) {
            if (each != null && !each.isEmpty()) {
                sorted.add(each);
            }
        }
        answer = new ArrayList<>(sorted);
    }

    /** What to list for the line as it stands. */
    private static List<String> listed(GuiTextField input) {
        String typed = typed(input);
        if (!BetterConfig.commandSuggestions || typed == null || wordAt < 0 || wordAt > typed.length() || typed.equals(hiddenFor)
                || !EmoteSuggest.suggestions(input.getText(), input.getCursorPosition()).isEmpty()) {
            return Collections.emptyList();
        }
        String word = typed.substring(wordAt).toLowerCase(Locale.ROOT);
        List<String> fits = new ArrayList<>();
        for (String each : answer) {
            if (each.toLowerCase(Locale.ROOT).startsWith(word)) {
                fits.add(each);
            }
        }
        if (fits.size() == 1 && fits.get(0).equalsIgnoreCase(word)) {
            return Collections.emptyList(); // nothing left to add
        }
        return fits;
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        GuiTextField input = BetterConfig.commandSuggestions ? inputOf(event.getGui()) : null;
        if (input == null) {
            return;
        }
        ask(input);
        List<String> list = listed(input);
        if (list.isEmpty()) {
            return;
        }
        selected = Math.max(0, Math.min(selected, list.size() - 1));
        int rows = Math.min(ROWS, list.size());
        top = Math.max(Math.min(top, selected), selected - rows + 1);
        top = Math.max(0, Math.min(top, list.size() - rows));

        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        GuiScreen gui = event.getGui();
        String text = input.getText();
        int width = 0;
        for (int i = top; i < top + rows; i++) {
            width = Math.max(width, font.getStringWidth(list.get(i)));
        }
        int x = Math.max(2, Math.min(4 + font.getStringWidth(text.substring(0, wordAt)) - 1, gui.width - width - 5));
        int bottom = gui.height - 15;
        int y = bottom - rows * 12;
        Gui.drawRect(x, y, x + width + 3, bottom, 0xD0000000);
        for (int i = 0; i < rows; i++) {
            font.drawStringWithShadow(list.get(top + i), x + 1, y + i * 12 + 2, top + i == selected ? 0xFFFFFF55 : 0xFFAAAAAA);
        }
        // Dotted edges say the list goes on.
        for (int dot = x; dot < x + width + 3; dot += 2) {
            if (top > 0) {
                Gui.drawRect(dot, y, dot + 1, y + 1, 0xFFFFFFFF);
            }
            if (top + rows < list.size()) {
                Gui.drawRect(dot, bottom - 1, dot + 1, bottom, 0xFFFFFFFF);
            }
        }
        // The rest of the picked suggestion, greyed, after what is typed.
        String pick = list.get(selected);
        String word = text.substring(wordAt, Math.min(input.getCursorPosition(), text.length()));
        int end = 4 + font.getStringWidth(text);
        if (input.getCursorPosition() == text.length() && pick.length() > word.length()
                && pick.regionMatches(true, 0, word, 0, word.length()) && end < gui.width - 8) {
            font.drawStringWithShadow(pick.substring(word.length()), end, gui.height - 12, 0xFF808080);
        }
    }

    @SubscribeEvent
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        GuiTextField input = BetterConfig.commandSuggestions && Keyboard.getEventKeyState() ? inputOf(event.getGui()) : null;
        if (input == null || typed(input) == null) {
            return;
        }
        int key = Keyboard.getEventKey();
        List<String> list = listed(input);
        if (key == Keyboard.KEY_TAB) {
            // The list has already shown everything Tab would have asked the server for.
            event.setCanceled(true);
            if (!list.isEmpty()) {
                take(input, list.get(Math.max(0, Math.min(selected, list.size() - 1))));
            }
        } else if (list.isEmpty()) {
            return;
        } else if (key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN) {
            selected = (selected + (key == Keyboard.KEY_UP ? list.size() - 1 : 1)) % list.size();
            event.setCanceled(true);
        } else if (key == Keyboard.KEY_ESCAPE) {
            hiddenFor = typed(input);
            event.setCanceled(true);
        }
    }

    private static void take(GuiTextField input, String pick) {
        String text = input.getText();
        int cursor = Math.min(input.getCursorPosition(), text.length());
        input.setText(text.substring(0, wordAt) + pick + text.substring(cursor));
        input.setCursorPosition(Math.min(wordAt + pick.length(), input.getText().length()));
    }

    /** Self-test: the list for the open chat line, and the line after Tab. */
    public static String testState() {
        GuiTextField input = inputOf(Minecraft.getMinecraft().currentScreen);
        if (input == null) {
            return "chat is not open";
        }
        List<String> list = listed(input);
        String before = input.getText();
        if (!list.isEmpty()) {
            take(input, list.get(Math.max(0, Math.min(selected, list.size() - 1))));
        }
        String after = input.getText();
        input.setText(before);
        return list.size() + " for '" + before + "' " + (list.size() > 6 ? list.subList(0, 6) + "..." : list) + ", Tab gives '" + after + "'"
                + ", side-effect lines kept out of the chat so far: " + hiddenLines;
    }
}
