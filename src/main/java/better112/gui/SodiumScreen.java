package better112.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import better112.ClientTweaks;
import better112.Hooks;
import better112.config.BetterConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.text.TextFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * The video settings screen, laid out the way Sodium's is: page tabs along the top, flat
 * option rows on the left, the hovered option's description on the right, and nothing takes
 * effect until Apply.
 */
public class SodiumScreen extends GuiScreen {

    private static final int ROW_HEIGHT = 18;
    private static final int GROUP_GAP = 4;
    private static final int MARGIN = 6;
    private static final int BACKGROUND = 0x90000000;
    private static final int BACKGROUND_HOVER = 0xE0000000;
    private static final int BACKGROUND_DISABLED = 0x60000000;

    private static int lastPage;

    /** Self-test hook: treat this row as hovered, since a script has no mouse. */
    public static int testHoverRow = -1;

    private final GuiScreen parent;
    private List<OptionPage> pages;
    private int pageIndex;

    private static final class Row {
        Option<?> option;
        int y;
    }

    private static final class Button {
        int x, y, width, height;
        String label;
        boolean enabled = true, selected, visible = true;
        Runnable action;

        boolean contains(int mx, int my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Button> tabs = new ArrayList<Button>();
    private Button applyButton, undoButton, closeButton;

    private int rowWidth;
    private int listTop, listBottom, contentHeight, scroll;
    private Option<?> dragging;
    private boolean draggingScrollbar;
    /** The colour picker, while one is open over the list. */
    private ColorPicker picker;

    public SodiumScreen(GuiScreen parent) {
        this.parent = parent;
        this.pageIndex = lastPage;
    }

    @Override
    public void initGui() {
        if (pages == null) {
            pages = OptionPages.build();
        }
        pageIndex = Math.max(0, Math.min(pages.size() - 1, pageIndex));
        rowWidth = width < 460 ? 200 : 240;

        tabs.clear();
        int x = MARGIN, y = MARGIN;
        for (int i = 0; i < pages.size(); i++) {
            final int index = i;
            Button tab = new Button();
            tab.label = pages.get(i).name();
            tab.width = 12 + fontRenderer.getStringWidth(tab.label);
            tab.height = ROW_HEIGHT;
            if (x + tab.width > width - MARGIN && x > MARGIN) {
                x = MARGIN;
                y += ROW_HEIGHT + 2;
            }
            tab.x = x;
            tab.y = y;
            tab.action = () -> selectPage(index);
            tabs.add(tab);
            x += tab.width + MARGIN;
        }
        listTop = y + ROW_HEIGHT + 4;
        listBottom = height - 40;

        applyButton = button(width - 142, height - 30, 65, 20, I18n.format("better112.button.apply"), this::applyChanges);
        closeButton = button(width - 73, height - 30, 65, 20, I18n.format("gui.done"), this::close);
        undoButton = button(width - 211, height - 30, 65, 20, I18n.format("better112.button.undo"), this::undoChanges);

        layoutRows();
    }

    private Button button(int x, int y, int w, int h, String label, Runnable action) {
        Button b = new Button();
        b.x = x;
        b.y = y;
        b.width = w;
        b.height = h;
        b.label = label;
        b.action = action;
        return b;
    }

    private void selectPage(int index) {
        picker = null;
        pageIndex = index;
        lastPage = index;
        scroll = 0;
        layoutRows();
    }

    private void layoutRows() {
        rows.clear();
        int y = 0;
        for (List<Option<?>> group : pages.get(pageIndex).groups) {
            for (Option<?> option : group) {
                Row row = new Row();
                row.option = option;
                row.y = y;
                rows.add(row);
                y += ROW_HEIGHT;
            }
            y += GROUP_GAP;
        }
        contentHeight = Math.max(0, y - GROUP_GAP);
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (listBottom - listTop));
    }

