package better112.hud;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;

/**
 * What an Applied Energistics 2 storage cell holds, listed in its tooltip, as modern AE2
 * does. On 1.12.2 the tooltip only says how many bytes and types are used, so finding the
 * cell with the diamonds in it means putting each one in a drive.
 *
 * AE2 is not needed to compile or run this: a cell keeps its contents in the item's own
 * data ("#0", "#1", ... for what, "@0", "@1", ... for how many), which the client already
 * has. Anything that does not look like that is left alone.
 */
final class CellContents {

    private CellContents() {}

    private static final int SHORT_LIST = 5;
    private static final int LONG_LIST = 24;
    /** AE2's cells stop at 63 kinds of item. */
    private static final int MAX_TYPES = 63;

    private static final class Entry {
        String name;
        long count;
        boolean fluid;
    }

    static void append(ItemStack stack, List<String> tooltip, boolean all) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey("#0", 10) || !tag.hasKey("it", 99)) {
            return;
        }
        List<Entry> entries = new ArrayList<Entry>();
        int types = Math.min(MAX_TYPES, tag.getShort("it"));
        for (int i = 0; i < types; i++) {
            if (!tag.hasKey("#" + i, 10)) {
                break;
            }
            Entry entry = read(tag.getCompoundTag("#" + i), tag.hasKey("@" + i, 99) ? tag.getLong("@" + i) : -1L);
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        // The Extended Life fork of AE2 already lists a cell's contents; saying it twice helps nobody.
        // (Name and amount on one line; the name alone can also be a partition entry.)
        String first = entries.get(0).name, firstAmount = Long.toString(entries.get(0).count);
        for (String line : tooltip) {
            if (line != null && line.contains(first) && line.replaceAll("[^0-9]", "").contains(firstAmount)) {
                return;
            }
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.count, a.count);
            }
        });
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.getDefault());
        int limit = all ? LONG_LIST : SHORT_LIST;
        for (int i = 0; i < entries.size() && i < limit; i++) {
            Entry entry = entries.get(i);
            String amount = entry.fluid ? I18n.format("better112.tooltip.cell_fluid", format.format(entry.count))
                    : format.format(entry.count);
            tooltip.add(TextFormatting.GRAY + " " + amount + " " + TextFormatting.WHITE + entry.name);
        }
        if (entries.size() > limit) {
            tooltip.add(TextFormatting.DARK_GRAY + " " + I18n.format(all ? "better112.tooltip.cell_more" : "better112.tooltip.cell_more_shift",
                    entries.size() - limit));
        }
    }

    private static Entry read(NBTTagCompound data, long count) {
        try {
            Entry entry = new Entry();
            if (data.hasKey("FluidName", 8)) {
                FluidStack fluid = FluidStack.loadFluidStackFromNBT(amountOf(data, "Amount", 1));
                if (fluid == null) {
                    return null;
                }
                entry.name = fluid.getLocalizedName();
                entry.fluid = true;
            } else {
                ItemStack item = new ItemStack(amountOf(data, "Count", 1));
                if (item.isEmpty()) {
                    return null;
                }
                entry.name = item.getDisplayName();
            }
            entry.count = count >= 0 ? count : data.getLong("Cnt");
            return entry;
        } catch (Throwable t) {
            return null; // an item from a removed mod, or a format this does not know
        }
    }

    /** A copy with the amount set, since AE2 keeps the real amount elsewhere and may store zero here. */
    private static NBTTagCompound amountOf(NBTTagCompound data, String key, int amount) {
        NBTTagCompound copy = data.copy();
        if (key.equals("Count")) {
            copy.setByte(key, (byte) amount);
        } else {
            copy.setInteger(key, amount);
        }
        return copy;
    }
}
