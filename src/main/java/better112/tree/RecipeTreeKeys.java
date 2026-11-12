package better112.tree;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import better112.Better112;
import better112.config.BetterConfig;
import better112.jei.JeiBridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.oredict.OreDictionary;

import org.lwjgl.input.Keyboard;

/** Key binds and tooltips for recipes. Works with JEI when present and without it. */
public final class RecipeTreeKeys {

    public static final KeyBinding KEY_TREE =
            new KeyBinding("better112.key.tree", KeyConflictContext.GUI, Keyboard.KEY_T, "better112.key.category");
    public static final KeyBinding KEY_OPEN =
            new KeyBinding("better112.key.tree_open", KeyConflictContext.IN_GAME, Keyboard.KEY_NONE, "better112.key.category");

    private static RecipeSource jeiSource;
    private static RecipeSource vanillaSource;
    private static RecipeTree last;

    private static final Map<Class<?>, List<Field>> TEXT_FIELDS = new HashMap<Class<?>, List<Field>>();

    private RecipeTreeKeys() {}

    public static void init() {
        ClientRegistry.registerKeyBinding(KEY_TREE);
        ClientRegistry.registerKeyBinding(KEY_OPEN);
        MinecraftForge.EVENT_BUS.register(new RecipeTreeKeys());
    }

    public static RecipeSource source() {
        if (Loader.isModLoaded("jei")) {
            try {
                if (JeiBridge.ready()) {
                    if (jeiSource == null) {
                        jeiSource = JeiBridge.createSource();
                    }
                    return jeiSource;
                }
            } catch (Throwable t) {
                Better112.LOG.warn("JEI is installed but could not be used for the recipe tree", t);
            }
        }
        if (vanillaSource == null) {
            vanillaSource = new VanillaRecipeSource();
        }
        return vanillaSource;
    }

    public static void open(GuiScreen parent, Ing ing) {
        RecipeSource source = source();
        if (last == null || last.source != source) {
            last = new RecipeTree(source);
        }
        if (ing != null) {
            last.setRoot(ing);
        }
        Minecraft.getMinecraft().displayGuiScreen(new RecipeTreeScreen(parent, last));
    }

    /** Self-test entry: "modid:name" or "modid:name@meta". */
    public static void openFor(String itemId) {
        int at = itemId.indexOf('@');
        Item item = Item.REGISTRY.getObject(new ResourceLocation(at < 0 ? itemId : itemId.substring(0, at)));
        if (item == null) {
            Better112.LOG.warn("No such item: {}", itemId);
            return;
        }
        // Opened over whatever screen is up, the way the key does it, so an ME terminal's stock is read.
        open(Minecraft.getMinecraft().currentScreen, Ing.of(new ItemStack(item, 1, at < 0 ? 0 : Integer.parseInt(itemId.substring(at + 1)))));
    }

    @SubscribeEvent
    public void onGuiKey(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (!Keyboard.getEventKeyState()) {
            return;
        }
        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_NONE || !KEY_TREE.isActiveAndMatches(key)) {
            return;
        }
        GuiScreen gui = event.getGui();
        if (gui == null || gui instanceof RecipeTreeScreen) {
            return;
        }
        RecipeSource source = source();
        if (source.hasTextFocus() || isTyping(gui)) {
            return;
        }
        Ing ing = source.hovered(gui);
        if (ing == null && gui instanceof GuiContainer) {
            Slot slot = ((GuiContainer) gui).getSlotUnderMouse();
            if (slot != null && slot.getHasStack()) {
                ing = Ing.of(slot.getStack());
            }
        }
        if (ing != null) {
            open(gui, ing);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && KEY_OPEN.isPressed() && Minecraft.getMinecraft().currentScreen == null) {
            open(null, null);
        }
    }

    /** True if any text box on the screen has the cursor, so letters are being typed, not pressed. */
    public static boolean isTyping(GuiScreen gui) {
        List<Field> fields = TEXT_FIELDS.get(gui.getClass());
        if (fields == null) {
            fields = new ArrayList<Field>();
            for (Class<?> type = gui.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (GuiTextField.class.isAssignableFrom(field.getType())) {
                        try {
                            field.setAccessible(true);
                            fields.add(field);
                        } catch (Throwable ignored) {
                            // not reachable; nothing to check
                        }
                    }
                }
            }
            TEXT_FIELDS.put(gui.getClass(), fields);
        }
        for (Field field : fields) {
            try {
                GuiTextField text = (GuiTextField) field.get(gui);
                if (text != null && text.isFocused() && text.getVisible()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // treat an unreadable field as not focused
            }
        }
        return false;
    }

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        if (!BetterConfig.oreDictTooltips || event.getItemStack().isEmpty()) {
            return;
        }
        if (Loader.isModLoaded("jei")) {
            try {
                List<String> accepted = JeiBridge.acceptedOres();
                if (accepted != null && !accepted.isEmpty()) {
                    event.getToolTip().add(TextFormatting.GRAY + I18n.format("better112.tree.oredict", String.join(", ", accepted)));
                }
            } catch (Throwable ignored) {
                // JEI internals differ in this pack; skip the hint
            }
        }
        if (!GuiScreen.isShiftKeyDown()) {
            return;
        }
        int[] ids;
        try {
            ids = OreDictionary.getOreIDs(event.getItemStack());
        } catch (Throwable t) {
            return;
        }
        if (ids.length == 0) {
            return;
        }
        event.getToolTip().add(TextFormatting.DARK_GRAY + I18n.format("better112.tooltip.oredict"));
        for (int id : ids) {
            event.getToolTip().add(TextFormatting.DARK_GRAY + "  " + OreDictionary.getOreName(id));
        }
    }
}
