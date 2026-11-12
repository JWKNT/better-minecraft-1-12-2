package better112.qol;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import better112.Better112;
import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiKeyBindingList;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * A search box and a filter on the Controls screen, as modern packs get from the Controlling mod: type to
 * find a key by its name, its category or the key it is set to (@R lists everything on R), and show only
 * the keys that clash with another, the unbound ones or the ones changed from their default.
 */
public final class ControlsSearch {

    private static final String[] FILTERS = {"all", "conflicts", "unbound", "changed"};
    private static String keptText = "";
    private static int keptFilter;
    private static Field keyOfEntry;

    /** Last, so a mod that brings its own Controls screen (EnderModpackTweaks in Divine Journey 2) keeps it. */
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)
    public void onOpen(GuiOpenEvent event) {
        if (!BetterConfig.keybindSearch || event.getGui() == null || event.getGui().getClass() != GuiControls.class) {
            return;
        }
        try {
            GuiScreen parent = ReflectionHelper.getPrivateValue(GuiControls.class, (GuiControls) event.getGui(), "parentScreen", "field_146496_h");
            event.setGui(new Screen(parent));
        } catch (Throwable t) {
            Better112.LOG.warn("The Controls screen stays as it is: {}", t.toString());
        }
    }

    static KeyBinding keyOf(GuiListExtended.IGuiListEntry entry) {
        try {
            if (keyOfEntry == null || keyOfEntry.getDeclaringClass() != entry.getClass()) {
                for (Field field : entry.getClass().getDeclaredFields()) {
                    if (field.getType() == KeyBinding.class) {
                        field.setAccessible(true);
                        keyOfEntry = field;
                    }
                }
            }
            return keyOfEntry != null && keyOfEntry.getDeclaringClass() == entry.getClass() ? (KeyBinding) keyOfEntry.get(entry) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean conflicting(KeyBinding key, KeyBinding[] all) {
        if (key.getKeyCode() == 0) {
            return false;
        }
        for (KeyBinding other : all) {
            if (other != key && other.getKeyCode() != 0 && key.conflicts(other)) {
                return true;
            }
        }
        return false;
    }

    static boolean passes(int filter, KeyBinding key, KeyBinding[] all) {
        switch (filter) {
            case 1:
                return conflicting(key, all);
            case 2:
                return key.getKeyCode() == 0;
            case 3:
                return !key.isSetToDefaultValue();
            default:
                return true;
        }
    }

    /** The key list, showing only what the search and the filter let through. */
    static final class SearchList extends GuiKeyBindingList {
        private final List<GuiListExtended.IGuiListEntry> all = new ArrayList<>();
        private final List<GuiListExtended.IGuiListEntry> shown = new ArrayList<>();

        SearchList(GuiControls controls, Minecraft mc) throws Exception {
            super(controls, mc);
            for (Field field : GuiKeyBindingList.class.getDeclaredFields()) {
                if (field.getType() == GuiListExtended.IGuiListEntry[].class) {
                    field.setAccessible(true);
                    for (GuiListExtended.IGuiListEntry entry : (GuiListExtended.IGuiListEntry[]) field.get(this)) {
                        if (entry != null) {
                            all.add(entry);
                        }
                    }
                }
            }
            if (all.isEmpty()) {
                throw new NoSuchFieldException("key list entries");
            }
            shown.addAll(all);
        }

        /** Returns how many keys are shown. */
        int refilter(String text, int filter) {
            KeyBinding[] keys = Minecraft.getMinecraft().gameSettings.keyBindings;
            String query = text.trim().toLowerCase(Locale.ROOT);
            boolean byKey = query.startsWith("@");
            if (byKey) {
                query = query.substring(1).trim();
            }
            shown.clear();
            GuiListExtended.IGuiListEntry header = null;
            String category = "";
            int count = 0;
            for (GuiListExtended.IGuiListEntry entry : all) {
                KeyBinding key = keyOf(entry);
                if (key == null) {
                    header = entry;
                    continue;
                }
                if (!category.equals(key.getKeyCategory())) {
                    category = key.getKeyCategory();
                }
                boolean fits;
                if (query.isEmpty()) {
                    fits = true;
                } else if (byKey) {
                    fits = key.getKeyCode() != 0 && key.getDisplayName().toLowerCase(Locale.ROOT).equals(query);
                } else {
                    fits = I18n.format(key.getKeyDescription()).toLowerCase(Locale.ROOT).contains(query)
                            || I18n.format(category).toLowerCase(Locale.ROOT).contains(query)
                            || key.getKeyDescription().toLowerCase(Locale.ROOT).contains(query);
                }
                if (fits && passes(filter, key, keys)) {
                    if (header != null) {
                        shown.add(header);
                        header = null;
                    }
                    shown.add(entry);
                    count++;
                }
            }
            scrollBy(0);
            return count;
        }

        @Override
        protected int getSize() {
            return shown.size();
        }

        @Override
        public GuiListExtended.IGuiListEntry getListEntry(int index) {
            return shown.get(index);
        }
    }

    public static final class Screen extends GuiControls {
        private static final int SEARCH_ID = 0xB1120, FILTER_ID = 0xB1121;
        private GuiTextField search;
        private GuiButton filterButton;
        private SearchList list;
        private int shownCount;
        private int keysHash;

        Screen(GuiScreen parent) {
            super(parent, Minecraft.getMinecraft().gameSettings);
        }

        @Override
        public void initGui() {
            super.initGui();
            try {
                list = new SearchList(this, mc);
                ReflectionHelper.setPrivateValue(GuiControls.class, this, list, "keyBindingList", "field_146494_r");
            } catch (Throwable t) {
                Better112.LOG.warn("No search on the Controls screen: {}", t.toString());
                list = null;
                return;
            }
            int left = width / 2 - 155;
            search = new GuiTextField(SEARCH_ID, fontRenderer, left + 1, 67, 228, 18);
            search.setMaxStringLength(64);
            search.setText(keptText);
            search.setFocused(true);
            filterButton = addButton(new GuiButton(FILTER_ID, left + 235, 66, 75, 20, ""));
            list.top = 90;
            refilter();
        }

        private static int hashKeys() {
            int hash = 1;
            for (KeyBinding key : Minecraft.getMinecraft().gameSettings.keyBindings) {
                hash = hash * 31 + key.getKeyCode() * 7 + key.getKeyModifier().ordinal();
            }
            return hash;
        }

        private void refilter() {
            if (list == null) {
                return;
            }
            keptText = search.getText();
            shownCount = list.refilter(keptText, keptFilter);
            keysHash = hashKeys();
            KeyBinding[] keys = mc.gameSettings.keyBindings;
            int matching = 0;
            for (KeyBinding key : keys) {
                if (passes(keptFilter, key, keys)) {
                    matching++;
                }
            }
            String name = I18n.format("better112.controls.filter." + FILTERS[keptFilter]);
            filterButton.displayString = keptFilter == 0 ? name : name + " (" + matching + ")";
        }

        @Override
        public void updateScreen() {
            super.updateScreen();
            if (search != null) {
                search.updateCursorCounter();
                // A key just set may have started or ended a clash: keep the list true to it, but not mid-edit.
                if (buttonId == null && hashKeys() != keysHash) {
                    refilter();
                }
            }
        }

        @Override
        protected void actionPerformed(GuiButton button) throws IOException {
            if (button.id == FILTER_ID) {
                keptFilter = (keptFilter + 1) % FILTERS.length;
                refilter();
                return;
            }
            super.actionPerformed(button);
        }

        @Override
        protected void keyTyped(char typed, int key) throws IOException {
            if (search != null && buttonId == null && search.isFocused() && key != 1) {
                if (search.textboxKeyTyped(typed, key)) {
                    refilter();
                }
                return;
            }
            super.keyTyped(typed, key);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
            boolean choosing = buttonId != null;
            super.mouseClicked(mouseX, mouseY, button);
            if (search != null && !choosing) {
                search.mouseClicked(mouseX, mouseY, button);
                if (button == 1 && mouseX >= search.x && mouseX < search.x + search.width && mouseY >= search.y
                        && mouseY < search.y + search.height) {
                    search.setText(""); // right click clears, as in the creative search
                    refilter();
                }
            }
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            if (search == null) {
                return;
            }
            search.drawTextBox();
            if (search.getText().isEmpty() && !search.isFocused()) {
                fontRenderer.drawStringWithShadow(I18n.format("better112.controls.search"), search.x + 4, search.y + 5, 0xFF808080);
            } else if (search.getText().isEmpty()) {
                fontRenderer.drawString(I18n.format("better112.controls.search"), search.x + 4 + 6, search.y + 5, 0xFF606060);
            }
            if (shownCount == 0) {
                drawCenteredString(fontRenderer, I18n.format("better112.controls.none"), width / 2, list.top + 20, 0xFFAAAAAA);
            }
        }

        /** Self-test: search and filter as if typed and clicked; returns how many keys are shown. */
        public String test(String text, int filter) {
            if (list == null) {
                return "no search (list not replaced)";
            }
            search.setText(text);
            keptFilter = filter;
            refilter();
            return shownCount + " keys for '" + text + "' / " + filterButton.displayString + ", of " + mc.gameSettings.keyBindings.length;
        }
    }

    /** Self-test: what conflicts the game has, by key. */
    public static String testConflicts() {
        KeyBinding[] keys = Minecraft.getMinecraft().gameSettings.keyBindings;
        List<String> names = new ArrayList<>();
        for (KeyBinding key : keys) {
            if (conflicting(key, keys)) {
                names.add(key.getDisplayName());
            }
        }
        String[] sorted = names.toArray(new String[0]);
        Arrays.sort(sorted);
        return names.size() + " clashing keys: " + String.join(" ", sorted);
    }
}
