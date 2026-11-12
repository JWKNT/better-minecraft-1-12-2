package better112.quests;

import java.util.List;
import java.util.UUID;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api.enums.EnumQuestState;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.rewards.IReward;
import betterquesting.api.questing.tasks.ITask;
import betterquesting.api2.client.gui.controls.PanelButton;
import betterquesting.api2.client.gui.misc.GuiAlign;
import betterquesting.api2.client.gui.misc.GuiPadding;
import betterquesting.api2.client.gui.misc.GuiRectangle;
import betterquesting.api2.client.gui.misc.GuiTransform;
import betterquesting.api2.client.gui.misc.IGuiRect;
import betterquesting.api2.client.gui.panels.CanvasEmpty;
import betterquesting.api2.client.gui.panels.IGuiPanel;
import betterquesting.api2.client.gui.panels.bars.PanelVScrollBar;
import betterquesting.api2.client.gui.panels.content.PanelTextBox;
import betterquesting.api2.client.gui.panels.lists.CanvasScrolling;
import betterquesting.api2.client.gui.resources.colors.GuiColorStatic;
import betterquesting.api2.client.gui.resources.textures.ColorTexture;
import betterquesting.api2.storage.DBEntry;
import betterquesting.api2.utils.QuestTranslation;
import betterquesting.network.handlers.NetQuestAction;
import betterquesting.questing.QuestDatabase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;

/**
 * A quest, opened as a window over the canvas: tasks on the left, rewards on the right, the
 * description underneath. The task and reward widgets are the ones Better Questing and its
 * expansions provide, so every task type works exactly as it does in the original book.
 */
final class QuestWindow extends CanvasEmpty {

    private final GuiFtbQuests screen;
    private final int questId;
    private final IQuest quest;
    private final Minecraft mc = Minecraft.getMinecraft();
    private int taskIndex, rewardIndex;
    private int x0, y0, ww, wh;
    /** Scroll bars inside the window, each with the list it scrolls (or null if that could not be told). */
    private final java.util.List<Object[]> bars = new java.util.ArrayList<Object[]>();
    /** Where the quest's state is written in the title bar, for its tooltip. */
    private int stateX, stateWidth;

    static QuestWindow create(GuiFtbQuests screen, int questId) {
        IQuest quest = QuestDatabase.INSTANCE.getValue(questId);
        return quest == null ? null : new QuestWindow(screen, questId, quest);
    }

    private QuestWindow(GuiFtbQuests screen, int questId, IQuest quest) {
        super(new GuiTransform(GuiAlign.FULL_BOX, new GuiPadding(0, 0, 0, 0), -10));
        this.screen = screen;
        this.questId = questId;
        this.quest = quest;
    }

    void refresh() {
        initPanel();
    }

