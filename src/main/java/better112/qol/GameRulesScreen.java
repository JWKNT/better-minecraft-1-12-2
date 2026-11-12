package better112.qol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import better112.Better112;
import better112.config.BetterConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

/**
 * Game rules at world creation, as in 1.16: a Game Rules button among the world options opens a list of
 * the rules, and what is changed there is set on the new world the moment it starts.
 */
public final class GameRulesScreen extends GuiScreen {

    private static final int BUTTON_ID = 0xB112;
    private static final int ROW = 24, LIST_TOP = 32;

    /** Rules the player changed for the world being created, by name. */
    private static final Map<String, String> CHOSEN = Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private static GuiScreen createScreen;
    private static boolean armed;

    /** Listens on the world creation screen; registered once at start-up. */
    public static final class Events {
        private GuiButton ours, moreOnly;

        @SubscribeEvent
        public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
            if (!(event.getGui() instanceof GuiCreateWorld)) {
                return;
            }
            if (event.getGui() != createScreen) {
                // Another world is being set up: start from the defaults again.
                createScreen = event.getGui();
                CHOSEN.clear();
                armed = false;
            }
            ours = null;
            moreOnly = null;
            if (!BetterConfig.gameRulesButton) {
                return;
            }
            for (GuiButton button : event.getButtonList()) {
                if (button.id == 5) {
                    moreOnly = button; // World Type, shown only among the further options
                }
            }
            GuiScreen gui = event.getGui();
            ours = new GuiButton(BUTTON_ID, gui.width / 2 + 80, 187, 75, 20, I18n.format("better112.gamerules.title"));
            ours.visible = moreOnly != null && moreOnly.visible;
            event.getButtonList().add(ours);
        }

        @SubscribeEvent
        public void onDraw(GuiScreenEvent.DrawScreenEvent.Pre event) {
            if (ours != null && event.getGui() == createScreen) {
                ours.visible = moreOnly != null && moreOnly.visible;
            }
        }

