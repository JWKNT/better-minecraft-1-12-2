package better112.qol;

import java.util.Locale;

import better112.config.BetterConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

/**
 * F3 key combinations the game gained after 1.12: F3+F4 opens the game mode picker, F3+C copies where
 * you stand as a teleport command, F3+I copies the block or entity you look at as a command.
 */
public final class DebugKeys {

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!BetterConfig.debugKeys || !Keyboard.getEventKeyState() || !Keyboard.isKeyDown(Keyboard.KEY_F3)
                || mc.player == null || mc.currentScreen != null) {
            return;
        }
        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_Q) {
            // The game has just listed its F3 keys in chat; the newer ones follow.
            for (String line : new String[] {"better112.debug.help.f4", "better112.debug.help.c", "better112.debug.help.i"}) {
                mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.text.TextComponentTranslation(line));
            }
            return;
        }
        if (key == Keyboard.KEY_F4) {
            GameModeSwitcher.open(mc);
        } else if (key == Keyboard.KEY_C) {
            GuiScreen.setClipboardString(locationCommand(mc));
            say(mc, I18n.format("better112.debug.copy_location"));
        } else if (key == Keyboard.KEY_I) {
            String[] what = targetCommand(mc);
            if (what != null) {
                GuiScreen.setClipboardString(what[1]);
                say(mc, I18n.format(what[0]));
            }
        } else {
            return;
        }
        // Letting go of F3 after one of these must not also flip the debug screen.
        try {
            ReflectionHelper.setPrivateValue(Minecraft.class, mc, Boolean.TRUE, "actionKeyF3", "field_184129_aV");
        } catch (Throwable t) {
            // the debug screen toggles; nothing worse
        }
    }

    /** A /tp command for the player's position and view. */
    public static String locationCommand(Minecraft mc) {
        return String.format(Locale.ROOT, "/tp @p %.2f %.2f %.2f %.2f %.2f",
                mc.player.posX, mc.player.posY, mc.player.posZ, mc.player.rotationYaw % 360f, mc.player.rotationPitch);
    }

    /** A message key and a /setblock or /summon command for what the player looks at; null if nothing is in reach. */
    public static String[] targetCommand(Minecraft mc) {
        RayTraceResult hit = mc.objectMouseOver;
        if (hit == null || mc.world == null) {
            return null;
        }
        if (hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            IBlockState state = mc.world.getBlockState(pos);
            StringBuilder command = new StringBuilder("/setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " "
                    + state.getBlock().getRegistryName() + " " + state.getBlock().getMetaFromState(state));
            TileEntity tile = mc.world.getTileEntity(pos);
            if (tile != null) {
                try {
                    NBTTagCompound tag = tile.writeToNBT(new NBTTagCompound());
                    tag.removeTag("x");
                    tag.removeTag("y");
                    tag.removeTag("z");
                    tag.removeTag("id");
                    if (!tag.isEmpty()) {
                        command.append(" replace ").append(tag);
                    }
                } catch (Throwable t) {
                    // a block entity that cannot be written on the client is copied without its data
                }
            }
            return new String[] {"better112.debug.copy_block", command.toString()};
        }
        if (hit.typeOfHit == RayTraceResult.Type.ENTITY && hit.entityHit != null) {
            ResourceLocation id = EntityList.getKey(hit.entityHit);
            if (id == null) {
                return null;
            }
            return new String[] {"better112.debug.copy_entity", String.format(Locale.ROOT, "/summon %s %.2f %.2f %.2f", id,
                    hit.entityHit.posX, hit.entityHit.posY, hit.entityHit.posZ)};
        }
        return null;
    }

    /** A line in chat the way the game reports its own F3 actions. */
    static void say(Minecraft mc, String text) {
        ITextComponent prefix = new TextComponentTranslation("debug.prefix");
        prefix.getStyle().setColor(TextFormatting.YELLOW).setBold(true);
        mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString("").appendSibling(prefix).appendText(" " + text));
    }
}
