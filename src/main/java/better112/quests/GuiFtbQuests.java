package better112.quests;

import betterquesting.api.client.gui.misc.INeedsRefresh;
import betterquesting.api2.client.gui.GuiScreenCanvas;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/**
 * Better Questing's quest book, laid out the way FTB Quests lays out its own: chapters down
 * the left edge, the chapter's quests as round nodes on a canvas that pans and zooms, and a
 * quest opening as a window over the canvas instead of a separate page. The data, the task
 * and reward widgets, and everything sent to the server are Better Questing's own.
 */
public class GuiFtbQuests extends GuiScreenCanvas implements INeedsRefresh {

    private QuestsView view;
    private QuestWindow window;
    private int openQuest = -1;

    public GuiFtbQuests(GuiScreen parent) {
        super(parent);
        useMargins(false);
        useDefaultBG(false);
    }

    @Override
    public void initPanel() {
        super.initPanel();
        window = null;
        view = new QuestsView(this);
        addPanel(view);
        if (openQuest >= 0) {
            openQuest(openQuest);
        }
    }

    @Override
    public void refreshGui() {
        if (view != null) {
            view.rebuild();
        }
        if (window != null) {
            window.refresh();
        }
    }

    void openQuest(int questId) {
        QuestWindow created = QuestWindow.create(this, questId);
        if (created == null) {
            openQuest = -1;
            return;
        }
        openQuest = questId;
        window = created;
        openPopup(created);
    }

    void closeQuest() {
        openQuest = -1;
        window = null;
        closePopup();
        if (view != null) {
            view.rebuild();
        }
    }

    boolean hasWindow() {
        return window != null;
    }

    @Override
    public void keyTyped(char c, int keyCode) throws java.io.IOException {
        if (keyCode == Keyboard.KEY_ESCAPE && window != null) {
            closeQuest();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && parent != null) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(c, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // Self-test hooks.

    boolean testOpenFirstQuest() {
        int id = view == null ? -1 : view.firstQuestId();
        if (id < 0) {
            return false;
        }
        openQuest(id);
        return true;
    }

    boolean testSelectChapter(int index) {
        return view != null && view.selectIndex(index);
    }

    void testExpandSidebar(boolean open) {
        if (view != null) {
            view.forceBarOpen = open;
        }
    }
}