    @Override
    public void initPanel() {
        super.initPanel();
        bars.clear();
        IGuiRect full = getTransform();
        int sw = full.getWidth(), sh = full.getHeight();
        ww = Math.min(420, sw - 36);
        wh = Math.min(290, sh - 20);
        x0 = (sw - ww) / 2;
        y0 = (sh - wh) / 2;

        addPanel(new Frame());

        PanelButton close = flat(new PanelButton(new GuiRectangle(x0 + ww - 18, y0 + 3, 14, 14, 0), -1, "x"));
        close.setClickAction(b -> screen.closeQuest());
        addPanel(close);

        int pad = 8;
        int column = (ww - pad * 3) / 2;
        int top = y0 + 24 + 12;
        int descHeight = Math.max(44, (int) (wh * 0.30F));
        int navY = y0 + wh - pad - descHeight - 8 - 16;
        int panelHeight = Math.max(30, navY - 4 - top);
        DBEntry<IQuest> entry = new DBEntry<IQuest>(questId, quest);

        // --- Tasks ---
        int tasks = quest.getTasks().size();
        taskIndex = Math.max(0, Math.min(tasks - 1, taskIndex));
        if (tasks > 0) {
            ITask task = quest.getTasks().getEntries().get(taskIndex).getValue();
            GuiRectangle rect = new GuiRectangle(x0 + pad, top, column, panelHeight, 0);
            rect.setParent(getTransform());
            try {
                IGuiPanel panel = task.getTaskGui(rect, entry);
                if (panel != null) {
                    addPanel(panel);
                    restyle(panel);
                }
            } catch (Throwable t) {
                better112.Better112.LOG.warn("A quest task could not draw itself", t);
            }
        }
        navigation(x0 + pad, navY, column, tasks, true);

        // --- Rewards ---
        int rewards = quest.getRewards().size();
        rewardIndex = Math.max(0, Math.min(rewards - 1, rewardIndex));
        int rx = x0 + pad * 2 + column;
        if (rewards > 0) {
            IReward reward = quest.getRewards().getEntries().get(rewardIndex).getValue();
            GuiRectangle rect = new GuiRectangle(rx, top, column, panelHeight, 0);
            rect.setParent(getTransform());
            try {
                IGuiPanel panel = reward.getRewardGui(rect, entry);
                if (panel != null) {
                    addPanel(panel);
                    restyle(panel);
                }
            } catch (Throwable t) {
                better112.Better112.LOG.warn("A quest reward could not draw itself", t);
            }
        }
        navigation(rx, navY, column, rewards, false);

        // --- Description ---
        int descY = y0 + wh - pad - descHeight;
        CanvasScrolling scroll = new CanvasScrolling(new GuiRectangle(x0 + pad, descY, ww - pad * 2 - 10, descHeight, 0));
        addPanel(scroll);
        String description = QuestTranslation.translate(quest.getProperty(NativeProps.DESC));
        PanelTextBox text = new PanelTextBox(new GuiRectangle(0, 0, scroll.getTransform().getWidth(), 0), description, true);
        text.setColor(new GuiColorStatic(0xFFD8D8E0));
        scroll.addCulledPanel(text, false);
        PanelVScrollBar bar = new PanelVScrollBar(new GuiRectangle(x0 + ww - pad - 8, descY, 8, descHeight, 0));
        addPanel(bar);
        scroll.setScrollDriverY(bar);
        flatBar(bar);
        bars.add(new Object[] {bar, scroll});
    }

    /** A scroll bar in this window's own plain style: a dark groove with a lighter thumb. */
    private static void flatBar(PanelVScrollBar bar) {
        GuiPadding slim = new GuiPadding(2, 0, 2, 0);
        bar.setBarTexture(new ColorTexture(new GuiColorStatic(0xFF17171C), slim), new ColorTexture(new GuiColorStatic(0xFF2A2A34), slim),
                new ColorTexture(new GuiColorStatic(0xFF4A4A5A), slim), new ColorTexture(new GuiColorStatic(0xFF6A6A80), slim));
    }

    /**
     * The task and reward widgets are Better Questing's own and bring its scroll bars with
     * them. Those are found and given the window's style, and each is paired with the list
     * next to it so it can be hidden while that list has nothing to scroll.
     */
    private void restyle(IGuiPanel panel) {
        if (!(panel instanceof betterquesting.api2.client.gui.panels.IGuiCanvas)) {
            return;
        }
        List<IGuiPanel> children;
        try {
            children = ((betterquesting.api2.client.gui.panels.IGuiCanvas) panel).getChildren();
        } catch (Throwable t) {
            return;
        }
        for (IGuiPanel child : new java.util.ArrayList<IGuiPanel>(children)) {
            if (child instanceof PanelVScrollBar) {
                PanelVScrollBar bar = (PanelVScrollBar) child;
                flatBar(bar);
                CanvasScrolling list = null;
                IGuiRect b = bar.getTransform();
                for (IGuiPanel other : children) {
                    if (other instanceof CanvasScrolling) {
                        IGuiRect o = other.getTransform();
                        boolean beside = b.getX() >= o.getX() + o.getWidth() - 2 && b.getX() <= o.getX() + o.getWidth() + 12;
                        boolean level = b.getY() < o.getY() + o.getHeight() && o.getY() < b.getY() + b.getHeight();
                        if (beside && level) {
                            list = (CanvasScrolling) other;
                        }
                    }
                }
                bars.add(new Object[] {bar, list});
            } else {
                restyle(child);
            }
        }
    }

