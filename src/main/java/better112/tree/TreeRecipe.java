package better112.tree;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

/** One way to make something, reduced to what a crafting plan needs. */
public final class TreeRecipe {

    public static final class Input {
        /** Interchangeable choices for this ingredient (an ore dictionary entry, usually). */
        public final List<Ing> options = new ArrayList<Ing>();
        /** Items, or millibuckets for a fluid. */
        public long count;
    }

    public static final class Output {
        public Ing ing;
        public long count;
    }

    public final String categoryId;
    public final String categoryTitle;
    /** The machine or table the recipe is made in; may be empty. */
    public final ItemStack catalyst;
    public final List<Input> inputs = new ArrayList<Input>();
    public final List<Output> outputs = new ArrayList<Output>();
    private String signature;

    public TreeRecipe(String categoryId, String categoryTitle, ItemStack catalyst) {
        this.categoryId = categoryId;
        this.categoryTitle = categoryTitle;
        this.catalyst = catalyst == null ? ItemStack.EMPTY : catalyst;
    }

    public void addInput(List<Ing> options, long count) {
        if (options.isEmpty() || count <= 0) {
            return;
        }
        for (Input existing : inputs) {
            if (sameOptions(existing.options, options)) {
                existing.count += count;
                return;
            }
        }
        Input input = new Input();
        input.options.addAll(options);
        input.count = count;
        inputs.add(input);
    }

    public void addOutput(Ing ing, long count) {
        if (ing == null || count <= 0) {
            return;
        }
        for (Output existing : outputs) {
            if (existing.ing.key.equals(ing.key)) {
                existing.count += count;
                return;
            }
        }
        Output output = new Output();
        output.ing = ing;
        output.count = count;
        outputs.add(output);
    }

    private static boolean sameOptions(List<Ing> a, List<Ing> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).key.equals(b.get(i).key)) {
                return false;
            }
        }
        return true;
    }

    /** How many of the given ingredient one run of the recipe makes (at least 1). */
    public long outputCount(String key) {
        for (Output output : outputs) {
            if (output.ing.key.equals(key)) {
                return Math.max(1L, output.count);
            }
        }
        return 1L;
    }

    public boolean uses(String key) {
        for (Input input : inputs) {
            for (Ing option : input.options) {
                if (option.key.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Stable identity used to remember which recipe the player prefers for an item. */
    public String signature() {
        if (signature == null) {
            List<String> parts = new ArrayList<String>();
            for (Input input : inputs) {
                parts.add(input.count + "x" + input.options.get(0).key);
            }
            java.util.Collections.sort(parts);
            signature = categoryId + "|" + String.join(",", parts);
        }
        return signature;
    }
}
