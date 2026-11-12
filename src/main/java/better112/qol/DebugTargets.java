package better112.qol;

import java.util.ArrayList;
import java.util.List;

import better112.config.BetterConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.oredict.OreDictionary;

/**
 * The newer F3 lines: 1.12 already names the block you look at and its properties, and later versions add its
 * tags, the fluid you look at (1.13) and the entity you look at (1.14). Here the block's ore names stand in for
 * tags, which is how 1.12 packs group blocks.
 */
public final class DebugTargets {

    @SubscribeEvent
    public void onText(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!BetterConfig.debugTargets || !mc.gameSettings.showDebugInfo || mc.world == null || mc.getRenderViewEntity() == null) {
            return;
        }
        event.getRight().addAll(lines(mc, event.getPartialTicks()));
    }

    static List<String> lines(Minecraft mc, float partialTicks) {
        List<String> lines = new ArrayList<String>();
        RayTraceResult hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            IBlockState state = mc.world.getBlockState(hit.getBlockPos());
            ItemStack item = state.getBlock().getPickBlock(state, hit, mc.world, hit.getBlockPos(), mc.player);
            List<String> ores = new ArrayList<String>();
            if (!item.isEmpty()) {
                for (int id : OreDictionary.getOreIDs(item)) {
                    ores.add("#" + OreDictionary.getOreName(id));
                }
            }
            for (String ore : ores.subList(0, Math.min(6, ores.size()))) {
                lines.add(ore);
            }
            if (ores.size() > 6) {
                lines.add("... " + (ores.size() - 6) + " more ore names");
            }
        }
        // The fluid in front of you, up to 20 blocks, as since 1.13.
        Entity eye = mc.getRenderViewEntity();
        Vec3d from = eye.getPositionEyes(partialTicks);
        Vec3d to = from.add(eye.getLook(partialTicks).scale(20.0));
        RayTraceResult fluidHit = mc.world.rayTraceBlocks(from, to, true, false, false);
        if (fluidHit != null && fluidHit.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos pos = fluidHit.getBlockPos();
            IBlockState state = mc.world.getBlockState(pos);
            Fluid fluid = state.getBlock() instanceof IFluidBlock ? ((IFluidBlock) state.getBlock()).getFluid() : FluidRegistry.lookupFluidForBlock(state.getBlock());
            if (fluid != null) {
                lines.add("");
                lines.add(TextFormatting.UNDERLINE + "Targeted Fluid: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                lines.add(fluid.getName() + (fluid.isGaseous() ? " (gas)" : ""));
                lines.add("temperature: " + fluid.getTemperature() + " K");
            }
        }
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.ENTITY && hit.entityHit != null) {
            Entity target = hit.entityHit;
            ResourceLocation type = EntityList.getKey(target);
            lines.add("");
            lines.add(TextFormatting.UNDERLINE + "Targeted Entity");
            lines.add(type != null ? type.toString() : target.getClass().getSimpleName());
            if (target instanceof net.minecraft.entity.EntityLivingBase) {
                net.minecraft.entity.EntityLivingBase living = (net.minecraft.entity.EntityLivingBase) target;
                lines.add("health: " + Math.round(living.getHealth() * 10) / 10.0 + " / " + Math.round(living.getMaxHealth() * 10) / 10.0);
            }
        }
        return lines;
    }

    /** Self-test: the extra lines F3 would show now. */
    public static String testLines() {
        return String.join(" | ", lines(Minecraft.getMinecraft(), 1.0F));
    }
}
