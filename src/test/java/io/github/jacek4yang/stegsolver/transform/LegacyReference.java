package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.TestImages;
import java.util.Random;

/**
 * The original StegSolve algorithms, transcribed literally from the legacy sources.
 *
 * <p>These implementations are used only by tests: they are the oracle that proves the rewritten
 * transforms still produce byte for byte the same result as the tool users already know. Keeping them
 * here — rather than trusting a hand written expectation — is what makes the migration safe.</p>
 */
public final class LegacyReference {

    /** Distance between the two tests of a distance function, i.e. the legacy {@code MAXTRANS}. */
    public static final int MAX_TRANSFORMS = 41;

    private LegacyReference() {
    }

    /** Legacy {@code Transform.transfrombit(int d)}. */
    public static int[] bitPlane(ImageData image, int bit) {
        int[] in = image.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            int colour = 0;
            int pixel = in[i];
            if (((pixel >>> bit) & 1) > 0) {
                colour = 0xffffff;
            }
            out[i] = colour;
        }
        return out;
    }

    /** Legacy {@code Transform.transmask(int mask)}. */
    public static int[] mask(ImageData image, int mask) {
        int[] in = image.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            int colour = in[i] & mask;
            if (colour > 0xffffff || colour < 0) {
                colour >>>= 8;
            }
            out[i] = colour & 0xffffff;
        }
        return out;
    }

    /** Legacy {@code Transform.inversion()}. */
    public static int[] invert(ImageData image) {
        int[] in = image.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (in[i] ^ 0xffffff) & 0xffffff;
        }
        return out;
    }

    /** Legacy {@code Transform.graybits()}. */
    public static int[] grayBits(ImageData image) {
        int[] in = image.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            int colour = 0;
            int pixel = in[i];
            if ((pixel & 0xff) == ((pixel & 0xff00) >> 8) && (pixel & 0xff) == ((pixel & 0xff0000) >> 16)) {
                colour = 0xffffff;
            }
            out[i] = colour;
        }
        return out;
    }

    /** Legacy {@code Transform.random_colormap()}, with the random stream seeded explicitly. */
    public static int[] randomColourMap(ImageData image, long seed) {
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
        int[] in = image.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            int fcol = in[i];
            int b = (fcol & 0xff) * bm;
            b = ((b * bm) ^ bx) + ba;
            int g = ((fcol & 0xff00) >> 8) * gm;
            g = ((g * gm) ^ gx) + ga;
            int r = ((fcol & 0xff0000) >> 16) * rm;
            r = ((r * rm) ^ rx) + ra;
            int col = (r << 16) + (g << 8) + b + (fcol & 0xff000000);
            out[i] = col & 0xffffff;
        }
        return out;
    }

    /** Legacy {@code Transform.calcTrans()} dispatch by transform number. */
    public static int[] apply(ImageData image, int transformNumber) {
        return switch (transformNumber) {
            case 1 -> invert(image);
            case 2, 3, 4, 5, 6, 7, 8, 9 -> bitPlane(image, 31 - (transformNumber - 2));
            case 10, 11, 12, 13, 14, 15, 16, 17 -> bitPlane(image, 23 - (transformNumber - 10));
            case 18, 19, 20, 21, 22, 23, 24, 25 -> bitPlane(image, 15 - (transformNumber - 18));
            case 26, 27, 28, 29, 30, 31, 32, 33 -> bitPlane(image, 7 - (transformNumber - 26));
            case 34 -> mask(image, 0xff000000);
            case 35 -> mask(image, 0x00ff0000);
            case 36 -> mask(image, 0x0000ff00);
            case 37 -> mask(image, 0x000000ff);
            case 41 -> grayBits(image);
            default -> image.pixels();
        };
    }

    /** Legacy {@code StereoTransform.calcTrans()}. */
    public static int[] stereo(ImageData image, int offset) {
        int width = image.width();
        int height = image.height();
        int[] in = image.pixels();
        int[] out = new int[in.length];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                int pixel = in[j * width + i];
                int neighbour = in[j * width + (i + offset) % width];
                out[j * width + i] = (pixel ^ (neighbour & 0x00ffffff)) & 0xffffff;
            }
        }
        return out;
    }

    /** Legacy {@code CombineTransform.comb(int, int)}. */
    public static int combinePixels(int mode, int c1, int c2) {
        return switch (mode) {
            case 0 -> (c1 ^ c2) & 0xffffff;
            case 1 -> (c1 | c2) & 0xffffff;
            case 2 -> (c1 & c2) & 0xffffff;
            case 3 -> (c1 + c2) & 0xffffff;
            case 4 -> (((c1 & 0xff0000) + (c2 & 0xff0000)) & 0xff0000
                    | ((c1 & 0xff00) + (c2 & 0xff00)) & 0xff00
                    | ((c1 & 0xff) + (c2 & 0xff)) & 0xff) & 0xffffff;
            case 5 -> (c1 - c2) & 0xffffff;
            case 6 -> (((c1 & 0xff0000) - (c2 & 0xff0000)) & 0xff0000
                    | ((c1 & 0xff00) - (c2 & 0xff00)) & 0xff00
                    | ((c1 & 0xff) - (c2 & 0xff)) & 0xff) & 0xffffff;
            case 7 -> (c1 * c2) & 0xffffff;
            case 8 -> ((((((c1 & 0xff0000) >> 16) * ((c2 & 0xff0000) >> 16)) & 0xff) << 16)
                    | (((((c1 & 0xff00) >> 8) * ((c2 & 0xff00) >> 8)) & 0xff) << 8)
                    | ((c1 & 0xff) * (c2 & 0xff)) & 0xff) & 0xffffff;
            case 9 -> {
                int r = (c1 & 0xff0000) > (c2 & 0xff0000) ? (c1 & 0xff0000) : (c2 & 0xff0000);
                int g = (c1 & 0xff00) > (c2 & 0xff00) ? (c1 & 0xff00) : (c2 & 0xff00);
                int b = (c1 & 0xff) > (c2 & 0xff) ? (c1 & 0xff) : (c2 & 0xff);
                yield (r | g | b) & 0xffffff;
            }
            case 10 -> {
                int r = (c1 & 0xff0000) < (c2 & 0xff0000) ? (c1 & 0xff0000) : (c2 & 0xff0000);
                int g = (c1 & 0xff00) < (c2 & 0xff00) ? (c1 & 0xff00) : (c2 & 0xff00);
                int b = (c1 & 0xff) < (c2 & 0xff) ? (c1 & 0xff) : (c2 & 0xff);
                yield (r | g | b) & 0xffffff;
            }
            default -> 0;
        };
    }

    /** Legacy {@code Extract} bit packing: alpha first, then the colour order, MSB first output. */
    public static byte[] extract(ImageData image, boolean[][] selected, String rgbOrder, boolean lsbFirst,
            boolean rowFirst) {
        Channel[] channels = {Channel.ALPHA, Channel.RED, Channel.GREEN, Channel.BLUE};
        int accumulatedMask = 0;
        int maskbits = 0;
        for (int c = 0; c < channels.length; c++) {
            for (int plane = 0; plane < 8; plane++) {
                if (selected[c][plane]) {
                    accumulatedMask += 1 << (channels[c].shift() + plane);
                    maskbits++;
                }
            }
        }
        final int mask = accumulatedMask;
        final int width = image.width();
        final int height = image.height();
        long len = (long) width * height * maskbits;
        byte[] extract = new byte[(int) ((len + 7) / 8)];
        int[] bitPos = {128};
        int[] bytePos = {0};

        int[] order = switch (rgbOrder) {
            case "rgb" -> new int[] {1 << 16, 1 << 8, 1};
            case "rbg" -> new int[] {1 << 16, 1, 1 << 8};
            case "grb" -> new int[] {1 << 8, 1 << 16, 1};
            case "gbr" -> new int[] {1 << 8, 1, 1 << 16};
            case "brg" -> new int[] {1, 1 << 16, 1 << 8};
            default -> new int[] {1, 1 << 8, 1 << 16};
        };

        java.util.function.IntConsumer pixelConsumer = pixel -> {
            extract8(pixel, lsbFirst ? 1 << 24 : 1 << 31, mask, lsbFirst, extract, bitPos, bytePos);
            for (int base : order) {
                // The MSB-first start position of a channel is seven bits above its LSB-first start.
                extract8(pixel, lsbFirst ? base : base << 7, mask, lsbFirst, extract, bitPos, bytePos);
            }
        };

        if (rowFirst) {
            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    pixelConsumer.accept(image.pixelAt(i, j));
                }
            }
        } else {
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < height; j++) {
                    pixelConsumer.accept(image.pixelAt(i, j));
                }
            }
        }
        return extract;
    }

    private static void extract8(int nextByte, int bitMask, int mask, boolean lsbFirst, byte[] extract,
            int[] bitPos, int[] bytePos) {
        for (int i = 0; i < 8; i++) {
            if ((mask & bitMask) != 0) {
                addBit(nextByte & bitMask, extract, bitPos, bytePos);
            }
            if (lsbFirst) {
                bitMask <<= 1;
            } else {
                bitMask >>>= 1;
            }
        }
    }

    private static void addBit(int num, byte[] extract, int[] bitPos, int[] bytePos) {
        if (num != 0) {
            extract[bytePos[0]] += bitPos[0];
        }
        bitPos[0] >>= 1;
        if (bitPos[0] >= 1) {
            return;
        }
        bitPos[0] = 128;
        bytePos[0]++;
        if (bytePos[0] < extract.length) {
            extract[bytePos[0]] = 0;
        }
    }

    /** Convenience used by the tests to build a selection array. */
    public static boolean[][] selection(int... channelPlanePairs) {
        boolean[][] selected = new boolean[4][8];
        for (int pair : channelPlanePairs) {
            selected[pair / 10][pair % 10] = true;
        }
        return selected;
    }

    /** A random image with varied alpha, used as the input of the parity tests. */
    public static ImageData fuzzImage(int width, int height, long seed) {
        return TestImages.randomArgb(width, height, seed);
    }
}
