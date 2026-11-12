package better112.qol;

import better112.config.BetterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.MouseHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Freelook, as modern packs get it from the Freelook and Perspective mods: while the key is held the camera
 * swings around the player in third person and the mouse turns the camera, not the player, who keeps walking
 * and aiming the way they faced. Letting go puts the view back as it was.
 */
public final class Freelook {

    public static final KeyBinding KEY = new KeyBinding("better112.key.freelook", Keyboard.KEY_F4, "better112.key.category");

    private static boolean active;
    private static int viewBefore;
    private static float yaw, pitch;
    private static MouseHelper mouseBefore;
    /** Self-test: hold the key without a keyboard. */
    public static boolean testHeld;

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    public static boolean isActive() {
        return active;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean want = BetterConfig.freelook && mc.world != null && mc.player != null && mc.getRenderViewEntity() == mc.player
                && (testHeld || mc.currentScreen == null && KEY.isKeyDown());
        if (want && !active) {
            start(mc);
        } else if (!want && active) {
            stop(mc);
        }
    }

    private static void start(Minecraft mc) {
        active = true;
        viewBefore = mc.gameSettings.thirdPersonView;
        mc.gameSettings.thirdPersonView = 1;
        yaw = mc.player.rotationYaw;
        pitch = mc.player.rotationPitch;
        // The mouse's movement is taken before the game turns the player with it.
        mouseBefore = mc.mouseHelper;
        mc.mouseHelper = new Mouse(mouseBefore);
    }

    private static void stop(Minecraft mc) {
        active = false;
        mc.gameSettings.thirdPersonView = viewBefore;
        if (mc.mouseHelper instanceof Mouse) {
            mc.mouseHelper = mouseBefore;
        }
        mouseBefore = null;
    }

    /** Turns the camera the way the game would have turned the player (EntityRenderer's sensitivity curve). */
    static void turn(int dx, int dy) {
        Minecraft mc = Minecraft.getMinecraft();
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float scale = f * f * f * 8.0F;
        yaw += dx * scale * 0.15F;
        pitch -= dy * scale * 0.15F * (mc.gameSettings.invertMouse ? -1 : 1);
        pitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
    }

    /** Self-test: swing the camera by this much. */
    public static String testTurn(float dYaw, float dPitch) {
        yaw += dYaw;
        pitch = MathHelper.clamp(pitch + dPitch, -90.0F, 90.0F);
        Minecraft mc = Minecraft.getMinecraft();
        return "freelook " + (active ? "on" : "off") + ", camera " + Math.round(yaw) + "/" + Math.round(pitch) + ", player faces "
                + Math.round(mc.player.rotationYaw) + "/" + Math.round(mc.player.rotationPitch) + ", view " + mc.gameSettings.thirdPersonView;
    }

    @SubscribeEvent
    public void onCamera(EntityViewRenderEvent.CameraSetup event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!active || event.getEntity() != mc.player || mc.gameSettings.thirdPersonView != 1) {
            return;
        }
        Entity entity = event.getEntity();
        float partial = (float) event.getRenderPartialTicks();
        // The game has already pulled the camera back from the eyes, but by how far the wall behind the player
        // allows. Measure the wall behind the camera instead and move the camera to that distance.
        double wrong = distance(mc, entity, entity.rotationYaw, entity.rotationPitch, partial);
        double right = distance(mc, entity, yaw, pitch, partial);
        GlStateManager.translate(0.0F, 0.0F, (float) (wrong - right));
        event.setYaw(yaw + 180.0F);
        event.setPitch(pitch);
        event.setRoll(0.0F);
    }

    /** How far behind the eyes the camera can sit looking this way: EntityRenderer.orientCamera's measurement. */
    private static double distance(Minecraft mc, Entity entity, float yaw, float pitch, float partial) {
        double x = entity.prevPosX + (entity.posX - entity.prevPosX) * partial;
        double y = entity.prevPosY + (entity.posY - entity.prevPosY) * partial + entity.getEyeHeight();
        double z = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partial;
        double d = 4.0;
        double dx = -MathHelper.sin(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F) * d;
        double dz = MathHelper.cos(yaw * 0.017453292F) * MathHelper.cos(pitch * 0.017453292F) * d;
        double dy = -MathHelper.sin(pitch * 0.017453292F) * d;
        for (int i = 0; i < 8; ++i) {
            float ox = ((i & 1) * 2 - 1) * 0.1F, oy = ((i >> 1 & 1) * 2 - 1) * 0.1F, oz = ((i >> 2 & 1) * 2 - 1) * 0.1F;
            RayTraceResult hit = mc.world.rayTraceBlocks(new Vec3d(x + ox, y + oy, z + oz), new Vec3d(x - dx + ox + oz, y - dy + oy, z - dz + oz),
                    false, passThroughPlants(), false);
            if (hit != null) {
                double away = hit.hitVec.distanceTo(new Vec3d(x, y, z));
                if (away < d) {
                    d = away;
                }
            }
        }
        return d;
    }

    private static Boolean passThroughPlants;

    /**
     * Universal Tweaks can make the third-person camera look past grass and flowers; the camera's distance is
     * measured the same way here, or freelook would pull it in front of plants the normal view looks past.
     */
    private static boolean passThroughPlants() {
        if (passThroughPlants == null) {
            boolean on = false;
            if (net.minecraftforge.fml.common.Loader.isModLoaded("universaltweaks")) {
                java.io.File config = new java.io.File(Minecraft.getMinecraft().gameDir, "config/Universal Tweaks - Tweaks.cfg");
                try {
                    for (String line : java.nio.file.Files.readAllLines(config.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                        if (line.contains("Third Person Ignores Non-solid Blocks")) {
                            on = line.trim().endsWith("=true");
                        }
                    }
                } catch (Exception e) {
                    // no config yet: the mod's default is off
                }
            }
            passThroughPlants = on;
        }
        return passThroughPlants;
    }

    /** Hands the game no mouse movement while freelook is on, and turns the camera with it instead. */
    private static final class Mouse extends MouseHelper {
        private final MouseHelper wrapped;

        Mouse(MouseHelper wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public void grabMouseCursor() {
            wrapped.grabMouseCursor();
        }

        @Override
        public void ungrabMouseCursor() {
            wrapped.ungrabMouseCursor();
        }

        @Override
        public void mouseXYChange() {
            wrapped.mouseXYChange();
            if (active) {
                turn(wrapped.deltaX, wrapped.deltaY);
                deltaX = 0;
                deltaY = 0;
            } else {
                deltaX = wrapped.deltaX;
                deltaY = wrapped.deltaY;
            }
        }
    }
}