    /** The row under a column: previous, the action button, next. */
    private void navigation(int x, int y, int width, int count, boolean tasks) {
        if (count > 1) {
            PanelButton previous = flat(new PanelButton(new GuiRectangle(x, y, 16, 16, 0), -1, "<"));
            previous.setActive((tasks ? taskIndex : rewardIndex) > 0);
            previous.setClickAction(b -> {
                if (tasks) {
                    taskIndex--;
                } else {
                    rewardIndex--;
                }
                refresh();
            });
            addPanel(previous);
            PanelButton next = flat(new PanelButton(new GuiRectangle(x + width - 16, y, 16, 16, 0), -1, ">"));
            next.setActive((tasks ? taskIndex : rewardIndex) < count - 1);
            next.setClickAction(b -> {
                if (tasks) {
                    taskIndex++;
                } else {
                    rewardIndex++;
                }
                refresh();
            });
            addPanel(next);
        }
        int inset = count > 1 ? 20 : 0;
        String label = I18n.format(tasks ? "better112.quests.submit" : "better112.quests.claim");
        PanelButton action = flat(new PanelButton(new GuiRectangle(x + inset, y, width - inset * 2, 16, 0), -1, label));
        if (tasks) {
            action.setActive(quest.canSubmit(mc.player));
            action.setClickAction(b -> NetQuestAction.requestDetect(new int[] {questId}));
        } else {
            action.setActive(count > 0 && quest.canClaim(mc.player));
            action.setClickAction(b -> NetQuestAction.requestClaim(new int[] {questId}));
        }
        addPanel(action);
    }

    private static PanelButton flat(PanelButton button) {
        button.setTextures(new ColorTexture(new GuiColorStatic(0xFF1C1C22)), new ColorTexture(new GuiColorStatic(0xFF2E2E3A)),
                new ColorTexture(new GuiColorStatic(0xFF41414F)));
        button.setTextHighlight(new GuiColorStatic(0xFF5E5E68), new GuiColorStatic(0xFFFFFFFF), new GuiColorStatic(QuestsView.ACCENT));
        return button;
    }

    /** Backdrop, window body, title and section headings. Sits behind the widgets. */
    private final class Frame implements IGuiPanel {
        private final GuiTransform transform = new GuiTransform(GuiAlign.FULL_BOX, new GuiPadding(0, 0, 0, 0), 5);
        private boolean enabled = true;

        @Override
        public IGuiRect getTransform() {
            return transform;
        }

        @Override
        public void initPanel() {}

