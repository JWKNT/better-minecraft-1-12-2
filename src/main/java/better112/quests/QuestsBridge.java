package better112.quests;

import better112.config.BetterConfig;

import betterquesting.client.gui2.GuiHome;
import betterquesting.client.gui2.GuiQuestLines;
import betterquesting.storage.QuestSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/** The only door into Better Questing code; call it only when Better Questing is loaded. */
public final class QuestsBridge {

    private QuestsBridge() {}

    /** Set while the player has asked for Better Questing's own screens; cleared when they close. */
    private static boolean classic;

    /** The screen to show instead of a Better Questing one, or null to leave it alone. */
    public static GuiScreen replace(GuiScreen opening, GuiScreen current) {
        if (opening == null) {
            classic = false;
            return null;
        }
        if (!BetterConfig.ftbQuests || classic) {
            return null;
        }
        if (!(opening instanceof GuiHome) && !(opening instanceof GuiQuestLines)) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || QuestSettings.INSTANCE.canUserEdit(mc.player)) {
            return null; // pack authors in edit mode need the original editors
        }
        return new GuiFtbQuests(null);
    }

    static void openClassic(GuiScreen parent) {
        classic = true;
        Minecraft.getMinecraft().displayGuiScreen(new GuiHome(parent));
    }

    /** Self-test hook. */
    public static GuiScreen create() {
        return new GuiFtbQuests(null);
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
