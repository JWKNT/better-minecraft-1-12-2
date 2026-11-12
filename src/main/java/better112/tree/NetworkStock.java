package better112.tree;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import better112.Better112;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;

/**
 * What an Applied Energistics 2 network holds, read from an open ME terminal, so a recipe
 * tree opened from the terminal can say "you have 1,200 of these in storage" and "your
 * network can craft this".
 *
 * AE2 is not compiled against. The terminal screen keeps the item list the server sent it
 * in a field of type ItemRepo; everything here is found by name at runtime, and anything
 * that is missing just means no network numbers are shown. Works with AE2 rv6 and the
 * Extended Life fork, and with terminals other mods build on top of AE2's.
 */
final class NetworkStock {

    final Map<String, Long> amounts = new HashMap<String, Long>();
    final Set<String> craftable = new HashSet<String>();

    private NetworkStock() {}

    /** The stock behind the given screen, or null if it is not an ME terminal. */
    static NetworkStock read(GuiScreen screen) {
        if (screen == null) {
            return null;
        }
        try {
            Object repo = null;
            for (Class<?> type = screen.getClass(); type != null && type != GuiScreen.class && repo == null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().getName().endsWith(".ItemRepo")) {
                        field.setAccessible(true);
                        repo = field.get(screen);
                        break;
                    }
                }
            }
            if (repo == null) {
                return null;
            }
            Object list = repo.getClass().getMethod("getList").invoke(repo);
            if (!(list instanceof Iterable)) {
                return null;
            }
            NetworkStock stock = new NetworkStock();
            Method definition = null, size = null, craft = null;
            for (Object entry : (Iterable<?>) list) {
                if (entry == null) {
                    continue;
                }
                if (definition == null) {
                    definition = entry.getClass().getMethod("getDefinition");
                    size = entry.getClass().getMethod("getStackSize");
                    craft = entry.getClass().getMethod("isCraftable");
                }
                Ing ing = Ing.of((ItemStack) definition.invoke(entry));
                if (ing == null) {
                    continue;
                }
                long count = (Long) size.invoke(entry);
                if (count > 0) {
                    Long old = stock.amounts.get(ing.key);
                    stock.amounts.put(ing.key, (old == null ? 0L : old) + count);
                }
                if ((Boolean) craft.invoke(entry)) {
                    stock.craftable.add(ing.key);
                }
            }
            return stock;
        } catch (Throwable t) {
            Better112.LOG.debug("Could not read ME network stock: {}", t.toString());
            return null;
        }
    }
}