        @SubscribeEvent
        public void onButton(GuiScreenEvent.ActionPerformedEvent.Pre event) {
            if (!(event.getGui() instanceof GuiCreateWorld)) {
                return;
            }
            if (event.getButton().id == BUTTON_ID) {
                event.getGui().mc.displayGuiScreen(new GameRulesScreen(event.getGui()));
                event.setCanceled(true);
            } else if (event.getButton().id == 0) {
                armed = true; // Create New World
            }
        }
    }

    /** Called when a single-player world has started: sets the chosen rules if this is the world just created. */
    public static void worldStarted(MinecraftServer server) {
        if (!armed) {
            return;
        }
        armed = false;
        World world = server.getWorld(0);
        if (world == null || world.getTotalWorldTime() > 200L) {
            return; // not a new world
        }
        synchronized (CHOSEN) {
            for (Map.Entry<String, String> rule : CHOSEN.entrySet()) {
                world.getGameRules().setOrCreateGameRule(rule.getKey(), rule.getValue());
            }
            if (!CHOSEN.isEmpty()) {
                Better112.LOG.info("Set {} game rule(s) chosen at world creation: {}", CHOSEN.size(), CHOSEN);
            }
            CHOSEN.clear();
        }
    }

    private static final class Row {
        String rule;
        boolean flag;
        GuiButton button;
        GuiTextField box;
    }

    private final GuiScreen parent;
    private final GameRules defaults = new GameRules();
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private int scroll;

    public GameRulesScreen(GuiScreen parent) {
        this.parent = parent;
        for (String rule : defaults.getRules()) {
            boolean flag = defaults.areSameType(rule, GameRules.ValueType.BOOLEAN_VALUE);
            if (flag || defaults.areSameType(rule, GameRules.ValueType.NUMERICAL_VALUE)) {
                String chosen = CHOSEN.get(rule);
                values.put(rule, chosen != null ? chosen : defaults.getString(rule));
            }
        }
    }

    private static String title(String rule) {
        String key = "better112.gamerule." + rule;
        if (I18n.hasKey(key)) {
            return I18n.format(key);
        }
        // doFireTick -> Do Fire Tick
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (i == 0) {
                text.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                text.append(' ').append(c);
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    private String onOff(String rule) {
        return I18n.format("true".equals(values.get(rule)) ? "options.on" : "options.off");
    }

    @Override
    public void initGui() {
        buttonList.clear();
        rows.clear();
        int id = 100;
        for (String rule : values.keySet()) {
            Row row = new Row();
            row.rule = rule;
            row.flag = defaults.areSameType(rule, GameRules.ValueType.BOOLEAN_VALUE);
            if (row.flag) {
                row.button = new GuiButton(id++, width / 2 + 55, 0, 100, 20, onOff(rule));
                buttonList.add(row.button);
            } else {
                row.box = new GuiTextField(id++, fontRenderer, width / 2 + 56, 0, 98, 18);
                row.box.setMaxStringLength(9);
                row.box.setText(values.get(rule));
            }
            rows.add(row);
        }
        buttonList.add(new GuiButton(1, width / 2 - 155, height - 28, 150, 20, I18n.format("gui.done")));
        buttonList.add(new GuiButton(2, width / 2 + 5, height - 28, 150, 20, I18n.format("gui.cancel")));
        place();
    }

    private int listBottom() {
        return height - 36;
    }

    /** Moves the rows' controls to where the scroll position puts them; those outside the list are hidden. */
    private void place() {
        int room = listBottom() - LIST_TOP;
        scroll = Math.max(0, Math.min(scroll, rows.size() * ROW - room));
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = LIST_TOP + i * ROW - scroll + 2;
            boolean inView = y >= LIST_TOP && y + 20 <= listBottom();
            if (row.button != null) {
                row.button.y = y;
                row.button.visible = inView;
            } else {
                row.box.y = y + 1;
                row.box.setVisible(inView);
                if (!inView) {
                    row.box.setFocused(false);
                }
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll += wheel > 0 ? -ROW : ROW;
            place();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            keep();
            mc.displayGuiScreen(parent);
        } else if (button.id == 2) {
            mc.displayGuiScreen(parent);
        } else {
            for (Row row : rows) {
                if (row.button == button) {
                    values.put(row.rule, "true".equals(values.get(row.rule)) ? "false" : "true");
                    button.displayString = onOff(row.rule);
                }
            }
        }
    }

    /** Remembers every rule that differs from the game's default. */
    private void keep() {
        CHOSEN.clear();
        for (Row row : rows) {
            String value = values.get(row.rule);
            if (row.box != null) {
                try {
                    value = String.valueOf(Integer.parseInt(row.box.getText().trim()));
                } catch (NumberFormatException e) {
                    value = defaults.getString(row.rule);
                }
            }
            if (!value.equals(defaults.getString(row.rule))) {
                CHOSEN.put(row.rule, value);
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        for (Row row : rows) {
            if (row.box != null) {
                row.box.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    @Override
    protected void keyTyped(char typed, int key) throws IOException {
        for (Row row : rows) {
            if (row.box != null && row.box.isFocused()) {
                if (Character.isDigit(typed) || typed == '-' || typed < ' ' || key >= 200) {
                    row.box.textboxKeyTyped(typed, key);
                }
                if (key != 1) {
                    return;
                }
            }
        }
        if (key == 1) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void updateScreen() {
        for (Row row : rows) {
            if (row.box != null) {
                row.box.updateCursorCounter();
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, I18n.format("better112.gamerules.title"), width / 2, 14, 0xFFFFFF);
        String hovered = null;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = LIST_TOP + i * ROW - scroll + 2;
            if (y < LIST_TOP || y + 20 > listBottom()) {
                continue;
            }
            boolean changed = !values.get(row.rule).equals(defaults.getString(row.rule))
                    || (row.box != null && !row.box.getText().trim().equals(defaults.getString(row.rule)));
            drawString(fontRenderer, title(row.rule), width / 2 - 155, y + 6, changed ? 0xFFFFFF55 : 0xFFFFFFFF);
            if (row.box != null) {
                row.box.drawTextBox();
            }
            if (mouseX >= width / 2 - 155 && mouseX < width / 2 + 50 && mouseY >= y && mouseY < y + 20) {
                hovered = row.rule;
            }
        }
        // A thumb at the right edge when the list is longer than the screen.
        int room = listBottom() - LIST_TOP, all = rows.size() * ROW;
        if (all > room) {
            int thumb = Math.max(16, room * room / all);
            int at = LIST_TOP + (room - thumb) * scroll / (all - room);
            drawRect(width / 2 + 162, LIST_TOP, width / 2 + 165, listBottom(), 0x40FFFFFF);
            drawRect(width / 2 + 162, at, width / 2 + 165, at + thumb, 0xFFAAAAAA);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hovered != null) {
            drawHoveringText(hovered, mouseX, mouseY);
        }
    }

    /** Self-test: change one rule as the screen would and report what is waiting for the new world. */
    public static String testChoose(String rule, String value) {
        CHOSEN.put(rule, value);
        armed = true;
        return CHOSEN.toString();
    }
}