        @Override
        public void setEnabled(boolean state) {
            enabled = state;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void drawPanel(int mx, int my, float partialTick) {
            FontRenderer font = mc.fontRenderer;
            int sw = transform.getWidth(), sh = transform.getHeight();
            // A scroll bar is only shown while its list is longer than its box.
            for (Object[] pair : bars) {
                if (pair[1] != null) {
                    try {
                        ((PanelVScrollBar) pair[0]).setEnabled(((CanvasScrolling) pair[1]).getScrollBounds().getHeight() > 0);
                    } catch (Throwable ignored) {
                        // leave the bar as it is
                    }
                }
            }
            Draw.rect(0, 0, sw, sh, 0xA0000000);
            Draw.rect(x0 - 1, y0 - 1, x0 + ww + 1, y0 + wh + 1, 0xFF0A0A0E);
            Draw.rect(x0, y0, x0 + ww, y0 + wh, QuestsView.PANEL);
            Draw.rect(x0, y0, x0 + ww, y0 + 20, 0xFF2A2A36);

            UUID uuid = QuestingAPI.getQuestingUUID(mc.player);
            EnumQuestState state = quest.getState(uuid);
            int stateColor = state == EnumQuestState.COMPLETED ? 0xFF5ADB6A : state == EnumQuestState.UNCLAIMED ? 0xFFFFC83C
                    : state == EnumQuestState.UNLOCKED ? QuestsView.ACCENT : 0xFF4A4A56;
            Draw.rect(x0, y0 + 20, x0 + ww, y0 + 21, stateColor);

            ItemStack icon = ItemStack.EMPTY;
            try {
                icon = quest.getProperty(NativeProps.ICON).getBaseStack();
            } catch (Throwable ignored) {
                // no icon
            }
            Draw.item(mc, icon, x0 + 3, y0 + 2);
            String title = font.trimStringToWidth(TextFormatting.BOLD + QuestTranslation.translate(quest.getProperty(NativeProps.NAME)), ww - 110);
            font.drawStringWithShadow(title, x0 + 24, y0 + 6, 0xFFFFFFFF);
            String stateText = I18n.format(state == EnumQuestState.COMPLETED ? "better112.quests.state.done"
                    : state == EnumQuestState.UNCLAIMED ? "better112.quests.state.ready"
                    : state == EnumQuestState.UNLOCKED ? "better112.quests.state.open" : "better112.quests.state.locked");
            stateWidth = font.getStringWidth(stateText);
            stateX = x0 + ww - 24 - stateWidth;
            font.drawStringWithShadow(stateText, stateX, y0 + 6, stateColor);

            int pad = 8;
            int column = (ww - pad * 3) / 2;
            heading(font, I18n.format("better112.quests.tasks"), quest.getTasks().size(), taskIndex, x0 + pad, y0 + 25, column, true);
            heading(font, I18n.format("better112.quests.rewards"), quest.getRewards().size(), rewardIndex, x0 + pad * 2 + column, y0 + 25, column, false);
            Draw.rect(x0 + pad + column + pad / 2, y0 + 25, x0 + pad + column + pad / 2 + 1, y0 + wh - pad - Math.max(44, (int) (wh * 0.30F)) - 6, 0xFF14141A);
            int descY = y0 + wh - pad - Math.max(44, (int) (wh * 0.30F));
            Draw.rect(x0 + pad, descY - 5, x0 + ww - pad, descY - 4, 0xFF14141A);
        }

        private void heading(FontRenderer font, String label, int count, int index, int x, int y, int width, boolean tasks) {
            String name = "";
            try {
                if (count > 0) {
                    name = QuestTranslation.translate(tasks ? quest.getTasks().getEntries().get(index).getValue().getUnlocalisedName()
                            : quest.getRewards().getEntries().get(index).getValue().getUnlocalisedName());
                }
            } catch (Throwable ignored) {
                // heading only
            }
            String text = count == 0 ? label + TextFormatting.DARK_GRAY + "  " + I18n.format("better112.quests.none_here")
                    : label + TextFormatting.GRAY + "  " + name + (count > 1 ? "  " + (index + 1) + "/" + count : "");
            font.drawStringWithShadow(font.trimStringToWidth(text, width), x, y, QuestsView.ACCENT);
        }

        @Override
        public boolean onMouseClick(int mx, int my, int button) {
            if (mx < x0 || mx >= x0 + ww || my < y0 || my >= y0 + wh) {
                screen.closeQuest();
            }
            return true;
        }

        @Override
        public boolean onMouseRelease(int mx, int my, int button) {
            return false;
        }

        @Override
        public boolean onMouseScroll(int mx, int my, int scroll) {
            return true;
        }

        @Override
        public boolean onKeyTyped(char c, int keycode) {
            return false;
        }

        @Override
        public List<String> getTooltip(int mx, int my) {
            // On a locked quest, its state in the title bar says what is still in the way.
            if (mx < stateX || mx >= stateX + stateWidth || my < y0 + 3 || my >= y0 + 17) {
                return null;
            }
            UUID uuid = QuestingAPI.getQuestingUUID(mc.player);
            if (quest.getState(uuid) != EnumQuestState.LOCKED) {
                return null;
            }
            List<String> lines = new java.util.ArrayList<String>();
            lines.add(I18n.format("better112.quests.needs"));
            for (int required : quest.getRequirements()) {
                IQuest other = QuestDatabase.INSTANCE.getValue(required);
                if (other != null && !other.isComplete(uuid) && lines.size() < 9) {
                    lines.add(TextFormatting.GRAY + " - " + QuestTranslation.translate(other.getProperty(NativeProps.NAME)));
                }
            }
            return lines.size() > 1 ? lines : null;
        }
    }
}
