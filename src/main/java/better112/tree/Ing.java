package better112.tree;

import java.util.function.Function;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

/** An ingredient of a recipe: an item or a fluid, with the key recipes are matched by. */
public final class Ing {

    /** Extra identity for items that differ only by NBT; JEI supplies its subtype info here. */
    public static volatile Function<ItemStack, String> subtype = stack -> null;

    public final ItemStack item;
    public final FluidStack fluid;
    public final String key;

    private Ing(ItemStack item, FluidStack fluid, String key) {
        this.item = item;
        this.fluid = fluid;
        this.key = key;
    }

    public static Ing of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ItemStack one = stack.copy();
        one.setCount(1);
        ResourceLocation name = one.getItem().getRegistryName();
        StringBuilder key = new StringBuilder(name == null ? one.getItem().getClass().getName() : name.toString());
        String sub = null;
        try {
            sub = subtype.apply(one);
        } catch (Throwable ignored) {
            // a broken subtype interpreter should not break the tree
        }
        if (sub != null && !sub.isEmpty()) {
            key.append('#').append(sub);
        } else if (one.getHasSubtypes()) {
            key.append('@').append(one.getMetadata());
        }
        return new Ing(one, null, key.toString());
    }

    public static Ing of(FluidStack stack) {
        if (stack == null || stack.getFluid() == null) {
            return null;
        }
        FluidStack one = stack.copy();
        one.amount = 1000;
        return new Ing(ItemStack.EMPTY, one, "fluid:" + one.getFluid().getName());
    }

    public boolean isFluid() {
        return fluid != null;
    }

    public String name() {
        try {
            return isFluid() ? fluid.getLocalizedName() : item.getDisplayName();
        } catch (Throwable t) {
            return key;
        }
    }
}
