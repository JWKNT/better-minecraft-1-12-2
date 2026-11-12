package better112.quests;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api.enums.EnumQuestState;
import betterquesting.api.enums.EnumQuestVisibility;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;
import betterquesting.api.questing.IQuestLineEntry;
import betterquesting.api.utils.BigItemStack;
import betterquesting.api2.cache.QuestCache;
import betterquesting.api2.client.gui.misc.GuiAlign;
import betterquesting.api2.client.gui.misc.GuiPadding;
import betterquesting.api2.client.gui.misc.GuiTransform;
import betterquesting.api2.client.gui.misc.IGuiRect;
import betterquesting.api2.client.gui.panels.IGuiPanel;
import betterquesting.api2.storage.DBEntry;
import betterquesting.api2.utils.QuestTranslation;
import betterquesting.network.handlers.NetQuestAction;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestLineDatabase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/** Chapter bar, header and quest canvas: everything on the screen behind an open quest. */
final class QuestsView implements IGuiPanel {

    private static final int BAR = 26;
    private static final int BAR_OPEN = 180;
    private static final int HEADER = 26;
    private static final int ROW = 22;

    static final int BACKGROUND = 0xFF17171C;
    static final int PANEL = 0xFF202028;
    static final int ACCENT = 0xFF94E4D3;
    private static final int LOCKED = 0xFF4A4A56;
    private static final int OPEN = 0xFFB4BCCC;
    private static final int READY = 0xFFFFC83C;
    private static final int DONE = 0xFF5ADB6A;

    /** Remembered between openings. */
    private static int selectedId = -1;
    private static final Map<Integer, float[]> VIEWS = new HashMap<Integer, float[]>();

    private static final class Chapter {
        int id;
        IQuestLine line;
        String name;
        ItemStack icon = ItemStack.EMPTY;
        int total, done;
        boolean pending, shown;
    }

    private static final class Node {
        int id;
        IQuest quest;
        int x, y, w, h;
        ItemStack icon = ItemStack.EMPTY;
        EnumQuestState state = EnumQuestState.LOCKED;
        boolean main;
        String name;
    }

    private final GuiFtbQuests screen;
    private final Minecraft mc = Minecraft.getMinecraft();
    private final GuiTransform transform = new GuiTransform(GuiAlign.FULL_BOX, new GuiPadding(0, 0, 0, 0), 0);
    private boolean enabled = true;

    private final List<Chapter> chapters = new ArrayList<Chapter>();
    private Chapter selected;
    private final List<Node> nodes = new ArrayList<Node>();
    private final Map<Integer, Node> byId = new HashMap<Integer, Node>();
    private final List<Node[]> links = new ArrayList<Node[]>();

    private float panX, panY, zoom = 1.0F;
    private boolean pressed, moved;
    private int pressX, pressY, lastX, lastY;
    private Node pressedNode;
    private int barScroll;
    boolean forceBarOpen;
    private Node hovered;
    private String hoveredTip;
    /** The chapter bar slides open after the mouse has rested on it for a moment: 0 shut, 1 open. */
    private float barSlide;
    private long barTouched, lastFrame;
    private int barWidthNow = BAR;

    QuestsView(GuiFtbQuests screen) {
        this.screen = screen;
    }

    @Override
    public IGuiRect getTransform() {
        return transform;
    }

    @Override
    public void initPanel() {
        rebuild();
    }

