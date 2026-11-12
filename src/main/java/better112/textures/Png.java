package better112.textures;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A small PNG decoder that always yields plain 8-bit ARGB.
 *
 * Modern Minecraft ships most textures as palette or greyscale PNGs. Java's own reader
 * brightens greyscale ones and, on Java 8, drops some transparency chunks, which is why
 * packs made for new versions can look washed out in 1.12.2. Decoding by hand avoids both.
 */
final class Png {

    private Png() {}

    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    /** Width and height from the header, without decoding. */
    static int[] size(byte[] data) throws IOException {
        if (data.length < 24) {
            throw new IOException("not a PNG");
        }
        for (int i = 0; i < 8; i++) {
            if (data[i] != SIGNATURE[i]) {
                throw new IOException("not a PNG");
            }
        }
        return new int[] {readInt(data, 16), readInt(data, 20)};
    }

    static BufferedImage decode(byte[] data) throws IOException {
        size(data);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 8, data.length - 8));
        int width = 0, height = 0, depth = 0, colorType = 0, interlace = 0;
        byte[] palette = null, transparency = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream(data.length);
        while (true) {
            int length = in.readInt();
            int type = in.readInt();
            byte[] chunk = new byte[length];
            in.readFully(chunk);
            in.readInt(); // CRC
            if (type == 0x49484452) { // IHDR
                width = readInt(chunk, 0);
                height = readInt(chunk, 4);
                depth = chunk[8] & 0xFF;
                colorType = chunk[9] & 0xFF;
                interlace = chunk[12] & 0xFF;
            } else if (type == 0x504C5445) { // PLTE
                palette = chunk;
            } else if (type == 0x74524E53) { // tRNS
                transparency = chunk;
            } else if (type == 0x49444154) { // IDAT
                idat.write(chunk);
            } else if (type == 0x49454E44) { // IEND
                break;
            }
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("unsupported PNG layout");
        }
        if (interlace != 0) {
            return viaImageIO(data);
        }
        int channels;
        switch (colorType) {
            case 0:
                channels = 1;
                break;
            case 2:
                channels = 3;
                break;
            case 3:
                channels = 1;
                break;
            case 4:
                channels = 2;
                break;
            case 6:
                channels = 4;
                break;
            default:
                throw new IOException("unknown PNG colour type " + colorType);
        }
        int bitsPerPixel = channels * depth;
        int bytesPerPixel = Math.max(1, bitsPerPixel / 8);
        int stride = (width * bitsPerPixel + 7) / 8;
        byte[] raw = new byte[(stride + 1) * height];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(idat.toByteArray());
            int off = 0;
            while (off < raw.length && !inflater.finished()) {
                int n = inflater.inflate(raw, off, raw.length - off);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                off += n;
            }
            if (off < raw.length) {
                throw new IOException("truncated PNG data");
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }

        // Undo the per-row filters in place.
        byte[] pixels = new byte[stride * height];
        for (int y = 0; y < height; y++) {
            int filter = raw[y * (stride + 1)] & 0xFF;
            int src = y * (stride + 1) + 1;
            int dst = y * stride;
            for (int x = 0; x < stride; x++) {
                int a = x >= bytesPerPixel ? pixels[dst + x - bytesPerPixel] & 0xFF : 0;
                int b = y > 0 ? pixels[dst + x - stride] & 0xFF : 0;
                int c = x >= bytesPerPixel && y > 0 ? pixels[dst + x - stride - bytesPerPixel] & 0xFF : 0;
                int value = raw[src + x] & 0xFF;
                switch (filter) {
                    case 1:
                        value += a;
                        break;
                    case 2:
                        value += b;
                        break;
                    case 3:
                        value += (a + b) >> 1;
                        break;
                    case 4:
                        int p = a + b - c;
                        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                        value += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                        break;
                    default:
                        break;
                }
                pixels[dst + x] = (byte) value;
            }
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[width * height];
        int maxSample = (1 << depth) - 1;
        for (int y = 0; y < height; y++) {
            int row = y * stride;
            for (int x = 0; x < width; x++) {
                int r, g, b, a = 255;
                switch (colorType) {
                    case 0: {
                        int v = sample(pixels, row, x, depth);
                        if (transparency != null && transparency.length >= 2
                                && v == (((transparency[0] & 0xFF) << 8) | (transparency[1] & 0xFF))) {
                            a = 0;
                        }
                        r = g = b = depth == 16 ? v >> 8 : v * 255 / maxSample;
                        break;
                    }
                    case 2: {
                        int step = depth / 8;
                        int o = row + x * 3 * step;
                        r = pixels[o] & 0xFF;
                        g = pixels[o + step] & 0xFF;
                        b = pixels[o + 2 * step] & 0xFF;
                        if (transparency != null && transparency.length >= 6 && depth == 8
                                && r == (transparency[1] & 0xFF) && g == (transparency[3] & 0xFF)
                                && b == (transparency[5] & 0xFF)) {
                            a = 0;
                        }
                        break;
                    }
                    case 3: {
                        int index = sample(pixels, row, x, depth);
                        if (palette == null || index * 3 + 2 >= palette.length) {
                            r = g = b = 0;
                        } else {
                            r = palette[index * 3] & 0xFF;
                            g = palette[index * 3 + 1] & 0xFF;
                            b = palette[index * 3 + 2] & 0xFF;
                        }
                        if (transparency != null && index < transparency.length) {
                            a = transparency[index] & 0xFF;
                        }
                        break;
                    }
                    case 4: {
                        int step = depth / 8;
                        int o = row + x * 2 * step;
                        r = g = b = pixels[o] & 0xFF;
                        a = pixels[o + step] & 0xFF;
                        break;
                    }
                    default: {
                        int step = depth / 8;
                        int o = row + x * 4 * step;
                        r = pixels[o] & 0xFF;
                        g = pixels[o + step] & 0xFF;
                        b = pixels[o + 2 * step] & 0xFF;
                        a = pixels[o + 3 * step] & 0xFF;
                        break;
                    }
                }
                argb[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    /**
     * Interlaced files are rare, so they go through Java's reader, with greyscale read sample
     * by sample to keep it from brightening them.
     */
    private static BufferedImage viaImageIO(byte[] data) throws IOException {
        BufferedImage source = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
        if (source == null) {
            throw new IOException("unreadable PNG");
        }
        int width = source.getWidth(), height = source.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.image.ColorModel model = source.getColorModel();
        boolean grey = model.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY
                && !(model instanceof java.awt.image.IndexColorModel);
        if (grey) {
            java.awt.image.Raster raster = source.getRaster();
            int bands = raster.getNumBands();
            int max = (1 << model.getComponentSize(0)) - 1;
            int alphaMax = bands > 1 ? (1 << model.getComponentSize(1)) - 1 : 255;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int v = raster.getSample(x, y, 0) * 255 / max;
                    int a = bands > 1 ? raster.getSample(x, y, 1) * 255 / alphaMax : 255;
                    image.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                }
            }
        } else {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, source.getRGB(x, y));
                }
            }
        }
        return image;
    }

    /** One packed sample (1, 2, 4, 8 or 16 bits) from a row. */
    private static int sample(byte[] pixels, int row, int x, int depth) {
        if (depth == 8) {
            return pixels[row + x] & 0xFF;
        }
        if (depth == 16) {
            return ((pixels[row + x * 2] & 0xFF) << 8) | (pixels[row + x * 2 + 1] & 0xFF);
        }
        int bit = x * depth;
        int shift = 8 - depth - (bit & 7);
        return ((pixels[row + (bit >> 3)] & 0xFF) >> shift) & ((1 << depth) - 1);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
