package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.ImageData;

/**
 * The 13 image combination modes of the original StegSolve combiner, preserved bit for bit.
 *
 * <p>Modes 0..10 combine two images pixel by pixel. When the images differ in size the result is as
 * large as the bigger one and missing pixels are treated as fully transparent black, exactly as the
 * legacy implementation did. Modes 11 and 12 interlace instead and use the intersection of the two
 * sizes.</p>
 *
 * <p>As in the legacy implementation every mode produces an opaque RGB image: the alpha byte of the
 * inputs is only used where the arithmetic happens to reach into it, and the final result is masked
 * to 24 bits.</p>
 */
public enum CombineMode {

    XOR("XOR"),
    OR("OR"),
    AND("AND"),
    ADD("ADD"),
    ADD_PER_CHANNEL("ADD (R,G,B separate)"),
    SUBTRACT("SUB"),
    SUBTRACT_PER_CHANNEL("SUB (R,G,B separate)"),
    MULTIPLY("MUL"),
    MULTIPLY_PER_CHANNEL("MUL (R,G,B separate)"),
    LIGHTEST("Lightest (R,G,B separate)"),
    DARKEST("Darkest (R,G,B separate)"),
    INTERLACE_ROWS("Interlace rows"),
    INTERLACE_COLUMNS("Interlace columns");

    private final String label;

    CombineMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The transform number of the legacy combiner, which is simply the declaration order. */
    public int legacyIndex() {
        return ordinal();
    }

    public boolean isInterlace() {
        return this == INTERLACE_ROWS || this == INTERLACE_COLUMNS;
    }

    public static CombineMode byLegacyIndex(int index) {
        CombineMode[] modes = values();
        if (index < 0 || index >= modes.length) {
            throw new IndexOutOfBoundsException("No combine mode " + index);
        }
        return modes[index];
    }

    public static CombineMode next(CombineMode mode) {
        return values()[(mode.ordinal() + 1) % values().length];
    }

    public static CombineMode previous(CombineMode mode) {
        return values()[(mode.ordinal() + values().length - 1) % values().length];
    }

    /** Combines two images, returning a new opaque image. Neither input is modified. */
    public ImageData combine(ImageData first, ImageData second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("Both images are required");
        }
        return isInterlace() ? interlace(first, second) : blend(first, second);
    }

    private ImageData blend(ImageData first, ImageData second) {
        int width = Math.max(first.width(), second.width());
        int height = Math.max(first.height(), second.height());
        int[] out = new int[width * height];
        int[] a = first.pixels();
        int[] b = second.pixels();
        int aw = first.width();
        int ah = first.height();
        int bw = second.width();
        int bh = second.height();
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                int color1 = x < aw && y < ah ? a[y * aw + x] : 0;
                int color2 = x < bw && y < bh ? b[y * bw + x] : 0;
                out[rowStart + x] = combinePixels(this, color1, color2);
            }
        }
        return ImageData.opaque(width, height, out);
    }

    private ImageData interlace(ImageData first, ImageData second) {
        int width = Math.min(first.width(), second.width());
        int height = Math.min(first.height(), second.height());
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Images do not overlap: "
                    + first.width() + "x" + first.height() + " and "
                    + second.width() + "x" + second.height());
        }
        int[] a = first.pixels();
        int[] b = second.pixels();
        int aw = first.width();
        int bw = second.width();
        if (this == INTERLACE_ROWS) {
            int[] out = new int[width * 2 * height];
            for (int y = 0; y < height; y++) {
                int top = (y * 2) * width;
                int bottom = top + width;
                int aRow = y * aw;
                int bRow = y * bw;
                for (int x = 0; x < width; x++) {
                    out[top + x] = a[aRow + x] & 0xffffff;
                    out[bottom + x] = b[bRow + x] & 0xffffff;
                }
            }
            return ImageData.opaque(width, height * 2, out);
        }
        int[] out = new int[width * 2 * height];
        int outStride = width * 2;
        for (int y = 0; y < height; y++) {
            int rowStart = y * outStride;
            int aRow = y * aw;
            int bRow = y * bw;
            for (int x = 0; x < width; x++) {
                out[rowStart + x * 2] = a[aRow + x] & 0xffffff;
                out[rowStart + x * 2 + 1] = b[bRow + x] & 0xffffff;
            }
        }
        return ImageData.opaque(width * 2, height, out);
    }

    /** The per pixel arithmetic of the non interlace modes. */
    static int combinePixels(CombineMode mode, int color1, int color2) {
        return switch (mode) {
            case XOR -> (color1 ^ color2) & 0xffffff;
            case OR -> (color1 | color2) & 0xffffff;
            case AND -> (color1 & color2) & 0xffffff;
            case ADD -> (color1 + color2) & 0xffffff;
            case ADD_PER_CHANNEL -> {
                int r = ((color1 & 0xff0000) + (color2 & 0xff0000)) & 0xff0000;
                int g = ((color1 & 0xff00) + (color2 & 0xff00)) & 0xff00;
                int b = ((color1 & 0xff) + (color2 & 0xff)) & 0xff;
                yield (r | g | b) & 0xffffff;
            }
            case SUBTRACT -> (color1 - color2) & 0xffffff;
            case SUBTRACT_PER_CHANNEL -> {
                int r = ((color1 & 0xff0000) - (color2 & 0xff0000)) & 0xff0000;
                int g = ((color1 & 0xff00) - (color2 & 0xff00)) & 0xff00;
                int b = ((color1 & 0xff) - (color2 & 0xff)) & 0xff;
                yield (r | g | b) & 0xffffff;
            }
            case MULTIPLY -> (color1 * color2) & 0xffffff;
            case MULTIPLY_PER_CHANNEL -> {
                int r = ((((color1 & 0xff0000) >> 16) * ((color2 & 0xff0000) >> 16)) & 0xff) << 16;
                int g = ((((color1 & 0xff00) >> 8) * ((color2 & 0xff00) >> 8)) & 0xff) << 8;
                int b = ((color1 & 0xff) * (color2 & 0xff)) & 0xff;
                yield (r | g | b) & 0xffffff;
            }
            case LIGHTEST -> {
                int r = (color1 & 0xff0000) > (color2 & 0xff0000) ? (color1 & 0xff0000) : (color2 & 0xff0000);
                int g = (color1 & 0xff00) > (color2 & 0xff00) ? (color1 & 0xff00) : (color2 & 0xff00);
                int b = (color1 & 0xff) > (color2 & 0xff) ? (color1 & 0xff) : (color2 & 0xff);
                yield (r | g | b) & 0xffffff;
            }
            case DARKEST -> {
                int r = (color1 & 0xff0000) < (color2 & 0xff0000) ? (color1 & 0xff0000) : (color2 & 0xff0000);
                int g = (color1 & 0xff00) < (color2 & 0xff00) ? (color1 & 0xff00) : (color2 & 0xff00);
                int b = (color1 & 0xff) < (color2 & 0xff) ? (color1 & 0xff) : (color2 & 0xff);
                yield (r | g | b) & 0xffffff;
            }
            case INTERLACE_ROWS, INTERLACE_COLUMNS ->
                throw new IllegalArgumentException(mode + " is not a per pixel mode");
        };
    }
}
