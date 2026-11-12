package better112.chat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import better112.config.BetterConfig;

import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Rewrites chat lines as they arrive: :name: becomes an emote and an item code becomes a
 * hoverable item. The line keeps its structure (who said it, colours, links); only the
 * text that holds a code changes.
 *
 * Servers and mods cut a message into many small pieces of text before it gets here: one
 * per word, one per link, one per bit of formatting (FTB Utilities turns _this_ into
 * italics and drops the underscores, for one). So a code is looked for in the text as the
 * player would read it, across neighbouring pieces, not piece by piece.
 */
public final class ChatRewriter {

    private static final Pattern EMOTE = Pattern.compile(":([a-z0-9_+\\-]{1,40}):");

    /** What a piece of text turns into: plain text and pictures, in order. Null means "unchanged". */
    private final Map<TextComponentString, List<Object>> replaced = new IdentityHashMap<TextComponentString, List<Object>>();

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.getType() == ChatType.GAME_INFO || event.getMessage() == null
                || !(BetterConfig.chatEmotes || BetterConfig.chatItemLinks)) {
            return;
        }
        ITextComponent rewritten = rewrite(event.getMessage());
        if (rewritten != null) {
            event.setMessage(rewritten);
        }
    }

    /** The line with its codes replaced, or null if it has none. */
    public ITextComponent rewrite(ITextComponent message) {
        replaced.clear();
        try {
            String all = message.getUnformattedText();
            if (all.indexOf(':') < 0 && all.indexOf('«') < 0) {
                return null;
            }
            List<TextComponentString> run = new ArrayList<TextComponentString>();
            scan(message, run);
            flush(run);
            return replaced.isEmpty() ? null : copy(message);
        } catch (RuntimeException e) {
            return null; // an unusual component; leave the line as it came
        } finally {
            replaced.clear();
        }
    }

    // ------------------------------------------------------------------------------------
    // Pass 1: find the codes in the text as read
    // ------------------------------------------------------------------------------------

    /** Walks the line in reading order, collecting neighbouring pieces of plain text into runs. */
    private void scan(ITextComponent component, List<TextComponentString> run) {
        if (component instanceof TextComponentString) {
            run.add((TextComponentString) component);
        } else {
            // Translated text, a score, a key name: whatever it shows sits between the texts around it.
            flush(run);
            if (component instanceof TextComponentTranslation) {
                for (Object arg : ((TextComponentTranslation) component).getFormatArgs()) {
                    if (arg instanceof ITextComponent) {
                        List<TextComponentString> inner = new ArrayList<TextComponentString>();
                        scan((ITextComponent) arg, inner);
                        flush(inner);
                    }
                }
            }
        }
        for (ITextComponent sibling : component.getSiblings()) {
            scan(sibling, run);
        }
    }

    /** Looks for codes in a run of text pieces and notes what each piece becomes. */
    private void flush(List<TextComponentString> run) {
        if (run.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        int[] starts = new int[run.size() + 1];
        for (int i = 0; i < run.size(); i++) {
            starts[i] = joined.length();
            joined.append(run.get(i).getText());
        }
        starts[run.size()] = joined.length();
        String text = joined.toString();
        List<List<Object>> parts = null;
        int done = 0, at = 0;
        Matcher emote = BetterConfig.chatEmotes ? EMOTE.matcher(text) : null;
        Matcher item = BetterConfig.chatItemLinks ? ItemLink.TOKEN.matcher(text) : null;
        while (at < text.length()) {
            boolean hasEmote = emote != null && emote.find(at), hasItem = item != null && item.find(at);
            if (!hasEmote && !hasItem) {
                break;
            }
            boolean useItem = hasItem && (!hasEmote || item.start() <= emote.start());
            Matcher hit = useItem ? item : emote;
            ITextComponent piece = useItem ? itemPiece(item) : emotePiece(emote.group(1));
            if (piece == null) {
                // Not a code after all ("12:30:45"); the closing colon may open the next one.
                at = useItem ? hit.end() : hit.end() - 1;
                continue;
            }
            if (parts == null) {
                parts = new ArrayList<List<Object>>();
                for (int i = 0; i < run.size(); i++) {
                    parts.add(new ArrayList<Object>());
                }
            }
            plain(text, done, hit.start(), starts, parts);
            parts.get(pieceAt(starts, hit.start())).add(piece);
            done = at = hit.end();
        }
        if (parts != null) {
            plain(text, done, text.length(), starts, parts);
            for (int i = 0; i < run.size(); i++) {
                replaced.put(run.get(i), parts.get(i));
            }
        }
        run.clear();
    }

    /** Hands the plain text between two positions back to the pieces it came from. */
    private static void plain(String text, int from, int to, int[] starts, List<List<Object>> parts) {
        for (int i = 0; i < parts.size() && from < to; i++) {
            int a = Math.max(from, starts[i]), b = Math.min(to, starts[i + 1]);
            if (a < b) {
                parts.get(i).add(text.substring(a, b));
            }
        }
    }

    private static int pieceAt(int[] starts, int position) {
        int i = 0;
        while (i < starts.length - 2 && starts[i + 1] <= position) {
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------------------------
    // Pass 2: the same line, with the changed pieces swapped
    // ------------------------------------------------------------------------------------

    private ITextComponent copy(ITextComponent source) {
        ITextComponent head;
        if (source instanceof TextComponentString) {
            List<Object> parts = replaced.get(source);
            if (parts == null) {
                head = new TextComponentString(((TextComponentString) source).getText());
            } else {
                head = new TextComponentString("");
                for (Object part : parts) {
                    head.appendSibling(part instanceof ITextComponent ? (ITextComponent) part : new TextComponentString((String) part));
                }
            }
            head.setStyle(source.getStyle().createShallowCopy());
        } else if (source instanceof TextComponentTranslation) {
            TextComponentTranslation translation = (TextComponentTranslation) source;
            Object[] args = translation.getFormatArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof ITextComponent) {
                    args[i] = copy((ITextComponent) args[i]);
                }
            }
            head = new TextComponentTranslation(translation.getKey(), args);
            head.setStyle(source.getStyle().createShallowCopy());
        } else {
            head = source.createCopy();
            head.getSiblings().clear();
        }
        for (ITextComponent sibling : source.getSiblings()) {
            head.appendSibling(copy(sibling));
        }
        return head;
    }

    private static ITextComponent emotePiece(String name) {
        Character ch = Emotes.find(name);
        if (ch == null) {
            return null;
        }
        TextComponentString piece = new TextComponentString(String.valueOf(ch.charValue()));
        // White and plain whatever the sentence around it is, so the picture keeps its own colours.
        piece.getStyle().setColor(TextFormatting.WHITE).setBold(false).setItalic(false).setUnderlined(false).setStrikethrough(false)
                .setObfuscated(false).setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentString(":" + name + ":")));
        return piece;
    }

    private static ITextComponent itemPiece(Matcher token) {
        ItemStack stack = ItemLink.decode(token);
        if (stack == null) {
            return null; // an item this game does not have: the readable name in the message stays as text
        }
        ITextComponent piece = ItemLink.component(stack);
        // The link carries its own look; whatever formatting its text happened to pick up on the way is dropped.
        piece.getStyle().setBold(false).setUnderlined(false).setStrikethrough(false).setObfuscated(false);
        return piece;
    }
}
