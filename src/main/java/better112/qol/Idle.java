package better112.qol;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Notices when nobody is at the keyboard, as Dynamic FPS does, so the frame rate can drop while a pack's
 * machines run on unattended. The game's ticks are untouched: only drawing slows down.
 */
public final class Idle {

    private static long lastActivity = System.currentTimeMillis();
    private static float lastYaw, lastPitch;
    /** Self-test: pretend this many milliseconds have passed without input. */
    public static long testOffset;

    public static boolean isIdle() {
        int minutes = BetterConfig.idleMinutes;
        return BetterConfig.idleFps > 0 && minutes > 0 && System.currentTimeMillis() + testOffset - lastActivity >= minutes * 60000L;
    }

    /** The self-test keeps the game awake between its steps (its own offset still applies). */
    public static void keepAwake() {
        lastActivity = System.currentTimeMillis();
    }

    public static void active() {
        lastActivity = System.currentTimeMillis();
        testOffset = 0L;
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        active();
    }

    @SubscribeEvent
    public void onMouse(InputEvent.MouseInputEvent event) {
        active();
    }

    @SubscribeEvent
    public void onGuiKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        active();
    }

    @SubscribeEvent
    public void onGuiMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        active();
    }

    /** Looking around or being moved also counts: the mouse turning the view sends no input events. */
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END || mc.player == null) {
            return;
        }
        if (mc.player.rotationYaw != lastYaw || mc.player.rotationPitch != lastPitch) {
            active();
        }
        lastYaw = mc.player.rotationYaw;
        lastPitch = mc.player.rotationPitch;
    }

    public static String testState() {
        return (isIdle() ? "idle" : "active") + ", " + (System.currentTimeMillis() + testOffset - lastActivity) / 1000L + " s since input, cap "
                + BetterConfig.idleFps + " FPS after " + BetterConfig.idleMinutes + " min";
    }
}
