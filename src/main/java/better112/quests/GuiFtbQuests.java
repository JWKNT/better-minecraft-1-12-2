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

    /**
     * press / release: the mouse button over the first open quest, as the game hands the events over;
     * with frames drawn in between while the real button is up, as after a quick click. escape: the key.
     */
    String testOp(String op) {
        try {
            int[] at = view == null ? null : view.testFirstNodeCenter();
            if (op.equals("press") || op.equals("release")) {
                if (at == null) {
                    return "no quest on the map";
                }
                // Better Questing reads the mouse events itself and hands them to these.
                if (op.equals("press")) {
                    onMouseClick(at[0], at[1], 0);
                    return "pressed at " + at[0] + ", " + at[1];
                }
                onMouseRelease(at[0], at[1], 0);
                return "released: " + (window != null ? "the quest window opened" : "no quest window");
            }
            if (op.equals("escape")) {
                keyTyped((char) 0, Keyboard.KEY_ESCAPE);
                return "Escape: now " + QuestsBridge.describe(mc.currentScreen);
            }
            if (op.equals("close")) {
                closeQuest();
                return "window closed";
            }
        } catch (Throwable t) {
            return op + " failed: " + t;
        }
        return "unknown " + op;
    }

    void testExpandSidebar(boolean open) {
        if (view != null) {
            view.forceBarOpen = open;
        }
    }
}
