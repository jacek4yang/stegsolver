package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import java.util.Random;

/**
 * The actual pixel operations of the transform catalog.
 *
 * <p>Every method takes the source pixels and returns a freshly allocated opaque ARGB array, which
 * matches the original StegSolve behaviour of writing the result into an
 * {@link java.awt.image.BufferedImage#TYPE_INT_RGB} image: the alpha channel of the source is
 * deliberately not propagated. The only exception is {@link #original}, which hands back the source
 * array itself.</p>
 *
 * <p>All loops are written over flat arrays with no per pixel object allocation, which is what makes
 * navigation between transforms instantaneous on large images.</p>
 */
public final class ImageTransforms {

    /** Seeds for the three random colour maps; deterministic so a transform can be revisited. */
    private static final int[] RANDOM_SEEDS = {0x5EED_0001, 0x5EED_0002, 0x5EED_0003};

    private ImageTransforms() {
    }

    /** The untouched source pixels (not a copy — callers must not modify the result). */
    public static int[] original(ImageData source) {
        return source.pixels();
    }

    /** {@code pixel XOR 0xFFFFFF}, i.e. the legacy "inversion" transform. */
    public static int[] invert(ImageData source) {
        int[] in = source.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (in[i] ^ 0xffffff) & 0xffffff;
        }
        return out;
    }

    /** A single bit plane of one channel, rendered as black (0) or white (0xFFFFFF). */
    public static int[] bitPlane(ImageData source, Channel channel, int plane) {
        int[] in = source.pixels();
        int[] out = new int[in.length];
        int shift = channel.shift() + plane;
        for (int i = 0; i < in.length; i++) {
            out[i] = ((in[i] >>> shift) & 1) != 0 ? 0xffffff : 0;
        }
        return out;
    }

    /**
     * The classic StegSolve bit plane renderer, addressed by the raw ARGB bit position.
     *
     * @param shift ARGB bit index, {@code 0} (blue bit 0) to {@code 31} (alpha bit 7)
     */
    public static int[] bitPlaneByArgbBit(ImageData source, int shift) {
        if (shift < 0 || shift > 31) {
            throw new IllegalArgumentException("Bit position out of range: " + shift);
        }
        int[] in = source.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = ((in[i] >>> shift) & 1) != 0 ? 0xffffff : 0;
        }
        return out;
    }

    /**
     * Masks the image with a raw ARGB mask, reproducing the legacy transform exactly, including its
     * quirk of shifting the result right by eight bits when the mask reaches into the alpha byte (so
     * "full alpha" renders as the red channel).
     */
    public static int[] mask(ImageData source, int mask) {
        int[] in = source.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            int value = in[i] & mask;
            if (value > 0xffffff || value < 0) {
                value >>>= 8;
            }
            out[i] = value & 0xffffff;
        }
        return out;
    }

    /** White where {@code r == g == b}, black everywhere else (the legacy "gray bits" transform). */
    public static int[] grayPixels(ImageData source) {
        int[] in = source.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            int pixel = in[i];
            int blue = pixel & 0xff;
            int green = (pixel >>> 8) & 0xff;
            int red = (pixel >>> 16) & 0xff;
            out[i] = (blue == green && blue == red) ? 0xffffff : 0;
        }
        return out;
    }

    /**
     * Applies the random colour map appropriate for the source: palette based for indexed images,
     * straight per component mapping otherwise.
     *
     * @param variant 1, 2 or 3 — each variant uses a different but fixed seed
     */
    public static int[] randomMap(ImageData source, int variant) {
        int seed = seedFor(variant);
        if (source.isIndexed()) {
            return randomPaletteMap(source, seed);
        }
        return randomComponentMap(source, seed);
    }

    /** Random per component map, matching the legacy {@code random_colormap} arithmetic. */
    public static int[] randomComponentMap(ImageData source, int seed) {
        Random random = new Random(seed);
        int bm = random.nextInt(256);
        int ba = random.nextInt(256);
        int bx = random.nextInt(256);
        int gm = random.nextInt(256);
        int ga = random.nextInt(256);
        int gx = random.nextInt(256);
        int rm = random.nextInt(256);
        int ra = random.nextInt(256);
        int rx = random.nextInt(256);

        int[] in = source.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            int pixel = in[i];
            int blue = (pixel & 0xff) * bm;
            blue = ((blue * bm) ^ bx) + ba;
            int green = ((pixel & 0xff00) >>> 8) * gm;
            green = ((green * gm) ^ gx) + ga;
            int red = ((pixel & 0xff0000) >>> 16) * rm;
            red = ((red * rm) ^ rx) + ra;
            int colour = (red << 16) + (green << 8) + blue + (pixel & 0xff000000);
            out[i] = colour & 0xffffff;
        }
        return out;
    }

    /**
     * Random palette map, matching the legacy {@code random_indexmap}: the mapping is applied to the
     * palette, so two pixels with the same colour but different palette indices can end up with
     * different colours, which is the whole point of this transform.
     */
    public static int[] randomPaletteMap(ImageData source, int seed) {
        int[] palette = source.palette();
        int[] indices = source.indices();
        if (palette == null || palette.length == 0 || indices == null) {
            return randomComponentMap(source, seed);
        }
        Random random = new Random(seed);
        int[] mapped = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            int red = random.nextInt(256);
            int green = random.nextInt(256);
            int blue = random.nextInt(256);
            mapped[i] = (red << 16) | (green << 8) | blue;
        }
        int[] out = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index < 0) {
                index = 0;
            } else if (index >= mapped.length) {
                index = mapped.length - 1;
            }
            out[i] = mapped[index];
        }
        return out;
    }

    public static int seedFor(int variant) {
        if (variant < 1 || variant > RANDOM_SEEDS.length) {
            throw new IllegalArgumentException("Unknown random colour map variant: " + variant);
        }
        return RANDOM_SEEDS[variant - 1];
    }

    /** Applies a catalog entry; used by {@link TransformEngine} and by tests. */
    public static int[] apply(TransformDef def, ImageData source) {
        return switch (def.kind()) {
            case ORIGINAL -> original(source);
            case INVERT -> invert(source);
            case PLANE -> bitPlane(source, def.channel(), def.plane());
            case FULL_CHANNEL -> mask(source, def.channelMask());
            // Catalog order: 38, 39, 40 are random colour maps 1, 2 and 3.
            case RANDOM_MAP -> randomMap(source, def.index() - 37);
            case GRAY_BITS -> grayPixels(source);
        };
    }
}
