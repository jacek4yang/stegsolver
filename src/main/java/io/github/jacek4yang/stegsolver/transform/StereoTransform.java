package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.ImageData;

/**
 * Stereogram solver: XORs the image with a horizontally shifted copy of itself, which reveals the
 * depth map hidden in an autostereogram once the shift matches the repeating pattern width.
 *
 * <p>This is the original StegSolve transform: {@code out(x, y) = in(x, y) XOR in((x + offset) mod w, y)}.
 * The pixel is wrapped around the right edge for the last columns, which is what the original did;
 * {@link #withEdgeHold} is provided as an alternative used when wrap around artefacts are
 * distracting. Every result pixel is fully opaque, like the legacy TYPE_INT_RGB output: where the
 * pattern matches, the result is black rather than transparent.</p>
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
            // in XOR in == 0 everywhere, i.e. an opaque black image.
            java.util.Arrays.fill(out, 0xff000000);
            return out;
        }
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            int split = width - shift;
            for (int x = 0; x < split; x++) {
                out[rowStart + x] = opaque(in[rowStart + x] ^ in[rowStart + x + shift]);
            }
            for (int x = split; x < width; x++) {
                out[rowStart + x] = opaque(in[rowStart + x] ^ in[(rowStart + x + shift) - width]);
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
                out[rowStart + x] = opaque(in[rowStart + x] ^ in[rowStart + sampleX]);
            }
        }
        return out;
    }

    /**
     * Searches for the offset with the strongest self similarity, which is the pattern width of an
     * autostereogram: at that offset a large fraction of the pixels match the pixel that far to their
     * right, so the XOR result goes almost completely black.
     *
     * <p>To stay fast on large images only every {@code sampleStep}-th row and column is compared, which
     * is more than enough to find the peak. The scan is limited to the first half of the width because a
     * repeating pattern also matches at multiples of its period, and the smallest one is the useful one.</p>
     */
    public static int bestOffset(ImageData source, int sampleStep) {
        if (source == null || source.width() < 2 || source.height() < 1) {
            return 0;
        }
        int step = Math.max(1, sampleStep);
        int width = source.width();
        int height = source.height();
        int[] pixels = source.pixels();
        int maxOffset = Math.max(1, width / 2);
        int bestOffset = 0;
        long bestMatches = -1;
        for (int offset = 1; offset <= maxOffset; offset++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            long matches = 0;
            long compared = 0;
            for (int y = 0; y < height; y += step) {
                int row = y * width;
                for (int x = 0; x + offset < width; x += step) {
                    compared++;
                    if (pixels[row + x] == pixels[row + x + offset]) {
                        matches++;
                    }
                }
            }
            if (compared == 0) {
                continue;
            }
            // Prefer the smallest offset among equals, so that the fundamental period wins.
            if (matches > bestMatches) {
                bestMatches = matches;
                bestOffset = offset;
            }
        }
        return bestOffset;
    }

    /** Makes a solved colour opaque, the way the legacy TYPE_INT_RGB output did. */
    private static int opaque(int colour) {
        return 0xff000000 | (colour & 0xffffff);
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
