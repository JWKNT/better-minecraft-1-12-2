package better112.gui;

import java.util.function.IntFunction;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;

/** The widget on the right-hand side of an option row. */
public abstract class Control<T> {

    static final int ACCENT = 0xFF94E4D3;
    static final int WHITE = 0xFFFFFFFF;
    static final int DISABLED = 0xFFAAAAAA;

    /** Draws the widget inside the row at (x, y, width, height). */
    abstract void render(Option<T> option, FontRenderer font, int x, int y, int width, int height, boolean hovered, boolean dragging);

    /** Width the widget takes from the right edge of the row, so the name can be trimmed to fit. */
    abstract int reserved(Option<T> option, FontRenderer font, boolean active);

    /** Mouse pressed on the row. Returns true if this starts a drag. */
    abstract boolean click(Option<T> option, int x, int width, int mouseX);

    void drag(Option<T> option, int x, int width, int mouseX) {}

    void scroll(Option<T> option, int direction) {}

    /** A checkbox. */
    public static final class Tick extends Control<Boolean> {
        @Override
        void render(Option<Boolean> option, FontRenderer font, int x, int y, int width, int height, boolean hovered, boolean dragging) {
            int bx = x + width - 16, by = y + height / 2 - 5;
            boolean ticked = option.get();
            int color = !option.isAvailable() ? DISABLED : ticked ? ACCENT : WHITE;
            if (ticked) {
                Gui.drawRect(bx + 2, by + 2, bx + 8, by + 8, color);
            }
            Gui.drawRect(bx, by, bx + 10, by + 1, color);
            Gui.drawRect(bx, by + 9, bx + 10, by + 10, color);
            Gui.drawRect(bx, by + 1, bx + 1, by + 9, color);
            Gui.drawRect(bx + 9, by + 1, bx + 10, by + 9, color);
        }

        @Override
        int reserved(Option<Boolean> option, FontRenderer font, boolean active) {
            return 22;
        }

        @Override
        boolean click(Option<Boolean> option, int x, int width, int mouseX) {
            option.set(!option.get());
            return false;
        }
    }

    /** Steps through a fixed list of named choices. */
    public static final class Cycle extends Control<Integer> {
        private final String[] nameKeys;

        public Cycle(String... nameKeys) {
            this.nameKeys = nameKeys;
        }

        @Override
        void render(Option<Integer> option, FontRenderer font, int x, int y, int width, int height, boolean hovered, boolean dragging) {
            int index = Math.max(0, Math.min(nameKeys.length - 1, option.get()));
            String label = I18n.format(nameKeys[index]);
            font.drawStringWithShadow(label, x + width - 6 - font.getStringWidth(label), y + height / 2 - 4,
                    option.isAvailable() ? WHITE : DISABLED);
        }

        @Override
        int reserved(Option<Integer> option, FontRenderer font, boolean active) {
            int index = Math.max(0, Math.min(nameKeys.length - 1, option.get()));
            return font.getStringWidth(I18n.format(nameKeys[index])) + 10;
        }

        @Override
        boolean click(Option<Integer> option, int x, int width, int mouseX) {
            option.set((option.get() + 1) % nameKeys.length);
            return false;
        }

        @Override
        void scroll(Option<Integer> option, int direction) {
            option.set(Math.floorMod(option.get() - direction, nameKeys.length));
        }
    }

    /** A whole-number slider. Shows just the value until the row is hovered. */
    public static final class Slider extends Control<Integer> {
        private final int min, max, step;
        private final IntFunction<String> format;

        public Slider(int min, int max, int step, IntFunction<String> format) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.format = format;
        }

        @Override
        void render(Option<Integer> option, FontRenderer font, int x, int y, int width, int height, boolean hovered, boolean dragging) {
            int sx = x + width - 96, sy = y + height / 2 - 5, sw = 90, sh = 10;
            String label = format.apply(option.get());
            int labelWidth = font.getStringWidth(label);
            if (!option.isAvailable() || !(hovered || dragging)) {
                font.drawStringWithShadow(label, sx + sw - labelWidth, sy + 1, option.isAvailable() ? WHITE : DISABLED);
                return;
            }
            double position = max == min ? 0 : (option.get() - min) / (double) (max - min);
            int thumb = sx + (int) Math.round(position * sw) - 2;
            Gui.drawRect(thumb, sy, thumb + 4, sy + sh, WHITE);
            Gui.drawRect(sx, sy + sh / 2, sx + sw, sy + sh / 2 + 1, WHITE);
            font.drawStringWithShadow(label, sx - labelWidth - 6, sy + 1, WHITE);
        }

        @Override
        int reserved(Option<Integer> option, FontRenderer font, boolean active) {
            int label = font.getStringWidth(format.apply(option.get()));
            return active && option.isAvailable() ? 96 + 6 + label + 4 : label + 10;
        }

        @Override
        boolean click(Option<Integer> option, int x, int width, int mouseX) {
            drag(option, x, width, mouseX);
            return true;
        }

        @Override
        void drag(Option<Integer> option, int x, int width, int mouseX) {
            int sx = x + width - 96, sw = 90;
            double position = Math.max(0.0D, Math.min(1.0D, (mouseX - sx) / (double) sw));
            int raw = min + (int) Math.round(position * (max - min) / step) * step;
            option.set(Math.max(min, Math.min(max, raw)));
        }

        @Override
        void scroll(Option<Integer> option, int direction) {
            option.set(Math.max(min, Math.min(max, option.get() + direction * step)));
        }
    }

    /** A colour: a swatch and its hex code. Clicking the row opens the picker (the screen does that). */
    public static final class Colour extends Control<Integer> {
        @Override
        void render(Option<Integer> option, FontRenderer font, int x, int y, int width, int height, boolean hovered, boolean dragging) {
            int rgb = option.get() & 0xFFFFFF;
            String label = String.format("#%06X", rgb);
            int right = x + width - 6;
            int tx = right - font.getStringWidth(label);
            font.drawStringWithShadow(label, tx, y + height / 2 - 4, option.isAvailable() ? WHITE : DISABLED);
            int sy = y + height / 2 - 5;
            Gui.drawRect(tx - 18, sy, tx - 4, sy + 10, option.isAvailable() ? WHITE : DISABLED);
            Gui.drawRect(tx - 17, sy + 1, tx - 5, sy + 9, 0xFF000000 | rgb);
        }

        @Override
        int reserved(Option<Integer> option, FontRenderer font, boolean active) {
            return font.getStringWidth("#000000") + 28;
        }

        @Override
        boolean click(Option<Integer> option, int x, int width, int mouseX) {
            return false;
        }
    }
}
