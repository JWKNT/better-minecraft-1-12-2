package better112.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A crafting plan: a goal, how many of it, and the recipe chosen for each item on the way
 * down. Nothing is expanded unless a recipe has been chosen for it, now or in an earlier
 * tree; in an expert pack most items have dozens of recipes and guessing all the way down
 * produces nonsense.
 */
public final class RecipeTree {

    /** A place in the drawn tree. The same item can appear in several places. */
    public static final class Node {
        public Ing ing;
        public long amount;
        /** Null for a raw material. */
        public TreeRecipe recipe;
        public long crafts;
        public boolean cycle;
        public boolean truncated;
        public Node parent;
        public final List<Node> children = new ArrayList<Node>();
        // Layout, filled in by the screen.
        public int x, y, width;
    }

    public static final class Line {
        public Ing ing;
        public long amount;
        public TreeRecipe recipe;
        public long crafts;
    }

    /** The whole plan added up, with shared surplus counted once. */
    public static final class Totals {
        public final List<Line> raw = new ArrayList<Line>();
        public final List<Line> leftover = new ArrayList<Line>();
        /** Crafting steps in the order to do them. */
        public final List<Line> steps = new ArrayList<Line>();
    }

    private static final int MAX_DEPTH = 24;
    private static final int MAX_NODES = 600;

    public final RecipeSource source;
    private Ing root;
    private long amount = 1;
    /** Choices made in this tree. A key mapped to null is forced raw. */
    private final Map<String, TreeRecipe> choices = new HashMap<String, TreeRecipe>();
    private final Map<String, TreeRecipe> remembered = new HashMap<String, TreeRecipe>();
    private int nodeCount;

    public RecipeTree(RecipeSource source) {
        this.source = source;
    }

    public Ing root() {
        return root;
    }

