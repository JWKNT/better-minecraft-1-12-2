package better112.quests;

import better112.config.BetterConfig;

import betterquesting.client.gui2.GuiQuestLines;
import betterquesting.storage.QuestSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/** The only door into Better Questing code; call it only when Better Questing is loaded. */
public final class QuestsBridge {

    private QuestsBridge() {}

    /** Set while the player has asked for Better Questing's own screens; cleared when they close. */
    private static boolean classic;

    /**
     * The screen to show instead of a Better Questing one, or null to leave it alone. Only the
     * quest lines are replaced: Better Questing's home screen, with its party and theme buttons,
     * stays the way into the book, and Escape goes back to it.
     */
    public static GuiScreen replace(GuiScreen opening, GuiScreen current) {
        if (opening == null) {
            classic = false;
            return null;
        }
        if (!BetterConfig.ftbQuests || classic || !(opening instanceof GuiQuestLines)) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || QuestSettings.INSTANCE.canUserEdit(mc.player)) {
            return null; // pack authors in edit mode need the original editors
        }
        return new GuiFtbQuests(((GuiQuestLines) opening).parent);
    }

    /** Better Questing's own quest lines, until the book is closed. */
    static void openClassic(GuiScreen parent) {
        classic = true;
        Minecraft.getMinecraft().displayGuiScreen(new GuiQuestLines(parent));
    }

    /** True for the quest screen: it paints the whole window over before anything else, so the world behind is never seen. */
    public static boolean coversWorld(GuiScreen screen) {
        return screen instanceof GuiFtbQuests;
    }

    /** Self-test hook. */
    public static GuiScreen create() {
        return new GuiFtbQuests(null);
    }

    /** Self-test: what the quest key opens, then the home screen's Quests button. */
    public static String testOpen(String what) {
        Minecraft mc = Minecraft.getMinecraft();
        if (what.equals("key")) {
            mc.displayGuiScreen(betterquesting.client.themes.ThemeRegistry.INSTANCE.getGui(
                    betterquesting.api2.client.gui.themes.presets.PresetGUIs.HOME,
                    betterquesting.api2.client.gui.themes.gui_args.GArgsNone.NONE));
        } else if (what.equals("lines")) {
            mc.displayGuiScreen(new GuiQuestLines(mc.currentScreen));
        }
        return describe(mc.currentScreen);
    }

    public static String describe(GuiScreen screen) {
        if (screen == null) {
            return "no screen";
        }
        String text = screen.getClass().getSimpleName();
        if (screen instanceof betterquesting.api2.client.gui.GuiScreenCanvas) {
            GuiScreen parent = ((betterquesting.api2.client.gui.GuiScreenCanvas) screen).parent;
            text += " (back to " + (parent == null ? "nothing" : parent.getClass().getSimpleName()) + ")";
        }
        return text;
    }

    public static String testOp(GuiScreen screen, String op) {
        if (!(screen instanceof GuiFtbQuests)) {
            return "not the FTB layout: " + describe(screen);
        }
        return ((GuiFtbQuests) screen).testOp(op);
    }

    public static boolean testOpenFirstQuest(GuiScreen screen) {
        return screen instanceof GuiFtbQuests && ((GuiFtbQuests) screen).testOpenFirstQuest();
    }

    public static boolean testSelectChapter(GuiScreen screen, int index) {
        return screen instanceof GuiFtbQuests && ((GuiFtbQuests) screen).testSelectChapter(index);
    }

    public static void testExpandSidebar(GuiScreen screen, boolean open) {
        if (screen instanceof GuiFtbQuests) {
            ((GuiFtbQuests) screen).testExpandSidebar(open);
        }
    }
}
