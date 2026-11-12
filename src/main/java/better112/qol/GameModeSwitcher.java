package better112.qol;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.GameType;
import org.lwjgl.input.Keyboard;

/** The F3+F4 game mode picker of 1.16: hold F3, tap F4 to step through the modes, let go of F3 to switch. */
public final class GameModeSwitcher extends GuiScreen {

    private static final GameType[] MODES = {GameType.CREATIVE, GameType.SURVIVAL, GameType.ADVENTURE, GameType.SPECTATOR};
    private static final ItemStack[] ICONS = {
        new ItemStack(Blocks.GRASS), new ItemStack(Items.IRON_SWORD), new ItemStack(Items.MAP), new ItemStack(Items.ENDER_EYE)};
    private static final int BOX = 26, GAP = 5;

    /** The mode switched away from last, offered first the next time. */
    private static GameType previous;
    /** Self-test: stay open although F3 is not held. */
    public static boolean testHold;

    private final GameType current;
    private int selected;

    private GameModeSwitcher(GameType current) {
        this.current = current;
        GameType offer = previous != null && previous != current ? previous
                : current == GameType.CREATIVE ? GameType.SURVIVAL : GameType.CREATIVE;
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i] == offer) {
                selected = i;
            }
        }
    }

    static void open(Minecraft mc) {
        if (mc.player.getPermissionLevel() < 2) {
            DebugKeys.say(mc, I18n.format("better112.debug.no_permission"));
            return;
        }
        mc.displayGuiScreen(new GameModeSwitcher(mc.playerController.getCurrentGameType()));
    }

    /** Self-test: open it and tap F4 that many times. */
    public static String testOpen(int taps) {
        Minecraft mc = Minecraft.getMinecraft();
        testHold = true;
        GameModeSwitcher screen = new GameModeSwitcher(mc.playerController.getCurrentGameType());
        screen.selected = (screen.selected + taps) % MODES.length;
        mc.displayGuiScreen(screen);
        return "from " + screen.current.getName() + ", picked " + MODES[screen.selected].getName()
                + ", permission level " + mc.player.getPermissionLevel();
    }

    /** Self-test: let go of F3. */
    public static void testRelease() {
        Minecraft mc = Minecraft.getMinecraft();
        testHold = false;
        if (mc.currentScreen instanceof GameModeSwitcher) {
            ((GameModeSwitcher) mc.currentScreen).choose();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        if (!testHold && !Keyboard.isKeyDown(Keyboard.KEY_F3)) {
            choose();
        }
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_F4) {
            selected = (selected + 1) % MODES.length;
        } else if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
        }
    }

    private void choose() {
        GameType pick = MODES[selected];
        if (pick != current && mc.player != null) {
            previous = current;
            mc.player.sendChatMessage("/gamemode " + pick.getName());
        }
        mc.displayGuiScreen(null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int span = MODES.length * BOX + (MODES.length - 1) * GAP;
        int left = (width - span) / 2, boxTop = height / 2 - 31 + 26;
        drawRect(left - 10, boxTop - 26, left + span + 10, boxTop + BOX + 24, 0xC8101014);
        drawCenteredString(fontRenderer, I18n.format("gameMode." + MODES[selected].getName()), width / 2, boxTop - 17, 0xFFFFFFFF);
        for (int i = 0; i < MODES.length; i++) {
            int x = left + i * (BOX + GAP);
            if (mouseX >= x && mouseX < x + BOX && mouseY >= boxTop && mouseY < boxTop + BOX && (mouseX != lastX || mouseY != lastY)) {
                selected = i; // the mouse picks too, once it moves
            }
            drawRect(x, boxTop, x + BOX, boxTop + BOX, i == selected ? 0xFFFFFFFF : 0xFF3A3A44);
            drawRect(x + 1, boxTop + 1, x + BOX - 1, boxTop + BOX - 1, i == selected ? 0xFF4A4A56 : 0xFF1B1B21);
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableDepth();
            itemRender.renderItemAndEffectIntoGUI(ICONS[i], x + 5, boxTop + 5);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableDepth();
        }
        lastX = mouseX;
        lastY = mouseY;
        drawCenteredString(fontRenderer, I18n.format("better112.switcher.next"), width / 2, boxTop + BOX + 8, 0xFFAAAAAA);
    }

    private int lastX = -1, lastY = -1;
}
