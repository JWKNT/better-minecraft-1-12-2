package better112.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;

/**
 * One row of the settings screen: a value bound to wherever it is stored, held as a pending
 * edit until the player presses Apply.
 */
public final class Option<T> {

    /** How much a setting costs or saves, shown under its description. */
    public enum Impact {
        LOW(TextFormatting.GREEN), MEDIUM(TextFormatting.YELLOW), HIGH(TextFormatting.GOLD), VARIES(TextFormatting.WHITE);

        public final TextFormatting color;

        Impact(TextFormatting color) {
            this.color = color;
        }

        public String label() {
            return I18n.format("better112.impact." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** What has to happen after a changed option is applied. */
    public static final int RELOAD_CHUNKS = 1, RELOAD_ASSETS = 2, RESIZE = 4, VANILLA = 8, BETTER = 16,
            RELOAD_DISPATCHER = 32, MIPMAPS = 64;

    public final String id;
    public final Control<T> control;
    private final Supplier<T> getter;
    private final Consumer<T> setter;
    private Impact impact;
    private int flags;
    private BooleanSupplier available;
    private Supplier<String> unavailableReason;
    private Supplier<String> note;

    private T value;
    private T stored;

    public Option(String id, Control<T> control, Supplier<T> getter, Consumer<T> setter) {
        this.id = id;
        this.control = control;
        this.getter = getter;
        this.setter = setter;
        reset();
    }

    public Option<T> impact(Impact impact) {
        this.impact = impact;
        return this;
    }

    public Option<T> flags(int flags) {
        this.flags |= flags;
        return this;
    }

    /** The option is greyed out, with the reason shown, while the test is false. */
    public Option<T> availableWhen(BooleanSupplier test, Supplier<String> reason) {
        this.available = test;
        this.unavailableReason = reason;
        return this;
    }

    /** An extra line under the description, such as what a feature is currently using. */
    public Option<T> note(Supplier<String> note) {
        this.note = note;
        return this;
    }

    public String note() {
        return note == null ? null : note.get();
    }

    public String name() {
        return I18n.format("better112.option." + id);
    }

    public String tooltip() {
        return I18n.format("better112.option." + id + ".tooltip");
    }

    public Impact impact() {
        return impact;
    }

    public int flags() {
        return flags;
    }

    public boolean isAvailable() {
        return available == null || available.getAsBoolean();
    }

    public String unavailableReason() {
        return unavailableReason == null ? null : unavailableReason.get();
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }

    public boolean hasChanged() {
        return !value.equals(stored);
    }

    public void reset() {
        stored = getter.get();
        value = stored;
    }

    public void apply() {
        setter.accept(value);
        stored = value;
    }
}