    public long amount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = Math.max(1L, Math.min(1000000L, amount));
    }

    public void setRoot(Ing ing) {
        root = ing;
        amount = 1;
        choices.clear();
        remembered.clear();
        if (ing != null && recipeOf(ing) == null && !RecipeDefaults.has(ing.key)) {
            TreeRecipe first = autoPick(ing, new HashSet<String>());
            if (first != null) {
                choices.put(ing.key, first);
            }
        }
    }

    /** Picks a recipe (or null for "raw material") and remembers it for later trees. */
    public void choose(Ing ing, TreeRecipe recipe) {
        choices.put(ing.key, recipe);
        RecipeDefaults.set(ing.key, recipe);
    }

    public TreeRecipe recipeOf(Ing ing) {
        if (choices.containsKey(ing.key)) {
            return choices.get(ing.key);
        }
        if (remembered.containsKey(ing.key)) {
            return remembered.get(ing.key);
        }
        TreeRecipe found = RecipeDefaults.has(ing.key) ? RecipeDefaults.find(ing.key, source.recipesFor(ing)) : null;
        remembered.put(ing.key, found);
        return found;
    }

    /** The first recipe that neither eats its own product nor just repacks or recycles something. */
    private TreeRecipe autoPick(Ing ing, Set<String> ancestors) {
        List<TreeRecipe> all = source.recipesFor(ing);
        TreeRecipe fallback = null;
        for (TreeRecipe recipe : all) {
            if (recipe.inputs.isEmpty() || recipe.uses(ing.key)) {
                continue;
            }
            boolean loops = false;
            for (String ancestor : ancestors) {
                if (recipe.uses(ancestor)) {
                    loops = true;
                    break;
                }
            }
            if (loops) {
                continue;
            }
            if (Materials.isRoundabout(recipe, ing.key)) {
                if (fallback == null) {
                    fallback = recipe;
                }
                continue;
            }
            return recipe;
        }
        return fallback;
    }

    /** Fills in a guess for every raw material under the node, a few levels deep. */
    public void expandAll(Node node) {
        Set<String> ancestors = new HashSet<String>();
        for (Node n = node.parent; n != null; n = n.parent) {
            ancestors.add(n.ing.key);
        }
        expand(node.ing, ancestors, 0, new int[] {0});
    }

    private void expand(Ing ing, Set<String> ancestors, int depth, int[] budget) {
        if (depth > 8 || budget[0] > 150 || ancestors.contains(ing.key)) {
            return;
        }
        TreeRecipe recipe = recipeOf(ing);
        if (recipe == null) {
            if (choices.containsKey(ing.key) || (depth > 0 && Materials.isBase(ing))) {
                return; // the player said this one is raw, or it is an ingot, dust or gem
            }
            recipe = autoPick(ing, ancestors);
            if (recipe == null) {
                return;
            }
            choices.put(ing.key, recipe);
            budget[0]++;
        }
        ancestors.add(ing.key);
        for (TreeRecipe.Input input : recipe.inputs) {
            expand(pick(input), ancestors, depth + 1, budget);
        }
        ancestors.remove(ing.key);
    }

    /** Of the interchangeable options, prefer one that already has a recipe chosen. */
    public Ing pick(TreeRecipe.Input input) {
        for (Ing option : input.options) {
            if (choices.get(option.key) != null) {
                return option;
            }
        }
        for (Ing option : input.options) {
            if (RecipeDefaults.has(option.key)) {
                return option;
            }
        }
        // "Any cobblestone" should show the cobblestone the player actually has.
        for (Ing option : input.options) {
            if (owned.contains(option.key)) {
                return option;
            }
        }
        return input.options.get(0);
    }

    /** Keys of what the player carries or has in an open ME network; set by the screen. */
    public java.util.Set<String> owned = java.util.Collections.emptySet();

    // ------------------------------------------------------------------------------------
    // The drawn tree
    // ------------------------------------------------------------------------------------

    public Node build() {
        if (root == null) {
            return null;
        }
        nodeCount = 0;
        return build(root, amount, null, new HashSet<String>(), 0);
    }

    private Node build(Ing ing, long amount, Node parent, Set<String> ancestors, int depth) {
        Node node = new Node();
        node.ing = ing;
        node.amount = amount;
        node.parent = parent;
        nodeCount++;
        if (ancestors.contains(ing.key)) {
            node.cycle = true;
            return node;
        }
        TreeRecipe recipe = recipeOf(ing);
        if (recipe == null) {
            return node;
        }
        if (depth >= MAX_DEPTH || nodeCount >= MAX_NODES) {
            node.truncated = true;
            return node;
        }
        node.recipe = recipe;
        node.crafts = ceilDiv(amount, recipe.outputCount(ing.key));
        ancestors.add(ing.key);
        for (TreeRecipe.Input input : recipe.inputs) {
            node.children.add(build(pick(input), input.count * node.crafts, node, ancestors, depth + 1));
        }
        ancestors.remove(ing.key);
        return node;
    }

    // ------------------------------------------------------------------------------------
    // Totals
    // ------------------------------------------------------------------------------------

    public Totals totals() {
        Totals totals = new Totals();
        if (root == null) {
            return totals;
        }
        // Order items so each comes after everything that needs it.
        List<Ing> order = new ArrayList<Ing>();
        Set<String> done = new HashSet<String>();
        Set<String> onPath = new HashSet<String>();
        Set<String> backEdges = new HashSet<String>();
        visit(root, done, onPath, backEdges, order);
        java.util.Collections.reverse(order);

        Map<String, Long> demand = new HashMap<String, Long>();
        Map<String, Line> raw = new LinkedHashMap<String, Line>();
        Map<String, Line> leftover = new LinkedHashMap<String, Line>();
        demand.put(root.key, amount);
        for (Ing ing : order) {
            long need = demand.containsKey(ing.key) ? demand.get(ing.key) : 0L;
            if (need <= 0) {
                continue;
            }
            TreeRecipe recipe = recipeOf(ing);
            if (recipe == null) {
                add(raw, ing, need);
                continue;
            }
            long per = recipe.outputCount(ing.key);
            long crafts = ceilDiv(need, per);
            Line step = new Line();
            step.ing = ing;
            step.amount = crafts * per;
            step.recipe = recipe;
            step.crafts = crafts;
            totals.steps.add(step);
            if (crafts * per > need) {
                add(leftover, ing, crafts * per - need);
            }
            for (TreeRecipe.Output output : recipe.outputs) {
                if (!output.ing.key.equals(ing.key)) {
                    add(leftover, output.ing, output.count * crafts);
                }
            }
            for (TreeRecipe.Input input : recipe.inputs) {
                Ing child = pick(input);
                long count = input.count * crafts;
                if (backEdges.contains(ing.key + ">" + child.key)) {
                    add(raw, child, count);
                } else {
                    Long old = demand.get(child.key);
                    demand.put(child.key, (old == null ? 0L : old) + count);
                }
            }
        }
        java.util.Collections.reverse(totals.steps);
        totals.raw.addAll(raw.values());
        totals.leftover.addAll(leftover.values());
        java.util.Collections.sort(totals.raw, (a, b) -> Long.compare(b.amount, a.amount));
        return totals;
    }

    private void visit(Ing ing, Set<String> done, Set<String> onPath, Set<String> backEdges, List<Ing> order) {
        if (!done.add(ing.key)) {
            return;
        }
        onPath.add(ing.key);
        TreeRecipe recipe = recipeOf(ing);
        if (recipe != null) {
            for (TreeRecipe.Input input : recipe.inputs) {
                Ing child = pick(input);
                if (onPath.contains(child.key)) {
                    backEdges.add(ing.key + ">" + child.key);
                } else {
                    visit(child, done, onPath, backEdges, order);
                }
            }
        }
        onPath.remove(ing.key);
        order.add(ing);
    }

    private static void add(Map<String, Line> lines, Ing ing, long amount) {
        Line line = lines.get(ing.key);
        if (line == null) {
            line = new Line();
            line.ing = ing;
            lines.put(ing.key, line);
        }
        line.amount += amount;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
