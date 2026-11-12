package better112.jei;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import better112.Better112;
import better112.config.BetterConfig;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IModIngredientRegistration;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeRegistryPlugin;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.bookmarks.BookmarkList;
import mezz.jei.config.Config;
import mezz.jei.config.KeyBindings;
import mezz.jei.gui.recipes.RecipeLayout;
import mezz.jei.ingredients.Ingredients;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Recipe bookmarks, as in modern recipe viewers: Shift + JEI's bookmark key over a recipe puts that
 * recipe, not just its item, into JEI's bookmark list. Clicking the bookmark opens exactly that recipe
 * and holding Shift over it shows the recipe in place.
 *
 * A bookmarked recipe is a JEI ingredient of its own kind, so JEI keeps its place in the list; what the
 * bookmark stands for is kept in config/better112/recipe_bookmarks.json. JEI Utilities does the same job, so
 * this stays out of the way when that mod is installed.
 */
public final class RecipeMarks {

    private RecipeMarks() {}

    /** One bookmarked recipe. */
    public static final class Mark {
        final String category;
        /** What the recipe makes: an ItemStack or a FluidStack. */
        final Object icon;
        /** Tells this recipe from the others that make the same thing. */
        final String sig;
        private IRecipeWrapper wrapper;
        private IRecipeCategory<?> found;
        private boolean looked;

        Mark(String category, Object icon, String sig) {
            this.category = category;
            this.icon = icon;
            this.sig = sig;
        }

        String uid() {
            return ("better112.recipe|" + category + "|" + idOf(icon) + "|" + sig).replace('\n', ' ');
        }

        /** The recipe itself, looked up once JEI has its recipes; the first recipe for the item if this one is gone. */
        IRecipeWrapper wrapper() {
            if (!looked) {
                IJeiRuntime runtime = BetterJeiPlugin.runtime;
                if (runtime == null) {
                    return null;
                }
                looked = true;
                try {
                    IRecipeRegistry registry = runtime.getRecipeRegistry();
                    IRecipeCategory<?> wanted = registry.getRecipeCategory(category);
                    if (wanted != null) {
                        List<? extends IRecipeWrapper> all = registry.getRecipeWrappers(wanted, registry.createFocus(IFocus.Mode.OUTPUT, icon));
                        for (IRecipeWrapper each : all) {
                            if (sig.equals(sigOf(each))) {
                                wrapper = each;
                                break;
                            }
                        }
                        if (wrapper == null && !all.isEmpty()) {
                            wrapper = all.get(0);
                        }
                        found = wanted;
                    }
                } catch (Throwable t) {
                    Better112.LOG.debug("Recipe bookmark lookup failed: {}", t.toString());
                }
            }
            return wrapper;
        }

        String name() {
            return icon instanceof ItemStack ? ((ItemStack) icon).getDisplayName() : ((FluidStack) icon).getLocalizedName();
        }
    }

    static final IIngredientType<Mark> TYPE = () -> Mark.class;
    private static final List<Mark> MARKS = new ArrayList<>();
    private static IIngredientRegistry ingredients;
    private static boolean active;
    /** Self-test: draw the first bookmark's recipe as if Shift were held over it. */
    public static boolean testPreview;

    private static File file() {
        return new File(Loader.instance().getConfigDir(), "better112/recipe_bookmarks.json");
    }

    private static String idOf(Object icon) {
        if (icon instanceof ItemStack) {
            ItemStack stack = (ItemStack) icon;
            NBTTagCompound tag = stack.getTagCompound();
            return stack.getItem().getRegistryName() + ":" + stack.getMetadata() + (tag == null ? "" : tag.toString());
        }
        FluidStack fluid = (FluidStack) icon;
        return "fluid:" + fluid.getFluid().getName() + (fluid.tag == null ? "" : fluid.tag.toString());
    }

    private static Ingredients read(IRecipeWrapper wrapper) {
        Ingredients all = new Ingredients();
        try {
            wrapper.getIngredients(all);
        } catch (Throwable t) {
            // a recipe that cannot describe itself still gets a bookmark, just a less exact one
        }
        return all;
    }

    static String sigOf(IRecipeWrapper wrapper) {
        Ingredients all = read(wrapper);
        StringBuilder text = new StringBuilder();
        for (List<ItemStack> slot : all.getInputs(VanillaTypes.ITEM)) {
            ItemStack first = slot.isEmpty() ? null : slot.get(0);
            text.append(first == null || first.isEmpty() ? "-" : idOf(first) + "x" + first.getCount()).append(',');
        }
        for (List<FluidStack> slot : all.getInputs(VanillaTypes.FLUID)) {
            FluidStack first = slot.isEmpty() ? null : slot.get(0);
            text.append(first == null || first.getFluid() == null ? "-" : idOf(first) + "x" + first.amount).append(',');
        }
        return Integer.toHexString(text.toString().hashCode());
    }

