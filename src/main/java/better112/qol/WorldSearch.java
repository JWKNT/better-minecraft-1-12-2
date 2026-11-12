package better112.qol;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import better112.Better112;
import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiListWorldSelection;
import net.minecraft.client.gui.GuiListWorldSelectionEntry;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiWorldSelection;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.storage.WorldSummary;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** A search box on the Select World screen, as the game has had since 1.16: type part of a world's name. */
public final class WorldSearch {

    private static String keptText = "";

    /** Last, so a mod with its own world list keeps it. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onOpen(GuiOpenEvent event) {
        if (!BetterConfig.worldSearch || event.getGui() == null || event.getGui().getClass() != GuiWorldSelection.class) {
            return;
        }
        try {
            GuiScreen parent = ReflectionHelper.getPrivateValue(GuiWorldSelection.class, (GuiWorldSelection) event.getGui(), "prevScreen", "field_184864_a");
            event.setGui(new Screen(parent));
        } catch (Throwable t) {
            Better112.LOG.warn("The world list stays as it is: {}", t.toString());
        }
    }

    /** The world list, showing only the worlds whose name or folder holds the search. */
    static final class FilteredList extends GuiListWorldSelection {
        private final List<GuiListWorldSelectionEntry> all = new ArrayList<GuiListWorldSelectionEntry>();
        private final List<GuiListWorldSelectionEntry> shown = new ArrayList<GuiListWorldSelectionEntry>();
        private static Field summaryField;

        @SuppressWarnings("unchecked")
        FilteredList(GuiWorldSelection screen, Minecraft mc, int width, int height, int top, int bottom) throws Exception {
            super(screen, mc, width, height, top, bottom, 36);
            for (Field field : GuiListWorldSelection.class.getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    all.addAll((List<GuiListWorldSelectionEntry>) field.get(this));
                }
            }
            shown.addAll(all);
        }

        static WorldSummary summary(GuiListWorldSelectionEntry entry) {
            try {
                if (summaryField == null) {
                    for (Field field : GuiListWorldSelectionEntry.class.getDeclaredFields()) {
                        if (field.getType() == WorldSummary.class) {
                            field.setAccessible(true);
                            summaryField = field;
                        }
                    }
                }
                return (WorldSummary) summaryField.get(entry);
            } catch (Throwable t) {
                return null;
            }
        }

        void refilter(String text) {
            String query = text.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (GuiListWorldSelectionEntry entry : all) {
                WorldSummary summary = summary(entry);
                if (query.isEmpty() || summary == null || summary.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
                        || summary.getFileName().toLowerCase(Locale.ROOT).contains(query)) {
                    shown.add(entry);
                }
            }
            scrollBy(-getAmountScrolled());
            // The selection's index belonged to the old list; the first (most recently played) world is picked.
            selectWorld(shown.isEmpty() ? -1 : 0);
        }

        int indexOf(GuiListWorldSelectionEntry entry) {
            return shown.indexOf(entry);
        }

        /** Scrolls just far enough that this row is in view. */
        void showIndex(int index) {
            int rowTop = index * slotHeight, rowBottom = rowTop + slotHeight, view = bottom - top - 4;
            if (rowTop < getAmountScrolled()) {
                scrollBy(rowTop - getAmountScrolled());
            } else if (rowBottom > getAmountScrolled() + view) {
                scrollBy(rowBottom - view - getAmountScrolled());
            }
        }

        int shownCount() {
            return shown.size();
        }

        @Override
        public GuiListWorldSelectionEntry getListEntry(int index) {
            return shown.get(index);
        }

        @Override
        protected int getSize() {
            return shown.size();
        }
    }

    public static final class Screen extends GuiWorldSelection {
        private GuiTextField search;
        private FilteredList list;

        Screen(GuiScreen parent) {
            super(parent);
        }

        @Override
        public void initGui() {
            super.initGui();
            try {
                list = new FilteredList(this, mc, width, height, 48, height - 64);
                ReflectionHelper.setPrivateValue(GuiWorldSelection.class, this, list, "selectionList", "field_184866_u");
            } catch (Throwable t) {
                Better112.LOG.warn("No search on the world list: {}", t.toString());
                list = null;
                return;
            }
            search = new GuiTextField(0xB1130, fontRenderer, width / 2 - 100, 22, 200, 20);
            search.setMaxStringLength(64);
            search.setText(keptText);
            search.setFocused(true);
            list.refilter(keptText);
        }

        /** Up and Down pick a world, Enter plays it; the newest world is picked from the start, as since 1.16. */
        private boolean listKey(int key) {
            int count = list.shownCount();
            if (count == 0) {
                return false;
            }
            GuiListWorldSelectionEntry chosen = list.getSelectedWorld();
            int index = chosen == null ? -1 : list.indexOf(chosen);
            if (key == 200 || key == 208) { // up, down
                int next = index < 0 ? 0 : Math.max(0, Math.min(count - 1, index + (key == 200 ? -1 : 1)));
                list.selectWorld(next);
                list.showIndex(next);
                return true;
            }
            if ((key == 28 || key == 156) && chosen != null) { // enter, keypad enter
                chosen.joinWorld();
                return true;
            }
            return false;
        }

        @Override
        public void updateScreen() {
            super.updateScreen();
            if (search != null) {
                search.updateCursorCounter();
            }
        }

        @Override
        protected void keyTyped(char typed, int key) throws IOException {
            if (list != null && listKey(key)) {
                return;
            }
            if (search != null && search.isFocused() && key != 1 && search.textboxKeyTyped(typed, key)) {
                keptText = search.getText();
                list.refilter(keptText);
                return;
            }
            super.keyTyped(typed, key);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
            super.mouseClicked(mouseX, mouseY, button);
            if (search != null) {
                search.mouseClicked(mouseX, mouseY, button);
                if (button == 1 && mouseX >= search.x && mouseX < search.x + search.width && mouseY >= search.y && mouseY < search.y + search.height) {
                    search.setText("");
                    keptText = "";
                    list.refilter("");
                }
            }
        }

        /** The title moves up to make room for the search box, where 1.16 puts it. */
        @Override
        public void drawCenteredString(FontRenderer font, String text, int x, int y, int color) {
            super.drawCenteredString(font, text, x, search != null && y == 20 && text.equals(title) ? 8 : y, color);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            if (search == null) {
                return;
            }
            search.drawTextBox();
            if (search.getText().isEmpty() && !search.isFocused()) {
                fontRenderer.drawStringWithShadow(I18n.format("better112.worlds.search"), search.x + 4, search.y + 6, 0xFF808080);
            }
            if (list.shownCount() == 0) {
                drawCenteredString(fontRenderer, I18n.format("better112.worlds.none"), width / 2, 60, 0xFFAAAAAA);
            }
        }

        /** Self-test: press Up (200), Down (208) or Enter (28) in the list; returns the world picked. */
        public String testKey(int key) {
            boolean used = list != null && key != 28 && listKey(key);
            GuiListWorldSelectionEntry chosen = list == null ? null : list.getSelectedWorld();
            WorldSummary summary = chosen == null ? null : FilteredList.summary(chosen);
            return (used ? "" : "(not used) ") + "picked " + (summary == null ? "nothing" : summary.getFileName());
        }

        /** Self-test: search as if typed; returns how many worlds are shown. */
        public String test(String text) {
            if (list == null) {
                return "no search (list not replaced)";
            }
            search.setText(text);
            keptText = text;
            list.refilter(text);
            return list.shownCount() + " worlds for '" + text + "'";
        }
    }
}
