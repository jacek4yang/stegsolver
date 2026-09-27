package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.ImageData;

/**
 * Stereogram solver: XORs the image with a horizontally shifted copy of itself, which reveals the
 * depth map hidden in an autostereogram once the shift matches the repeating pattern width.
 *
 * <p>This is the original StegSolve transform: {@code out(x, y) = in(x, y) XOR in((x + offset) mod w, y)}.
 * The pixel is wrapped around the right edge for the last columns, which is what the original did;
 * {@link #withEdgeHold} is provided as an alternative used when wrap around artefacts are
 * distracting.</p>
 */
public final class StereoTransform {

    private StereoTransform() {
    }

    /** XORs the image with a copy of itself shifted by {@code offset} pixels, wrapping at the right edge. */
    public static int[] shiftedXor(ImageData source, int offset) {
        int width = source.width();
        int height = source.height();
        int shift = normalizeOffset(offset, width);
        int[] in = source.pixels();
        int[] out = new int[in.length];
        if (shift == 0) {
            // in XOR in == 0 everywhere
            return out;
        }
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            int split = width - shift;
            for (int x = 0; x < split; x++) {
                out[rowStart + x] = (in[rowStart + x] ^ in[rowStart + x + shift]) & 0xffffff;
            }
            for (int x = split; x < width; x++) {
                out[rowStart + x] = (in[rowStart + x] ^ in[(rowStart + x + shift) - width]) & 0xffffff;
            }
        }
        return out;
    }

    /**
     * Like {@link #shiftedXor} but clamps the sample position at the right edge instead of wrapping,
     * which removes the wrap around seam on the last {@code offset} columns.
     */
    public static int[] withEdgeHold(ImageData source, int offset) {
        int width = source.width();
        int height = source.height();
        int shift = normalizeOffset(offset, width);
        int[] in = source.pixels();
        int[] out = new int[in.length];
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                int sampleX = x + shift;
                if (sampleX >= width) {
                    sampleX = width - 1;
                }
                out[rowStart + x] = (in[rowStart + x] ^ in[rowStart + sampleX]) & 0xffffff;
            }
        }
        return out;
    }

    /** Brings an offset into {@code [0, width - 1]}. */
    public static int normalizeOffset(int offset, int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        int value = offset % width;
        if (value < 0) {
            value += width;
        }
        return value;
    }

    /** Next offset, wrapping around, as the legacy ">" button did. */
    public static int nextOffset(int offset, int width) {
        return (normalizeOffset(offset, width) + 1) % width;
    }

    /** Previous offset, wrapping around, as the legacy "<" button did. */
    public static int previousOffset(int offset, int width) {
        int value = normalizeOffset(offset, width) - 1;
        return value < 0 ? width - 1 : value;
    }
}
