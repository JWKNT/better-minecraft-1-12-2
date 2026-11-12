package better112.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.resources.I18n;

/** A tab of the settings screen: groups of options with a small gap between groups. */
public final class OptionPage {

    public final String id;
    public final List<List<Option<?>>> groups = new ArrayList<List<Option<?>>>();

    public OptionPage(String id) {
        this.id = id;
    }

    public String name() {
        return I18n.format("better112.page." + id);
    }

    public OptionPage group(Option<?>... options) {
        List<Option<?>> group = new ArrayList<Option<?>>();
        for (Option<?> option : options) {
            if (option != null) {
                group.add(option);
            }
        }
        if (!group.isEmpty()) {
            groups.add(group);
        }
        return this;
    }

    public List<Option<?>> all() {
        List<Option<?>> all = new ArrayList<Option<?>>();
        for (List<Option<?>> group : groups) {
            all.addAll(group);
        }
        return all;
    }
}
