package better112.content;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import better112.Better112;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/**
 * The crafting recipes of an imported mod, read from its recipe files: planks from logs,
 * slabs and stairs and doors from planks, sandstone from sand, dyes from flowers. A recipe
 * that needs an item this game has not been given (a block not brought over, a 1.16+
 * vanilla item) is left out.
 */
final class ContentRecipes {

    private ContentRecipes() {}

    private static final String[] DYES = {"black", "red", "green", "brown", "blue", "purple", "cyan", "light_gray", "gray", "pink", "lime",
        "yellow", "light_blue", "magenta", "orange", "white"};

    static int crafting, smelting;
    private static final List<Object[]> SMELTS = new ArrayList<Object[]>();

    static void register(RegistryEvent.Register<IRecipe> event) {
        for (Source source : new Source[] {ModernContent.MC, ModernContent.BOP}) {
            if (source.jar != null) {
                register(event, source);
            }
        }
        Better112.LOG.info("Modern content: {} crafting recipes", crafting);
    }

    private static void register(RegistryEvent.Register<IRecipe> event, Source source) {
        // Recipe files moved from "recipes" to "recipe" in Minecraft 1.21.
        String[] folders = {"data/" + source.namespace + "/recipes/", "data/" + source.namespace + "/recipe/"};
        try (ZipFile zip = new ZipFile(source.jar)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                String folder = name.startsWith(folders[0]) ? folders[0] : name.startsWith(folders[1]) ? folders[1] : null;
                if (folder == null || !name.endsWith(".json")) {
                    continue;
                }
                JsonObject json;
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    json = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                }
                String id = source.prefix + "_" + name.substring(folder.length(), name.length() - 5).replace('/', '_');
                try {
                    IRecipe recipe = recipe(zip, json, source);
                    if (recipe != null) {
                        event.getRegistry().register(recipe.setRegistryName(new ResourceLocation("better112", id)));
                        crafting++;
                    }
                } catch (RuntimeException skip) {
                    // a recipe in a form this does not read
                }
            }
        } catch (Exception e) {
            Better112.LOG.warn("Recipes of {} are unavailable", source.title, e);
        }
    }

    /** What a recipe makes: {"item": ...} in older files, {"id": ...} since 1.20.5, or the bare name. */
    private static ItemStack result(JsonElement json, Source source) {
        String id;
        int count = 1;
        if (json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            id = o.has("id") ? o.get("id").getAsString() : o.get("item").getAsString();
            count = o.has("count") ? o.get("count").getAsInt() : 1;
        } else {
            id = json.getAsString();
        }
        // From Minecraft's own recipes only those that make one of the imported blocks are wanted: the rest the game has already.
        if (source.vanilla() && ModernContent.item(id) == null) {
            return null;
        }
        return stack(id, count);
    }

    private static IRecipe recipe(ZipFile zip, JsonObject json, Source source) throws java.io.IOException {
        String type = json.get("type").getAsString().replace("minecraft:", "");
        if (type.equals("smelting")) {
            ItemStack out = result(json.get("result"), source);
            Ingredient in = out == null ? null : ingredient(zip, json.get("ingredient"));
            if (out != null && in != null) {
                SMELTS.add(new Object[] {in, out, json.has("experience") ? json.get("experience").getAsFloat() : 0.1F});
            }
            return null;
        }
        if (!type.equals("crafting_shaped") && !type.equals("crafting_shapeless")) {
            return null;
        }
        ItemStack out = result(json.get("result"), source);
        if (out == null) {
            return null;
        }
        ResourceLocation group = new ResourceLocation("better112", json.has("group") ? json.get("group").getAsString() : source.prefix);
        List<Object> parts = new ArrayList<Object>();
        if (type.equals("crafting_shaped")) {
            for (JsonElement row : json.getAsJsonArray("pattern")) {
                parts.add(row.getAsString());
            }
            for (Map.Entry<String, JsonElement> key : json.getAsJsonObject("key").entrySet()) {
                Ingredient ingredient = ingredient(zip, key.getValue());
                if (ingredient == null) {
                    return null;
                }
                parts.add(key.getKey().charAt(0));
                parts.add(ingredient);
            }
            return new ShapedOreRecipe(group, out, parts.toArray());
        }
        for (JsonElement each : json.getAsJsonArray("ingredients")) {
            Ingredient ingredient = ingredient(zip, each);
            if (ingredient == null) {
                return null;
            }
            parts.add(ingredient);
        }
        return new ShapelessOreRecipe(group, out, parts.toArray());
    }

    /**
     * An item, a tag or a list of either, as the items this game has; null if none of them is
     * known. Older files write {"item": ...} and {"tag": ...}; since 1.21.2 it is the bare name,
     * with a "#" in front of a tag.
     */
    private static Ingredient ingredient(ZipFile zip, JsonElement json) throws java.io.IOException {
        List<ItemStack> stacks = new ArrayList<ItemStack>();
        if (json.isJsonArray()) {
            for (JsonElement each : json.getAsJsonArray()) {
                Ingredient one = ingredient(zip, each);
                if (one != null) {
                    for (ItemStack stack : one.getMatchingStacks()) {
                        stacks.add(stack);
                    }
                }
            }
        } else {
            String item = null, tag = null;
            if (json.isJsonObject()) {
                JsonObject o = json.getAsJsonObject();
                item = o.has("item") ? o.get("item").getAsString() : null;
                tag = o.has("tag") ? o.get("tag").getAsString() : null;
            } else if (json.getAsString().startsWith("#")) {
                tag = json.getAsString().substring(1);
            } else {
                item = json.getAsString();
            }
            if (item != null) {
                ItemStack stack = stack(item, 1);
                if (stack != null) {
                    stacks.add(stack);
                }
            } else if (tag != null) {
                for (String id : tag(zip, tag, new HashSet<String>())) {
                    ItemStack stack = stack(id, 1);
                    if (stack != null) {
                        stacks.add(stack);
                    }
                }
            }
        }
        return stacks.isEmpty() ? null : Ingredient.fromStacks(stacks.toArray(new ItemStack[0]));
    }

    private static Set<String> tag(ZipFile zip, String tag, Set<String> seen) throws java.io.IOException {
        Set<String> items = new HashSet<String>();
        int colon = tag.indexOf(':');
        if (colon < 0 || !seen.add(tag)) {
            return items;
        }
        // Tag folders lost their plural in 1.21 too.
        ZipEntry entry = zip.getEntry("data/" + tag.substring(0, colon) + "/tags/items/" + tag.substring(colon + 1) + ".json");
        if (entry == null) {
            entry = zip.getEntry("data/" + tag.substring(0, colon) + "/tags/item/" + tag.substring(colon + 1) + ".json");
        }
        if (entry == null) {
            return items;
        }
        JsonObject json;
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            json = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (JsonElement value : json.getAsJsonArray("values")) {
            String id = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
            if (id.startsWith("#")) {
                items.addAll(tag(zip, id.substring(1), seen));
            } else {
                items.add(id);
            }
        }
        return items;
    }

    /** A modern item id as a stack of the 1.12.2 item that is the same thing, or null. */
    private static ItemStack stack(String id, int count) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon), name = colon < 0 ? id : id.substring(colon + 1);
        Item imported = ModernContent.item(id);
        if (imported != null || !namespace.equals("minecraft")) {
            return imported == null ? null : new ItemStack(imported, count);
        }
        if (name.endsWith("_dye")) {
            for (int i = 0; i < DYES.length; i++) {
                if (name.equals(DYES[i] + "_dye")) {
                    return new ItemStack(Items.DYE, count, i);
                }
            }
            return null;
        }
        switch (name) {
            case "stone_bricks":
                return new ItemStack(Blocks.STONEBRICK, count);
            case "mossy_stone_bricks":
                return new ItemStack(Blocks.STONEBRICK, count, 1);
            case "charcoal":
                return new ItemStack(Items.COAL, count, 1);
            case "oak_planks":
                return new ItemStack(Blocks.PLANKS, count);
            default:
                break;
        }
        Item item = Item.REGISTRY.getObject(new ResourceLocation("minecraft", name));
        return item == null ? null : new ItemStack(item, count);
    }

    /** Furnace recipes go in once every item exists. */
    static void smelt() {
        for (Object[] smelt : SMELTS) {
            for (ItemStack in : ((Ingredient) smelt[0]).getMatchingStacks()) {
                GameRegistry.addSmelting(in, (ItemStack) smelt[1], (Float) smelt[2]);
                smelting++;
            }
        }
        // Charcoal from any imported log, as from any vanilla one.
        for (BlockDef def : ModernContent.DEFS) {
            Item item = ModernContent.ITEMS.get(def.key);
            if (def.wood != null && def.kind == BlockDef.Kind.PILLAR && item != null && !def.name.startsWith("bamboo") && !def.name.contains("bamboo_")) {
                GameRegistry.addSmelting(new ItemStack(item), new ItemStack(Items.COAL, 1, 1), 0.15F);
                smelting++;
            }
        }
    }

    /**
     * The vanilla button, pressure plate and trapdoor recipes take any wood and come first in
     * the list, so a fir plank would make an oak button. Each is swapped for a copy of itself
     * that steps aside when the grid holds nothing but an imported wood's planks, which lets
     * the wood's own recipe answer. For every other wood they work as before.
     */
    static void yieldVanilla(RegistryEvent.Register<IRecipe> event) {
        final Set<Item> planks = new HashSet<Item>();
        for (BlockDef def : ModernContent.DEFS) {
            Item item = ModernContent.ITEMS.get(def.key);
            if (def.wood != null && def.name.endsWith("_planks") && item != null) {
                planks.add(item);
            }
        }
        if (planks.isEmpty()) {
            return;
        }
        for (String name : new String[] {"wooden_button", "wooden_pressure_plate", "trapdoor"}) {
            try {
                ResourceLocation id = new ResourceLocation("minecraft", name);
                IRecipe original = event.getRegistry().getValue(id);
                if (original != null && !(original instanceof Yielding)) {
                    event.getRegistry().register(new Yielding(original, planks).setRegistryName(id));
                }
            } catch (RuntimeException e) {
                Better112.LOG.debug("Left the vanilla {} recipe as it is: {}", name, e.toString());
            }
        }
    }

    private static final class Yielding extends net.minecraftforge.registries.IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {
        private final IRecipe original;
        private final Set<Item> planks;

        Yielding(IRecipe original, Set<Item> planks) {
            this.original = original;
            this.planks = planks;
        }

        @Override
        public boolean matches(net.minecraft.inventory.InventoryCrafting grid, net.minecraft.world.World world) {
            if (!original.matches(grid, world)) {
                return false;
            }
            for (int i = 0; i < grid.getSizeInventory(); i++) {
                ItemStack stack = grid.getStackInSlot(i);
                if (!stack.isEmpty() && !planks.contains(stack.getItem())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ItemStack getCraftingResult(net.minecraft.inventory.InventoryCrafting grid) {
            return original.getCraftingResult(grid);
        }

        @Override
        public boolean canFit(int width, int height) {
            return original.canFit(width, height);
        }

        @Override
        public ItemStack getRecipeOutput() {
            return original.getRecipeOutput();
        }

        @Override
        public net.minecraft.util.NonNullList<ItemStack> getRemainingItems(net.minecraft.inventory.InventoryCrafting grid) {
            return original.getRemainingItems(grid);
        }

        @Override
        public net.minecraft.util.NonNullList<Ingredient> getIngredients() {
            return original.getIngredients();
        }

        @Override
        public boolean isDynamic() {
            return original.isDynamic();
        }

        @Override
        public String getGroup() {
            return original.getGroup();
        }
    }
}