    /** What stands for the recipe in the list: the output the player asked for, else its first output. */
    private static Object iconOf(IRecipeWrapper wrapper, IFocus<?> focus) {
        Ingredients all = read(wrapper);
        List<List<ItemStack>> items = all.getOutputs(VanillaTypes.ITEM);
        if (focus != null && focus.getMode() == IFocus.Mode.OUTPUT && focus.getValue() instanceof ItemStack) {
            ItemStack wanted = (ItemStack) focus.getValue();
            for (List<ItemStack> slot : items) {
                for (ItemStack stack : slot) {
                    if (stack != null && !stack.isEmpty() && ItemStack.areItemsEqual(stack, wanted)) {
                        return stack.copy();
                    }
                }
            }
        }
        for (List<ItemStack> slot : items) {
            for (ItemStack stack : slot) {
                if (stack != null && !stack.isEmpty()) {
                    return stack.copy();
                }
            }
        }
        for (List<FluidStack> slot : all.getOutputs(VanillaTypes.FLUID)) {
            for (FluidStack fluid : slot) {
                if (fluid != null && fluid.getFluid() != null) {
                    return fluid.copy();
                }
            }
        }
        return null;
    }

    // --- Keeping the list ---

    /** The bookmark lines JEI saved for this kind of ingredient, or null if its file cannot be read. */
    private static Set<String> keptByJei() {
        try {
            File saved = Config.getBookmarkFile();
            Set<String> kept = new HashSet<>();
            if (saved != null && saved.isFile()) {
                for (String line : Files.readAllLines(saved.toPath(), StandardCharsets.UTF_8)) {
                    if (line.startsWith("O:")) {
                        kept.add(line.substring(2));
                    }
                }
            }
            return kept;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Mark> load() {
        MARKS.clear();
        File file = file();
        if (!file.isFile()) {
            return MARKS;
        }
        Set<String> kept = keptByJei();
        int read = 0;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            for (JsonElement each : new JsonParser().parse(reader).getAsJsonArray()) {
                read++;
                try {
                    JsonObject object = each.getAsJsonObject();
                    Object icon = null;
                    if (object.has("item")) {
                        ItemStack stack = new ItemStack(JsonToNBT.getTagFromJson(object.get("item").getAsString()));
                        icon = stack.isEmpty() ? null : stack;
                    } else if (object.has("fluid")) {
                        icon = FluidStack.loadFluidStackFromNBT(JsonToNBT.getTagFromJson(object.get("fluid").getAsString()));
                    }
                    if (icon == null) {
                        continue; // its mod is no longer installed
                    }
                    Mark mark = new Mark(object.get("category").getAsString(), icon, object.get("sig").getAsString());
                    // A bookmark removed in JEI is gone from JEI's own file; forget what it stood for too.
                    if (kept == null || kept.contains(mark.uid())) {
                        MARKS.add(mark);
                    }
                } catch (Exception e) {
                    Better112.LOG.warn("Skipped an unreadable recipe bookmark: {}", e.toString());
                }
            }
        } catch (Exception e) {
            Better112.LOG.warn("Could not read {}: {}", file, e.toString());
            return MARKS;
        }
        if (read != MARKS.size()) {
            save();
        }
        return MARKS;
    }

    private static void save() {
        JsonArray array = new JsonArray();
        for (Mark mark : MARKS) {
            JsonObject object = new JsonObject();
            object.addProperty("category", mark.category);
            if (mark.icon instanceof ItemStack) {
                object.addProperty("item", ((ItemStack) mark.icon).writeToNBT(new NBTTagCompound()).toString());
            } else {
                object.addProperty("fluid", ((FluidStack) mark.icon).writeToNBT(new NBTTagCompound()).toString());
            }
            object.addProperty("sig", mark.sig);
            array.add(object);
        }
        File file = file();
        try {
            file.getParentFile().mkdirs();
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                writer.write(array.toString());
            }
        } catch (Exception e) {
            Better112.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }

    // --- JEI's side ---

    static void registerType(IModIngredientRegistration registration) {
        active = BetterConfig.recipeBookmarks && !Loader.isModLoaded("jeiutilities");
        if (active) {
            registration.register(TYPE, new ArrayList<>(load()), new Helper(), new Renderer());
        }
    }

    static void register(IModRegistry registry) {
        if (!active) {
            return;
        }
        ingredients = registry.getIngredientRegistry();
        registry.addRecipeRegistryPlugin(new Recipes());
        // They belong in the bookmark list only, not among the items on the right.
        for (Mark mark : MARKS) {
            registry.getJeiHelpers().getIngredientBlacklist().addIngredientToBlacklist(mark);
        }
    }

    private static final class Helper implements IIngredientHelper<Mark> {
        @Override
        public Mark getMatch(Iterable<Mark> all, Mark wanted) {
            String uid = wanted.uid();
            for (Mark mark : all) {
                if (mark.uid().equals(uid)) {
                    return mark;
                }
            }
            return null;
        }

        @Override
        public String getDisplayName(Mark mark) {
            return mark.name();
        }

        @Override
        public String getUniqueId(Mark mark) {
            return mark.uid();
        }

        @Override
        public String getWildcardId(Mark mark) {
            return mark.uid();
        }

        @Override
        public String getModId(Mark mark) {
            if (mark.icon instanceof ItemStack) {
                ResourceLocation name = ((ItemStack) mark.icon).getItem().getRegistryName();
                return name == null ? "minecraft" : name.getNamespace();
            }
            return "minecraft";
        }

        @Override
        public String getResourceId(Mark mark) {
            if (mark.icon instanceof ItemStack) {
                ResourceLocation name = ((ItemStack) mark.icon).getItem().getRegistryName();
                return name == null ? "" : name.getPath();
            }
            return ((FluidStack) mark.icon).getFluid().getName();
        }

        @Override
        public ItemStack getCheatItemStack(Mark mark) {
            return mark.icon instanceof ItemStack ? ((ItemStack) mark.icon).copy() : ItemStack.EMPTY;
        }

        @Override
        public Mark copyIngredient(Mark mark) {
            return mark;
        }

        @Override
        public String getErrorInfo(Mark mark) {
            return mark == null ? "null" : mark.uid();
        }
    }

    private static final class Renderer implements IIngredientRenderer<Mark> {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void render(Minecraft mc, int x, int y, Mark mark) {
            if (mark == null || ingredients == null) {
                return;
            }
            ((IIngredientRenderer) ingredients.getIngredientRenderer(mark.icon)).render(mc, x, y, mark.icon);
            // A folded corner tells a recipe from a plain item.
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            for (int row = 0; row < 5; row++) {
                Gui.drawRect(x, y + row, x + 5 - row, y + row + 1, 0xFF94E4D3);
            }
            GlStateManager.enableDepth();
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public List<String> getTooltip(Minecraft mc, Mark mark, ITooltipFlag flag) {
            List<String> lines = new ArrayList<>();
            if (ingredients != null) {
                lines.addAll(((IIngredientRenderer) ingredients.getIngredientRenderer(mark.icon)).getTooltip(mc, mark.icon, flag));
            }
            IRecipeWrapper wrapper = mark.wrapper();
            if (wrapper == null || mark.found == null) {
                return lines;
            }
            lines.add(TextFormatting.DARK_AQUA + I18n.format("better112.recipe_mark.kind", mark.found.getTitle()));
            int shown = 0;
            for (Map.Entry<String, Integer> input : inputs(wrapper).entrySet()) {
                if (++shown > 6) {
                    lines.add(TextFormatting.DARK_GRAY + "...");
                    break;
                }
                lines.add(TextFormatting.GRAY + String.valueOf(input.getValue()) + " × " + input.getKey());
            }
            lines.add(TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC + I18n.format("better112.recipe_mark.hint"));
            return lines;
        }
    }

    /** What the recipe takes, by name, with amounts added up. */
    private static Map<String, Integer> inputs(IRecipeWrapper wrapper) {
        Ingredients all = read(wrapper);
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (List<ItemStack> slot : all.getInputs(VanillaTypes.ITEM)) {
            ItemStack first = slot.isEmpty() ? null : slot.get(0);
            if (first != null && !first.isEmpty()) {
                totals.merge(first.getDisplayName(), first.getCount(), Integer::sum);
            }
        }
        for (List<FluidStack> slot : all.getInputs(VanillaTypes.FLUID)) {
            FluidStack first = slot.isEmpty() ? null : slot.get(0);
            if (first != null && first.getFluid() != null) {
                totals.merge(first.getLocalizedName() + " (mB)", first.amount, Integer::sum);
            }
        }
        return totals;
    }

    /** Opening a bookmarked recipe shows that one recipe; asking for its uses asks for the item's. */
    private static final class Recipes implements IRecipeRegistryPlugin {
        @Override
        public <V> List<String> getRecipeCategoryUids(IFocus<V> focus) {
            IJeiRuntime runtime = BetterJeiPlugin.runtime;
            if (!(focus.getValue() instanceof Mark) || runtime == null) {
                return Collections.emptyList();
            }
            Mark mark = (Mark) focus.getValue();
            if (focus.getMode() == IFocus.Mode.OUTPUT && mark.wrapper() != null) {
                return Collections.singletonList(mark.category);
            }
            IRecipeRegistry registry = runtime.getRecipeRegistry();
            List<String> uids = new ArrayList<>();
            for (IRecipeCategory<?> category : registry.getRecipeCategories(registry.createFocus(focus.getMode(), mark.icon))) {
                uids.add(category.getUid());
            }
            return uids;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends IRecipeWrapper, V> List<T> getRecipeWrappers(IRecipeCategory<T> category, IFocus<V> focus) {
            IJeiRuntime runtime = BetterJeiPlugin.runtime;
            if (!(focus.getValue() instanceof Mark) || runtime == null) {
                return Collections.emptyList();
            }
            Mark mark = (Mark) focus.getValue();
            if (focus.getMode() == IFocus.Mode.OUTPUT && mark.wrapper() != null) {
                return category.getUid().equals(mark.category) ? Collections.singletonList((T) mark.wrapper()) : Collections.<T>emptyList();
            }
            IRecipeRegistry registry = runtime.getRecipeRegistry();
            return registry.getRecipeWrappers(category, registry.createFocus(focus.getMode(), mark.icon));
        }

        @Override
        public <T extends IRecipeWrapper> List<T> getRecipeWrappers(IRecipeCategory<T> category) {
            return Collections.emptyList();
        }
    }

    // --- Making and showing bookmarks ---

    private static BookmarkList jeiList() throws Exception {
        Object overlay = BetterJeiPlugin.runtime.getBookmarkOverlay();
        for (Field field : overlay.getClass().getDeclaredFields()) {
            if (field.getType() == BookmarkList.class) {
                field.setAccessible(true);
                return (BookmarkList) field.get(overlay);
            }
        }
        throw new NoSuchFieldException("bookmark list");
    }

    @SuppressWarnings("unchecked")
    private static List<RecipeLayout> layouts(GuiScreen recipes) throws Exception {
        Field field = recipes.getClass().getDeclaredField("recipeLayouts");
        field.setAccessible(true);
        return (List<RecipeLayout>) field.get(recipes);
    }

    /** Bookmarks the recipe, or takes its bookmark away again. Returns what happened, or null if it has no output to show. */
    private static String toggle(RecipeLayout layout) throws Exception {
        Field field = RecipeLayout.class.getDeclaredField("recipeWrapper");
        field.setAccessible(true);
        IRecipeWrapper wrapper = (IRecipeWrapper) field.get(layout);
        Object icon = iconOf(wrapper, layout.getFocus());
        if (icon == null) {
            return null;
        }
        Mark mark = new Mark(layout.getRecipeCategory().getUid(), icon, sigOf(wrapper));
        String uid = mark.uid();
        Mark known = null;
        for (Mark each : MARKS) {
            if (each.uid().equals(uid)) {
                known = each;
            }
        }
        BookmarkList list = jeiList();
        if (known != null && list.remove(known)) {
            MARKS.remove(known);
            save();
            return "removed " + mark.name();
        }
        if (known == null) {
            MARKS.add(mark);
            known = mark;
        }
        list.add(known);
        save();
        return "added " + mark.name();
    }

    /** Registered on the event bus only when JEI is installed. */
    public static final class Events {
        private Mark shownFor;
        private IRecipeLayoutDrawable shown;
        private boolean warned;

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
            IJeiRuntime runtime = BetterJeiPlugin.runtime;
            if (!active || runtime == null || !Keyboard.getEventKeyState() || !GuiScreen.isShiftKeyDown()
                    || (Object) runtime.getRecipesGui() != event.getGui()) {
                return;
            }
            try {
                int key = Keyboard.getEventKey();
                if (key == 0 || key != KeyBindings.bookmark.getKeyCode()) {
                    return;
                }
                GuiScreen gui = event.getGui();
                Minecraft mc = Minecraft.getMinecraft();
                int mouseX = Mouse.getX() * gui.width / mc.displayWidth;
                int mouseY = gui.height - Mouse.getY() * gui.height / mc.displayHeight - 1;
                for (RecipeLayout layout : layouts(gui)) {
                    if (layout.isMouseOver(mouseX, mouseY)) {
                        if (toggle(layout) != null) {
                            event.setCanceled(true);
                        }
                        return;
                    }
                }
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    Better112.LOG.warn("Recipe bookmarks do not work with this JEI: {}", t.toString());
                }
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
            IJeiRuntime runtime = BetterJeiPlugin.runtime;
            if (!active || runtime == null) {
                return;
            }
            try {
                Mark mark = null;
                if (testPreview) {
                    mark = MARKS.isEmpty() ? null : MARKS.get(0);
                } else if (GuiScreen.isShiftKeyDown()) {
                    Object hovered = runtime.getBookmarkOverlay().getIngredientUnderMouse();
                    mark = hovered instanceof Mark ? (Mark) hovered : null;
                }
                if (mark == null || mark.wrapper() == null) {
                    shownFor = null;
                    shown = null;
                    return;
                }
                if (shownFor != mark) {
                    shown = create(runtime.getRecipeRegistry(), mark);
                    shownFor = mark;
                }
                IDrawable background = mark.found.getBackground();
                int width = background.getWidth(), height = background.getHeight();
                GuiScreen gui = event.getGui();
                int x = Math.max(6, Math.min(event.getMouseX() + 10, gui.width - width - 6));
                int y = Math.max(6, Math.min(event.getMouseY() - height - 14, gui.height - height - 6));
                GlStateManager.pushMatrix();
                GlStateManager.translate(0f, 0f, 350f); // above the tooltip
                GlStateManager.disableLighting();
                GlStateManager.disableDepth();
                Gui.drawRect(x - 5, y - 5, x + width + 5, y + height + 5, 0xFF000000);
                Gui.drawRect(x - 4, y - 4, x + width + 4, y + height + 4, 0xFFFFFFFF);
                Gui.drawRect(x - 3, y - 3, x + width + 4, y + height + 4, 0xFF555555);
                Gui.drawRect(x - 3, y - 3, x + width + 3, y + height + 3, 0xFFC6C6C6);
                GlStateManager.enableDepth();
                GlStateManager.color(1f, 1f, 1f, 1f);
                shown.setPosition(x, y);
                shown.drawRecipe(Minecraft.getMinecraft(), -1, -1);
                GlStateManager.popMatrix();
                GlStateManager.disableLighting();
            } catch (Throwable t) {
                shownFor = null;
                shown = null;
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static IRecipeLayoutDrawable create(IRecipeRegistry registry, Mark mark) {
            return registry.createRecipeLayoutDrawable((IRecipeCategory) mark.found, mark.wrapper(), registry.createFocus(IFocus.Mode.OUTPUT, mark));
        }
    }

    /** The item or fluid a hovered bookmark stands for, so the rest of the mod can treat it as that. */
    static Object unwrap(Object hovered) {
        return hovered instanceof Mark ? ((Mark) hovered).icon : hovered;
    }

    /** Self-test: bookmark the first recipe on the open JEI recipe screen. */
    public static String testMarkFirst() {
        try {
            GuiScreen screen = Minecraft.getMinecraft().currentScreen;
            if (!active || BetterJeiPlugin.runtime == null || (Object) BetterJeiPlugin.runtime.getRecipesGui() != screen) {
                return "no recipe screen (active " + active + ")";
            }
            List<RecipeLayout> all = layouts(screen);
            return all.isEmpty() ? "no recipes shown" : toggle(all.get(0)) + ", " + state();
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }

    /** Self-test: how many recipe bookmarks there are and how many of them JEI lists. */
    public static String state() {
        try {
            int listed = 0;
            for (Object element : jeiList().getIngredientList()) {
                if (((mezz.jei.gui.ingredients.IIngredientListElement) element).getIngredient() instanceof Mark) {
                    listed++;
                }
            }
            StringBuilder text = new StringBuilder(MARKS.size() + " kept, " + listed + " in JEI's list");
            for (Mark mark : MARKS) {
                text.append("; ").append(mark.name()).append(" [").append(mark.category).append(mark.wrapper() == null ? ", recipe gone]" : "]");
            }
            return text.toString();
        } catch (Throwable t) {
            return "active " + active + ", " + t;
        }
    }

    /** Self-test: open the first bookmark the way a click on it does. */
    public static String testOpenFirst() {
        if (MARKS.isEmpty() || BetterJeiPlugin.runtime == null) {
            return "nothing to open";
        }
        IJeiRuntime runtime = BetterJeiPlugin.runtime;
        runtime.getRecipesGui().show(runtime.getRecipeRegistry().createFocus(IFocus.Mode.OUTPUT, MARKS.get(0)));
        try {
            return layouts(Minecraft.getMinecraft().currentScreen).size() + " recipe(s) shown";
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }
}