    @Override
    public void setEnabled(boolean state) {
        enabled = state;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------------------------

    void rebuild() {
        chapters.clear();
        if (mc.player == null) {
            return;
        }
        UUID uuid = QuestingAPI.getQuestingUUID(mc.player);
        for (DBEntry<IQuestLine> entry : QuestLineDatabase.INSTANCE.getSortedEntries()) {
            IQuestLine line = entry.getValue();
            EnumQuestVisibility visibility = line.getProperty(NativeProps.VISIBILITY);
            if (visibility == EnumQuestVisibility.HIDDEN) {
                continue;
            }
            Chapter chapter = new Chapter();
            chapter.id = entry.getID();
            chapter.line = line;
            chapter.name = QuestTranslation.translate(line.getUnlocalisedName());
            chapter.icon = stack(line.getProperty(NativeProps.ICON));
            boolean unlocked = false, complete = false;
            for (DBEntry<IQuestLineEntry> q : line.getEntries()) {
                IQuest quest = QuestDatabase.INSTANCE.getValue(q.getID());
                if (quest == null) {
                    continue;
                }
                boolean done = quest.isComplete(uuid);
                if (QuestCache.isQuestShown(quest, uuid, mc.player)) {
                    chapter.shown = true;
                    chapter.total++;
                    if (done) {
                        chapter.done++;
                    }
                }
                if (done && !quest.hasClaimed(uuid) && quest.getRewards().size() > 0) {
                    chapter.pending = true;
                }
                unlocked |= quest.isUnlocked(uuid);
                complete |= done;
            }
            if (visibility == EnumQuestVisibility.COMPLETED && !complete || visibility == EnumQuestVisibility.UNLOCKED && !unlocked) {
                continue;
            }
            chapters.add(chapter);
        }
        Chapter keep = null;
        for (Chapter chapter : chapters) {
            if (chapter.id == selectedId && chapter.shown) {
                keep = chapter;
            }
        }
        if (keep == null) {
            for (Chapter chapter : chapters) {
                if (chapter.shown) {
                    keep = chapter;
                    break;
                }
            }
        }
        boolean changed = selected == null || keep == null || selected.id != keep.id;
        selected = keep;
        selectedId = keep == null ? -1 : keep.id;
        buildNodes(uuid);
        if (changed) {
            restoreView();
        }
    }

    private void buildNodes(UUID uuid) {
        nodes.clear();
        byId.clear();
        links.clear();
        if (selected == null) {
            return;
        }
        for (DBEntry<IQuestLineEntry> entry : selected.line.getEntries()) {
            IQuest quest = QuestDatabase.INSTANCE.getValue(entry.getID());
            if (quest == null || !QuestCache.isQuestShown(quest, uuid, mc.player)) {
                continue;
            }
            Node node = new Node();
            node.id = entry.getID();
            node.quest = quest;
            node.x = entry.getValue().getPosX();
            node.y = entry.getValue().getPosY();
            node.w = Math.max(8, entry.getValue().getSizeX());
            node.h = Math.max(8, entry.getValue().getSizeY());
            node.icon = stack(quest.getProperty(NativeProps.ICON));
            node.state = quest.getState(uuid);
            node.main = Boolean.TRUE.equals(quest.getProperty(NativeProps.MAIN));
            node.name = QuestTranslation.translate(quest.getProperty(NativeProps.NAME));
            nodes.add(node);
            byId.put(node.id, node);
        }
        for (Node node : nodes) {
            for (int required : node.quest.getRequirements()) {
                Node from = byId.get(required);
                if (from != null) {
                    links.add(new Node[] {from, node});
                }
            }
        }
    }

    private static ItemStack stack(BigItemStack big) {
        try {
            return big == null ? ItemStack.EMPTY : big.getBaseStack();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private void select(Chapter chapter) {
        if (chapter == null || !chapter.shown || chapter == selected) {
            return;
        }
        saveView();
        selectedId = chapter.id;
        rebuild();
        click();
    }

    boolean selectIndex(int index) {
        int i = 0;
        for (Chapter chapter : chapters) {
            if (chapter.shown && i++ == index) {
                select(chapter);
                return true;
            }
        }
        return false;
    }

    int firstQuestId() {
        for (Node node : nodes) {
            if (node.state != EnumQuestState.LOCKED) {
                return node.id;
            }
        }
        return nodes.isEmpty() ? -1 : nodes.get(0).id;
    }

    // ------------------------------------------------------------------------------------
    // View
    // ------------------------------------------------------------------------------------

    private int width() {
        return transform.getWidth();
    }

    private int height() {
        return transform.getHeight();
    }

    private float canvasWidth() {
        return Math.max(40, width() - BAR);
    }

    private float canvasHeight() {
        return Math.max(40, height() - HEADER);
    }

    /**
     * A chapter's view is remembered as the spot of the map in the middle of the canvas and
     * the zoom, with the canvas size it was for: after the window or the GUI scale changes,
     * the same part of the map is still in the middle, as large as the new room allows.
     */
    private void saveView() {
        if (selected != null && width() > 0) {
            float cx = BAR + canvasWidth() / 2.0F, cy = HEADER + canvasHeight() / 2.0F;
            VIEWS.put(selected.id, new float[] {(cx - panX) / zoom, (cy - panY) / zoom, zoom, canvasWidth(), canvasHeight()});
        }
    }

    private void restoreView() {
        float[] saved = selected == null ? null : VIEWS.get(selected.id);
        if (saved == null || saved.length < 5) {
            fit();
            return;
        }
        float grown = Math.min(canvasWidth() / saved[3], canvasHeight() / saved[4]);
        zoom = Math.max(0.2F, Math.min(3.0F, saved[2] * grown));
        panX = BAR + canvasWidth() / 2.0F - saved[0] * zoom;
        panY = HEADER + canvasHeight() / 2.0F - saved[1] * zoom;
        keepInView();
    }

    /** Stops the map from being dragged or zoomed entirely out of sight: a strip of it always stays on the canvas. */
    private void keepInView() {
        if (nodes.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Node node : nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + node.w);
            maxY = Math.max(maxY, node.y + node.h);
        }
        float keep = 48.0F;
        panX = Math.max(BAR + keep - maxX * zoom, Math.min(width() - keep - minX * zoom, panX));
        panY = Math.max(HEADER + keep - maxY * zoom, Math.min(height() - keep - minY * zoom, panY));
    }

    /** Zooms and centres so the whole chapter is in view. */
    private void fit() {
        if (nodes.isEmpty()) {
            panX = panY = 0;
            zoom = 1.0F;
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Node node : nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + node.w);
            maxY = Math.max(maxY, node.y + node.h);
        }
        float areaW = Math.max(40, width() - BAR - 40), areaH = Math.max(40, height() - HEADER - 40);
        zoom = Math.max(0.25F, Math.min(1.5F, Math.min(areaW / Math.max(1, maxX - minX), areaH / Math.max(1, maxY - minY))));
        panX = BAR + 20 + (areaW - (maxX - minX) * zoom) / 2.0F - minX * zoom;
        panY = HEADER + 20 + (areaH - (maxY - minY) * zoom) / 2.0F - minY * zoom;
        saveView();
    }

    /**
     * Whether the chapter bar should be open. It opens once the mouse has rested on it for a
     * moment (so brushing the edge of the screen while dragging the map does not throw a
     * panel over it) and stays open while the mouse is on the opened panel.
     */
    private boolean barWanted(int mx, long now) {
        if (forceBarOpen) {
            return true;
        }
        if (screen.hasWindow() || pressed) {
            barTouched = 0L;
            return false;
        }
        if (barSlide > 0.0F && mx >= 0 && mx < barWidthNow) {
            return true;
        }
        if (mx >= 0 && mx < BAR) {
            if (barTouched == 0L) {
                barTouched = now;
            }
            return now - barTouched >= 160L;
        }
        barTouched = 0L;
        return false;
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    @Override
    public void drawPanel(int mx, int my, float partialTick) {
        int w = width(), h = height();
        FontRenderer font = mc.fontRenderer;
        long now = Minecraft.getSystemTime();
        float step = lastFrame == 0L ? 1.0F : Math.min(1.0F, (now - lastFrame) / 110.0F);
        lastFrame = now;
        barSlide = Math.max(0.0F, Math.min(1.0F, barSlide + (barWanted(mx, now) ? step : -step)));
        // Eased, so the panel settles rather than stops.
        float eased = barSlide * barSlide * (3.0F - 2.0F * barSlide);
        barWidthNow = BAR + Math.round((BAR_OPEN - BAR) * eased);
        boolean open = barWidthNow > BAR;
        hovered = null;
        hoveredTip = null;

        // Dragging pans the canvas.
        if (pressed) {
            if (!Mouse.isButtonDown(0)) {
                pressed = false;
            } else {
                if (Math.abs(mx - pressX) > 3 || Math.abs(my - pressY) > 3) {
                    moved = true;
                }
                if (moved) {
                    panX += mx - lastX;
                    panY += my - lastY;
                    keepInView();
                }
                lastX = mx;
                lastY = my;
            }
        }

        Draw.rect(0, 0, w, h, BACKGROUND);

        // --- Canvas ---
        boolean overCanvas = !screen.hasWindow() && mx >= barWidthNow && my >= HEADER;
        Draw.scissor(mc, w, BAR, HEADER, w - BAR, h - HEADER);
        GlStateManager.pushMatrix();
        GlStateManager.translate(panX, panY, 0.0F);
        GlStateManager.scale(zoom, zoom, 1.0F);
        float wx = (mx - panX) / zoom, wy = (my - panY) / zoom;
        for (Node[] link : links) {
            Node from = link[0], to = link[1];
            int color = to.state == EnumQuestState.LOCKED ? 0xFF34343E : to.state == EnumQuestState.UNLOCKED ? OPEN : DONE;
            Draw.line(from.x + from.w / 2.0F, from.y + from.h / 2.0F, to.x + to.w / 2.0F, to.y + to.h / 2.0F,
                    to.main ? 3.0F : 2.0F, color);
        }
        for (Node node : nodes) {
            boolean hover = overCanvas && wx >= node.x && wx < node.x + node.w && wy >= node.y && wy < node.y + node.h;
            if (hover) {
                hovered = node;
            }
            drawNode(node, hover);
        }
        GlStateManager.popMatrix();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // --- Header ---
        Draw.rect(BAR, 0, w, HEADER, PANEL);
        Draw.rect(BAR, HEADER - 1, w, HEADER, 0xFF0E0E12);
        int bx = w - 6;
        bx = headerButton(I18n.format("better112.quests.classic"), bx, mx, my, true, I18n.format("better112.quests.classic.tip"));
        bx = headerButton(I18n.format("better112.quests.fit"), bx, mx, my, true, I18n.format("better112.quests.fit.tip"));
        bx = headerButton(I18n.format("better112.quests.claim_all"), bx, mx, my, selected != null && selected.pending,
                I18n.format("better112.quests.claim_all.tip"));
        if (selected != null) {
            // The title gets the room the buttons leave, and is cut short rather than run under them. It starts
            // clear of the chapter bar, also while that is open.
            String progress = selected.done + " / " + selected.total;
            int left = barWidthNow + 10;
            int room = bx - 8 - left - font.getStringWidth(progress) - 8;
            if (room > 30) {
                String title = font.trimStringToWidth(TextFormatting.BOLD + selected.name, room);
                font.drawStringWithShadow(title, left, 9, 0xFFFFFFFF);
                font.drawStringWithShadow(progress, left + 8 + font.getStringWidth(title), 9, 0xFF8A8A96);
            }
            if (selected.total > 0) {
                int barWidth = w - BAR;
                Draw.rect(BAR, HEADER - 2, BAR + barWidth * selected.done / selected.total, HEADER - 1, DONE);
            }
        } else {
            font.drawStringWithShadow(I18n.format("better112.quests.none"), BAR + 10, 9, 0xFF8A8A96);
        }

        // --- Chapter bar ---
        int barWidth = barWidthNow;
        Draw.rect(0, 0, barWidth, h, open ? 0xF814141A : 0xFF14141A);
        Draw.rect(barWidth - 1, 0, barWidth, h, 0xFF0E0E12);
        int maxScroll = Math.max(0, chapters.size() * ROW + 6 - h);
        barScroll = Math.max(0, Math.min(maxScroll, barScroll));
        Draw.scissor(mc, w, 0, 0, barWidth, h);
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            int y = 3 + i * ROW - barScroll;
            if (y + ROW < 0 || y > h) {
                continue;
            }
            boolean hover = !screen.hasWindow() && mx >= 0 && mx < barWidth && my >= y && my < y + ROW;
            if (chapter == selected) {
                Draw.rect(0, y, barWidth - 1, y + ROW - 2, 0xFF2A2A36);
                Draw.rect(0, y, 2, y + ROW - 2, ACCENT);
            } else if (hover && chapter.shown) {
                Draw.rect(0, y, barWidth - 1, y + ROW - 2, 0xFF22222C);
            }
            Draw.item(mc, chapter.icon, 5, y + 2);
            if (!chapter.shown) {
                Draw.rect(4, y + 1, 22, y + 19, 0xB014141A);
            }
            if (chapter.pending) {
                Draw.circle(20, y + 4, 3.0F, READY);
            } else if (chapter.total > 0 && chapter.done == chapter.total) {
                Draw.circle(20, y + 4, 3.0F, DONE);
            }
            if (open) {
                String count = chapter.total == 0 ? "" : chapter.done + "/" + chapter.total;
                int countWidth = font.getStringWidth(count);
                // Laid out for the full width while the panel slides, so the text does not reflow as it opens.
                barWidth = BAR_OPEN;
                String name = font.trimStringToWidth(chapter.name, barWidth - 30 - countWidth - 8);
                font.drawStringWithShadow(name, 27, y + 6, !chapter.shown ? 0xFF606068 : chapter == selected ? 0xFFFFFFFF : 0xFFC8C8D0);
                font.drawStringWithShadow(count, barWidth - 6 - countWidth, y + 6,
                        chapter.total > 0 && chapter.done == chapter.total ? DONE : 0xFF70707C);
                barWidth = barWidthNow;
            } else if (hover) {
                hoveredTip = chapter.name;
            }
        }
        if (maxScroll > 0) {
            // More chapters than fit: a thin thumb at the bar's edge shows where in the list this is.
            int content = chapters.size() * ROW + 6;
            int thumb = Math.max(12, h * h / content), top = (h - thumb) * barScroll / maxScroll;
            Draw.rect(barWidth - 3, top, barWidth - 1, top + thumb, 0xFF4A4A5A);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private int headerButton(String label, int right, int mx, int my, boolean active, String tip) {
        FontRenderer font = mc.fontRenderer;
        int width = font.getStringWidth(label) + 12;
        int x = right - width;
        boolean hover = !screen.hasWindow() && mx >= x && mx < right && my >= 4 && my < HEADER - 5;
        Draw.rect(x, 4, right, HEADER - 5, !active ? 0xFF1C1C22 : hover ? 0xFF3A3A48 : 0xFF2C2C38);
        font.drawStringWithShadow(label, x + 6, 9, active ? 0xFFFFFFFF : 0xFF60606A);
        if (hover) {
            hoveredTip = tip;
        }
        return x - 4;
    }

    /** Which header button is at the point: 0 claim all, 1 fit, 2 classic, -1 none. */
    private int headerButtonAt(int mx, int my) {
        if (my < 4 || my >= HEADER - 5) {
            return -1;
        }
        FontRenderer font = mc.fontRenderer;
        int right = width() - 6;
        String[] labels = {I18n.format("better112.quests.classic"), I18n.format("better112.quests.fit"),
            I18n.format("better112.quests.claim_all")};
        for (int i = 0; i < labels.length; i++) {
            int x = right - font.getStringWidth(labels[i]) - 12;
            if (mx >= x && mx < right) {
                return 2 - i;
            }
            right = x - 4;
        }
        return -1;
    }

    private void drawNode(Node node, boolean hover) {
        float cx = node.x + node.w / 2.0F, cy = node.y + node.h / 2.0F;
        float radius = Math.min(node.w, node.h) / 2.0F;
        int ring = node.state == EnumQuestState.LOCKED ? LOCKED : node.state == EnumQuestState.UNLOCKED ? OPEN
                : node.state == EnumQuestState.UNCLAIMED ? READY : DONE;
        if (hover) {
            Draw.circle(cx, cy, radius + 2.5F, 0x60FFFFFF);
        }
        Draw.circle(cx, cy, radius, ring);
        Draw.circle(cx, cy, radius - (node.main ? 2.5F : 1.5F), node.state == EnumQuestState.LOCKED ? 0xFF1D1D24 : 0xFF2B2B36);
        if (!node.icon.isEmpty()) {
            float scale = Math.max(0.25F, (Math.min(node.w, node.h) - 8) / 16.0F);
            GlStateManager.pushMatrix();
            GlStateManager.translate(cx - 8.0F * scale, cy - 8.0F * scale, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            Draw.item(mc, node.icon, 0, 0);
            GlStateManager.popMatrix();
        }
        if (node.state == EnumQuestState.LOCKED) {
            // Dimmed, but still plainly what it is.
            Draw.circle(cx, cy, radius - 1.5F, 0x6217171C);
        } else if (node.state == EnumQuestState.COMPLETED) {
            Draw.badge(cx + radius * 0.7F, cy + radius * 0.7F, Math.max(3.0F, radius * 0.36F), DONE, true);
        } else if (node.state == EnumQuestState.UNCLAIMED) {
            Draw.badge(cx + radius * 0.7F, cy - radius * 0.7F, Math.max(3.0F, radius * 0.36F), READY, false);
        }
    }

    // ------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------

    @Override
    public boolean onMouseClick(int mx, int my, int button) {
        if (button != 0) {
            return false;
        }
        int barWidth = barWidthNow;
        if (mx < barWidth) {
            int index = (my - 3 + barScroll) / ROW;
            if (my >= 3 && index >= 0 && index < chapters.size()) {
                select(chapters.get(index));
            }
            return true;
        }
        if (my < HEADER) {
            int which = headerButtonAt(mx, my);
            if (which == 0 && selected != null && selected.pending) {
                claimAll();
            } else if (which == 1) {
                fit();
                click();
            } else if (which == 2) {
                click();
                QuestsBridge.openClassic(null);
            }
            return true;
        }
        pressed = true;
        moved = false;
        pressX = lastX = mx;
        pressY = lastY = my;
        float wx = (mx - panX) / zoom, wy = (my - panY) / zoom;
        pressedNode = null;
        for (Node node : nodes) {
            if (wx >= node.x && wx < node.x + node.w && wy >= node.y && wy < node.y + node.h) {
                pressedNode = node;
            }
        }
        return true;
    }

    @Override
    public boolean onMouseRelease(int mx, int my, int button) {
        if (button != 0 || !pressed) {
            return false;
        }
        pressed = false;
        saveView();
        if (!moved && pressedNode != null) {
            click();
            screen.openQuest(pressedNode.id);
        }
        pressedNode = null;
        return true;
    }

    @Override
    public boolean onMouseScroll(int mx, int my, int scroll) {
        if (mx < barWidthNow) {
            barScroll += scroll * ROW * 2;
            return true;
        }
        if (my < HEADER) {
            return false;
        }
        float next = Math.max(0.2F, Math.min(3.0F, zoom * (scroll < 0 ? 1.2F : 1.0F / 1.2F)));
        panX = mx - (mx - panX) * next / zoom;
        panY = my - (my - panY) * next / zoom;
        zoom = next;
        keepInView();
        saveView();
        return true;
    }

    @Override
    public boolean onKeyTyped(char c, int keycode) {
        return false;
    }

    private void claimAll() {
        List<Integer> ids = new ArrayList<Integer>();
        for (Node node : nodes) {
            if (node.quest.getRewards().size() > 0 && node.quest.canClaim(mc.player)) {
                ids.add(node.id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        int[] array = new int[ids.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = ids.get(i);
        }
        NetQuestAction.requestClaim(array);
        click();
    }

    private void click() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public List<String> getTooltip(int mx, int my) {
        if (screen.hasWindow()) {
            return null;
        }
        List<String> lines = new ArrayList<String>();
        if (hovered != null) {
            Node node = hovered;
            lines.add(node.name);
            switch (node.state) {
                case LOCKED:
                    lines.add(TextFormatting.DARK_GRAY + I18n.format("better112.quests.state.locked"));
                    UUID uuid = QuestingAPI.getQuestingUUID(mc.player);
                    int listed = 0;
                    for (int required : node.quest.getRequirements()) {
                        IQuest quest = QuestDatabase.INSTANCE.getValue(required);
                        if (quest != null && !quest.isComplete(uuid) && listed++ < 4) {
                            lines.add(TextFormatting.GRAY + " - " + QuestTranslation.translate(quest.getProperty(NativeProps.NAME)));
                        }
                    }
                    break;
                case UNLOCKED:
                    lines.add(TextFormatting.GRAY + I18n.format("better112.quests.state.open"));
                    break;
                case UNCLAIMED:
                    lines.add(TextFormatting.GOLD + I18n.format("better112.quests.state.ready"));
                    break;
                default:
                    lines.add(TextFormatting.GREEN + I18n.format("better112.quests.state.done"));
                    break;
            }
            return lines;
        }
        if (hoveredTip != null) {
            lines.add(hoveredTip);
            return lines;
        }
        return null;
    }
}