    private boolean hasChanges() {
        for (OptionPage page : pages) {
            for (Option<?> option : page.all()) {
                if (option.hasChanged()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------------------

    private void applyChanges() {
        int flags = 0;
        for (OptionPage page : pages) {
            for (Option<?> option : page.all()) {
                if (option.hasChanged()) {
                    option.apply();
                    flags |= option.flags();
                }
            }
        }
        Minecraft mc = this.mc;
        if ((flags & Option.VANILLA) != 0) {
            mc.gameSettings.saveOptions();
        }
        if ((flags & Option.BETTER) != 0) {
            BetterConfig.save();
            Hooks.refresh();
            ClientTweaks.applyConfig();
        }
        if ((flags & Option.MIPMAPS) != 0) {
            mc.gameSettings.onGuiClosed();
        }
        if ((flags & Option.RELOAD_DISPATCHER) != 0) {
            ClientTweaks.resetChunkDispatcher();
        }
        if ((flags & Option.RELOAD_ASSETS) != 0) {
            mc.refreshResources();
        } else if ((flags & (Option.RELOAD_CHUNKS | Option.RELOAD_DISPATCHER)) != 0) {
            mc.renderGlobal.loadRenderers();
        }
        if ((flags & Option.RESIZE) != 0) {
            ScaledResolution resolution = new ScaledResolution(mc);
            setWorldAndResolution(mc, resolution.getScaledWidth(), resolution.getScaledHeight());
        }
    }

    private void undoChanges() {
        for (OptionPage page : pages) {
            for (Option<?> option : page.all()) {
                option.reset();
            }
        }
    }

    private void close() {
        mc.displayGuiScreen(parent);
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        boolean changes = hasChanges();
        applyButton.enabled = changes;
        undoButton.visible = changes;
        closeButton.enabled = !changes;

        for (int i = 0; i < tabs.size(); i++) {
            Button tab = tabs.get(i);
            tab.selected = i == pageIndex;
            drawButton(tab, mouseX, mouseY);
        }

        Row hovered = null;
        boolean overList = mouseY >= listTop && mouseY < listBottom && mouseX >= MARGIN && mouseX < MARGIN + rowWidth;
        beginScissor(0, listTop, width, listBottom - listTop);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = listTop + row.y - scroll;
            if (y + ROW_HEIGHT <= listTop || y >= listBottom) {
                continue;
            }
            boolean hover = testHoverRow >= 0 ? i == testHoverRow
                    : overList && mouseY >= y && mouseY < y + ROW_HEIGHT && dragging == null || dragging == row.option;
            if (hover) {
                hovered = row;
            }
            drawRow(row.option, MARGIN, y, hover);
        }
        endScissor();

        if (maxScroll() > 0) {
            int trackX = MARGIN + rowWidth + 1, trackHeight = listBottom - listTop;
            int thumbHeight = Math.max(12, trackHeight * trackHeight / contentHeight);
            int thumbY = listTop + (trackHeight - thumbHeight) * scroll / maxScroll();
            Gui.drawRect(trackX, listTop, trackX + 2, listBottom, 0x40000000);
            Gui.drawRect(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xC0FFFFFF);
        }

        if (hovered != null) {
            drawTooltip(hovered.option, listTop + hovered.y - scroll);
        }

        drawButton(applyButton, mouseX, mouseY);
        drawButton(undoButton, mouseX, mouseY);
        drawButton(closeButton, mouseX, mouseY);

        if (picker != null) {
            picker.draw(fontRenderer, mouseX, mouseY);
        }
    }

    private void drawButton(Button b, int mouseX, int mouseY) {
        if (!b.visible) {
            return;
        }
        boolean hover = b.contains(mouseX, mouseY);
        Gui.drawRect(b.x, b.y, b.x + b.width, b.y + b.height,
                !b.enabled ? BACKGROUND_DISABLED : hover ? BACKGROUND_HOVER : BACKGROUND);
        int textWidth = fontRenderer.getStringWidth(b.label);
        fontRenderer.drawStringWithShadow(b.label, b.x + (b.width - textWidth) / 2.0F, b.y + (b.height - 8) / 2.0F,
                b.enabled ? 0xFFFFFFFF : 0x90FFFFFF);
        if (b.enabled && b.selected) {
            Gui.drawRect(b.x, b.y + b.height - 1, b.x + b.width, b.y + b.height, Control.ACCENT);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drawRow(Option option, int x, int y, boolean hovered) {
        Gui.drawRect(x, y, x + rowWidth, y + ROW_HEIGHT, hovered ? BACKGROUND_HOVER : BACKGROUND);
        String name = option.name();
        String suffix = option.isAvailable() && option.hasChanged() ? " *" : "";
        int room = rowWidth - 8 - option.control.reserved(option, fontRenderer, hovered || dragging == option)
                - fontRenderer.getStringWidth(suffix);
        if (fontRenderer.getStringWidth(name) > room) {
            name = fontRenderer.trimStringToWidth(name, Math.max(0, room - fontRenderer.getStringWidth("..."))) + "...";
        }
        String label;
        if (!option.isAvailable()) {
            label = TextFormatting.GRAY + "" + TextFormatting.STRIKETHROUGH + name;
        } else if (option.hasChanged()) {
            label = TextFormatting.ITALIC + name + suffix;
        } else {
            label = name;
        }
        fontRenderer.drawStringWithShadow(label, x + 6, y + ROW_HEIGHT / 2 - 4, 0xFFFFFFFF);
        option.control.render(option, fontRenderer, x, y, rowWidth, ROW_HEIGHT, hovered, dragging == option);
    }

    private void drawTooltip(Option<?> option, int rowY) {
        int boxX = MARGIN + rowWidth + 6;
        int boxWidth = Math.min(200, width - boxX - MARGIN);
        if (boxWidth < 80) {
            return;
        }
        List<String> lines = new ArrayList<String>(fontRenderer.listFormattedStringToWidth(option.tooltip(), boxWidth - 6));
        if (option.impact() != null) {
            lines.add(TextFormatting.GRAY + I18n.format("better112.impact", option.impact().color + option.impact().label()));
        }
        String note = option.isAvailable() ? option.note() : null;
        if (note != null && !note.isEmpty()) {
            lines.addAll(fontRenderer.listFormattedStringToWidth(TextFormatting.DARK_AQUA + note, boxWidth - 6));
        }
        if (!option.isAvailable() && option.unavailableReason() != null) {
            lines.addAll(fontRenderer.listFormattedStringToWidth(TextFormatting.RED + option.unavailableReason(), boxWidth - 6));
        }
        int boxHeight = lines.size() * 12 + 3;
        int boxY = Math.max(listTop, rowY);
        int cutoff = height - 40;
        if (boxY + boxHeight > cutoff) {
            boxY -= boxY + boxHeight - cutoff;
        }
        Gui.drawRect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0000000);
        for (int i = 0; i < lines.size(); i++) {
            fontRenderer.drawStringWithShadow(lines.get(i), boxX + 3, boxY + 3 + i * 12, 0xFFFFFFFF);
        }
    }

    private void beginScissor(int x, int y, int w, int h) {
        double scale = mc.displayWidth / (double) width;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * scale), (int) (mc.displayHeight - (y + h) * scale), (int) (w * scale), (int) (h * scale));
    }

    private void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0) {
            return;
        }
        if (picker != null) {
            if (picker.contains(mouseX, mouseY)) {
                picker.press(mouseX, mouseY);
            } else {
                picker = null; // a click anywhere else just closes it
            }
            return;
        }
        for (Button tab : tabs) {
            if (tab.contains(mouseX, mouseY)) {
                click();
                tab.action.run();
                return;
            }
        }
        for (Button b : new Button[] {applyButton, undoButton, closeButton}) {
            if (b.visible && b.enabled && b.contains(mouseX, mouseY)) {
                click();
                b.action.run();
                return;
            }
        }
        if (maxScroll() > 0 && mouseX >= MARGIN + rowWidth && mouseX < MARGIN + rowWidth + 5
                && mouseY >= listTop && mouseY < listBottom) {
            draggingScrollbar = true;
            scrollTo(mouseY);
            return;
        }
        if (mouseY < listTop || mouseY >= listBottom || mouseX < MARGIN || mouseX >= MARGIN + rowWidth) {
            return;
        }
        for (Row row : rows) {
            int y = listTop + row.y - scroll;
            if (mouseY >= y && mouseY < y + ROW_HEIGHT) {
                Option option = row.option;
                if (option.isAvailable()) {
                    click();
                    if (option.control instanceof Control.Colour) {
                        openPicker(option, y);
                    } else if (option.control.click(option, MARGIN, rowWidth, mouseX)) {
                        dragging = option;
                    }
                }
                return;
            }
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (picker != null) {
            picker.drag(mouseX, mouseY);
            return;
        }
        if (dragging != null) {
            Option option = dragging;
            option.control.drag(option, MARGIN, rowWidth, mouseX);
        } else if (draggingScrollbar) {
            scrollTo(mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (picker != null) {
            picker.release();
        }
        dragging = null;
        draggingScrollbar = false;
    }

    private void scrollTo(int mouseY) {
        int track = listBottom - listTop;
        scroll = Math.max(0, Math.min(maxScroll(), (mouseY - listTop) * contentHeight / Math.max(1, track) - track / 2));
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(wheel) * ROW_HEIGHT * 2));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (picker != null) {
            if (picker.key(typedChar, keyCode)) {
                picker = null;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (!hasChanges()) {
                close();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_P && isShiftKeyDown()) {
            ClientTweaks.openVanillaVideoSettings(new GuiVideoSettings(parent, mc.gameSettings));
        }
    }

    private void click() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @SuppressWarnings("unchecked")
    private void openPicker(Option<?> option, int rowY) {
        int px = Math.max(MARGIN, MARGIN + rowWidth - ColorPicker.WIDTH);
        int py = rowY + ROW_HEIGHT + 2;
        if (py + ColorPicker.HEIGHT > height - 4) {
            py = Math.max(4, rowY - ColorPicker.HEIGHT - 2);
        }
        picker = new ColorPicker((Option<Integer>) option, px, py);
    }

    /** Test hook: open the colour picker of an option, as a click on its row would. */
    public boolean testOpenPicker(String id) {
        for (Row row : rows) {
            if (row.option.id.equals(id) && row.option.control instanceof Control.Colour) {
                openPicker(row.option, listTop + row.y - scroll);
                return true;
            }
        }
        return false;
    }

    /** Test hook: jump to a page by id. */
    public boolean showPage(String id) {
        if (pages == null) {
            pages = OptionPages.build();
        }
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id.equals(id)) {
                selectPage(i);
                return true;
            }
        }
        return false;
    }

    /** Test hook: edit an option as if the player had, by id. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean testSet(String id, String value) {
        for (OptionPage page : pages()) {
            for (Option option : page.all()) {
                if (option.id.equals(id)) {
                    option.set(option.get() instanceof Boolean ? (Object) Boolean.valueOf(value)
                            : value.startsWith("#") ? (Object) Integer.valueOf(Integer.parseInt(value.substring(1), 16)) : (Object) Integer.valueOf(value));
                    return true;
                }
            }
        }
        return false;
    }

    /** Test hook: press Apply. */
    public void testApply() {
        applyChanges();
    }

    public List<OptionPage> pages() {
        if (pages == null) {
            pages = OptionPages.build();
        }
        return pages;
    }
}
