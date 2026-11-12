package better112.textures;

import java.awt.image.BufferedImage;

/**
 * Chest textures were rearranged in Minecraft 1.15: every face was turned upside down, top
 * and bottom swapped, front and back swapped, and the double chest was split into a left and
 * a right half. This puts a modern chest texture back into the layout 1.12.2 draws.
 */
final class ChestLayout {

    private ChestLayout() {}

    /** A single chest (64x64). */
    static BufferedImage single(BufferedImage modern) {
        int s = Math.max(1, modern.getWidth() / 64);
        BufferedImage out = new BufferedImage(64 * s, 64 * s, BufferedImage.TYPE_INT_ARGB);
        box(out, modern, 0, 0, 14, 5, 14, s);
        box(out, modern, 0, 19, 14, 10, 14, s);
        box(out, modern, 0, 0, 2, 4, 1, s);
        return out;
    }

    /** A double chest (128x64) from the modern left and right halves (64x64 each). */
    static BufferedImage doubled(BufferedImage left, BufferedImage right) {
        int s = Math.max(1, left.getWidth() / 64);
        BufferedImage out = new BufferedImage(128 * s, 64 * s, BufferedImage.TYPE_INT_ARGB);
        doubleBox(out, left, right, 0, 0, 15, 5, 14, s);
        doubleBox(out, left, right, 0, 19, 15, 10, 14, s);
        doubleBox(out, left, right, 0, 0, 1, 4, 1, s);
        return out;
    }

    // A box of size (w, h, d) unwrapped at (u, v) puts its faces at:
    //   top (u+d, v)   bottom (u+d+w, v)                          each w x d
    //   left (u, v+d)  front (u+d, v+d)  right (u+d+w, v+d)  back (u+d+w+d, v+d)   h tall

    private static void box(BufferedImage out, BufferedImage in, int u, int v, int w, int h, int d, int s) {
        copy(out, u + d, v, in, u + d + w, v, w, d, false, s);                    // top    <- bottom, flipped
        copy(out, u + d + w, v, in, u + d, v, w, d, false, s);                    // bottom <- top, flipped
        copy(out, u, v + d, in, u, v + d, d, h, true, s);                         // left   <- left, turned
        copy(out, u + d + w, v + d, in, u + d + w, v + d, d, h, true, s);         // right  <- right, turned
        copy(out, u + d, v + d, in, u + d + w + d, v + d, w, h, true, s);         // front  <- back, turned
        copy(out, u + d + w + d, v + d, in, u + d, v + d, w, h, true, s);         // back   <- front, turned
    }

    /** {@code w} is the width of one half; the old box is twice as wide. */
    private static void doubleBox(BufferedImage out, BufferedImage left, BufferedImage right, int u, int v, int w, int h, int d, int s) {
        int full = w * 2;
        // top: left half from the right chest's bottom, right half from the left chest's bottom
        copy(out, u + d, v, right, u + d + w, v, w, d, false, s);
        copy(out, u + d + w, v, left, u + d + w, v, w, d, false, s);
        // bottom
        copy(out, u + d + full, v, right, u + d, v, w, d, false, s);
        copy(out, u + d + full + w, v, left, u + d, v, w, d, false, s);
        // the two end faces
        copy(out, u, v + d, right, u, v + d, d, h, true, s);
        copy(out, u + d + full, v + d, left, u + d + w, v + d, d, h, true, s);
        // front
        copy(out, u + d, v + d, right, u + d + w + d, v + d, w, h, true, s);
        copy(out, u + d + w, v + d, left, u + d + w + d, v + d, w, h, true, s);
        // back
        copy(out, u + d + full + d, v + d, left, u + d, v + d, w, h, true, s);
        copy(out, u + d + full + d + w, v + d, right, u + d, v + d, w, h, true, s);
    }

    /** Copies a face flipped top to bottom, and also left to right when {@code turned}. */
    private static void copy(BufferedImage out, int dx, int dy, BufferedImage in, int sx, int sy, int w, int h, boolean turned, int s) {
        dx *= s;
        dy *= s;
        sx *= s;
        sy *= s;
        w *= s;
        h *= s;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int fromX = turned ? sx + w - 1 - x : sx + x;
                int fromY = sy + h - 1 - y;
                if (fromX < in.getWidth() && fromY < in.getHeight() && dx + x < out.getWidth() && dy + y < out.getHeight()) {
                    out.setRGB(dx + x, dy + y, in.getRGB(fromX, fromY));
                }
            }
        }
    }
}
