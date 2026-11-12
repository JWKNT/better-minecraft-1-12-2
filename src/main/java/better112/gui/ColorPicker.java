package better112.gui;

import java.awt.Color;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * The colour picker that opens under a colour row of the settings screen: a field of
 * saturation and brightness, a hue bar, and the colour as hex, which can be typed or pasted.
 */
final class ColorPicker {

    static final int WIDTH = 132, HEIGHT = 120;
    private static final int PAD = 6, FIELD_W = 120, FIELD_H = 64, HUE_H = 8;

    final Option<Integer> option;
    final int x, y;
    private float hue, saturation, brightness;
    /** Hex digits being typed, or null while the field shows the current colour. */
    private String typing;
    private int dragging; // 0 none, 1 the field, 2 the hue bar

    ColorPicker(Option<Integer> option, int x, int y) {
        this.option = option;
        this.x = x;
        this.y = y;
        read();
    }

    private void read() {
        int rgb = option.get();
        float[] hsb = Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, null);
        // Grey and black have no hue of their own; keep the one the bar shows.
        if (hsb[1] > 0.0F && hsb[2] > 0.0F) {
            hue = hsb[0];
        }
        saturation = hsb[1];
        brightness = hsb[2];
    }

    private void write() {
        option.set(Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF);
    }

    boolean contains(int mx, int my) {
        return mx >= x && my >= y && mx < x + WIDTH && my < y + HEIGHT;
    }

    void draw(FontRenderer font, int mouseX, int mouseY) {
        Gui.drawRect(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF94E4D3);
        Gui.drawRect(x, y, x + WIDTH, y + HEIGHT, 0xF0101014);
        int fx = x + PAD, fy = y + PAD;
        // Saturation left to right, brightness top to bottom: one strip per two pixels, fading to black.
        for (int i = 0; i < FIELD_W; i += 2) {
            int top = 0xFF000000 | (Color.HSBtoRGB(hue, (i + 1) / (float) FIELD_W, 1.0F) & 0xFFFFFF);
            gradient(fx + i, fy, fx + i + 2, fy + FIELD_H, top, top, 0xFF000000, 0xFF000000);
        }
        int cx = fx + Math.round(saturation * (FIELD_W - 1)), cy = fy + Math.round((1.0F - brightness) * (FIELD_H - 1));
        Gui.drawRect(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF000000);
        Gui.drawRect(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

        int hy = fy + FIELD_H + 4;
        for (int i = 0; i < FIELD_W; i += 2) {
            int c = 0xFF000000 | (Color.HSBtoRGB((i + 1) / (float) FIELD_W, 1.0F, 1.0F) & 0xFFFFFF);
            Gui.drawRect(fx + i, hy, fx + i + 2, hy + HUE_H, c);
        }
        int hx = fx + Math.round(hue * (FIELD_W - 1));
        Gui.drawRect(hx - 1, hy - 2, hx + 2, hy + HUE_H + 2, 0xFFFFFFFF);
        Gui.drawRect(hx, hy - 1, hx + 1, hy + HUE_H + 1, 0xFF000000);

        int ty = hy + HUE_H + 6;
        int rgb = option.get() & 0xFFFFFF;
        Gui.drawRect(fx, ty - 1, fx + 14, ty + 10, 0xFFFFFFFF);
        Gui.drawRect(fx + 1, ty, fx + 13, ty + 9, 0xFF000000 | rgb);
        String hex = typing != null ? "#" + typing + (System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "") : String.format("#%06X", rgb);
        font.drawStringWithShadow(hex, fx + 19, ty + 1, 0xFFFFFFFF);
        font.drawStringWithShadow(I18n.format("better112.picker.hint"), fx, ty + 14, 0xFF8A8A8A);
    }

    private static void gradient(int left, int top, int right, int bottom, int topLeft, int topRight, int bottomLeft, int bottomRight) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertex(buffer, right, top, topRight);
        vertex(buffer, left, top, topLeft);
        vertex(buffer, left, bottom, bottomLeft);
        vertex(buffer, right, bottom, bottomRight);
        tessellator.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private static void vertex(BufferBuilder buffer, int x, int y, int argb) {
        buffer.pos(x, y, 0.0D).color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24).endVertex();
    }

    /** Pressing inside the picker. */
    void press(int mx, int my) {
        int fx = x + PAD, fy = y + PAD, hy = fy + FIELD_H + 4;
        if (mx >= fx && mx < fx + FIELD_W && my >= fy && my < fy + FIELD_H) {
            dragging = 1;
        } else if (mx >= fx && mx < fx + FIELD_W && my >= hy - 2 && my < hy + HUE_H + 2) {
            dragging = 2;
        } else {
            dragging = 0;
        }
        drag(mx, my);
    }

    void drag(int mx, int my) {
        int fx = x + PAD, fy = y + PAD;
        if (dragging == 1) {
            saturation = clamp((mx - fx) / (float) (FIELD_W - 1));
            brightness = 1.0F - clamp((my - fy) / (float) (FIELD_H - 1));
        } else if (dragging == 2) {
            hue = clamp((mx - fx) / (float) (FIELD_W - 1)) * 0.9999F;
        } else {
            return;
        }
        typing = null;
        write();
    }

    void release() {
        dragging = 0;
    }

    private static float clamp(float v) {
        return v < 0.0F ? 0.0F : v > 1.0F ? 1.0F : v;
    }

    /** A key while the picker is open. Returns true when the picker should close. */
    boolean key(char typed, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            return true;
        }
        if (GuiScreen.isKeyComboCtrlV(keyCode)) {
            String clip = GuiScreen.getClipboardString().trim();
            if (clip.startsWith("#")) {
                clip = clip.substring(1);
            }
            if (clip.matches("[0-9a-fA-F]{6}")) {
                setHex(clip);
            }
            return false;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (typing == null) {
                typing = String.format("%06X", option.get() & 0xFFFFFF);
            }
            if (!typing.isEmpty()) {
                typing = typing.substring(0, typing.length() - 1);
            }
            return false;
        }
        if (Character.digit(typed, 16) >= 0) {
            typing = (typing == null || typing.length() >= 6 ? "" : typing) + Character.toUpperCase(typed);
            if (typing.length() == 6) {
                setHex(typing);
            }
        }
        return false;
    }

    void setHex(String hex) {
        option.set(Integer.parseInt(hex, 16));
        typing = null;
        read();
    }
}
