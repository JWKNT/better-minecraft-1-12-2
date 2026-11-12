package better112.quests;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

/** Flat shapes for the quest screen. */
final class Draw {

    private Draw() {}

    static void rect(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, bottom, color);
    }

    private static void begin(int color) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.color(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F,
                ((color >>> 24) & 0xFF) / 255.0F);
    }

    private static void end() {
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    static void circle(float cx, float cy, float radius, int color) {
        if (radius <= 0.0F) {
            return;
        }
        begin(color);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
        buffer.pos(cx, cy, 0.0D).endVertex();
        int segments = radius < 6.0F ? 16 : 28;
        for (int i = 0; i <= segments; i++) {
            double angle = Math.PI * 2.0D * i / segments;
            buffer.pos(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius, 0.0D).endVertex();
        }
        tessellator.draw();
        end();
    }

    static void line(float x1, float y1, float x2, float y2, float width, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.01F) {
            return;
        }
        float nx = -dy / length * width / 2.0F, ny = dx / length * width / 2.0F;
        begin(color);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buffer.pos(x1 + nx, y1 + ny, 0.0D).endVertex();
        buffer.pos(x2 + nx, y2 + ny, 0.0D).endVertex();
        buffer.pos(x2 - nx, y2 - ny, 0.0D).endVertex();
        buffer.pos(x1 - nx, y1 - ny, 0.0D).endVertex();
        tessellator.draw();
        end();
    }

    /** A small round marker: a tick for done, an exclamation mark for "collect your reward". */
    static void badge(float cx, float cy, float radius, int color, boolean tick) {
        circle(cx, cy, radius + 1.0F, 0xFF17171C);
        circle(cx, cy, radius, color);
        float s = radius / 3.0F;
        int dark = 0xFF17171C;
        if (tick) {
            line(cx - 1.6F * s, cy, cx - 0.4F * s, cy + 1.3F * s, s, dark);
            line(cx - 0.4F * s, cy + 1.3F * s, cx + 1.7F * s, cy - 1.3F * s, s, dark);
        } else {
            line(cx, cy - 1.8F * s, cx, cy + 0.5F * s, s, dark);
            line(cx, cy + 1.2F * s, cx, cy + 1.9F * s, s, dark);
        }
    }

    static void item(Minecraft mc, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        try {
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        } catch (Throwable ignored) {
            // an icon that cannot render should not take the quest book down
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Clips drawing to a rectangle given in GUI coordinates, at any GUI scale. */
    static void scissor(Minecraft mc, int guiWidth, int x, int y, int w, int h) {
        double scale = mc.displayWidth / (double) Math.max(1, guiWidth);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * scale), (int) (mc.displayHeight - (y + h) * scale), (int) Math.ceil(w * scale), (int) Math.ceil(h * scale));
    }
}
