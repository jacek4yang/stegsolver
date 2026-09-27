package io.github.jacek4yang.stegsolver.selfcheck;

import io.github.jacek4yang.stegsolver.core.ImageData;
import java.util.Random;

/**
 * A generated image used when the self test runs without a file.
 *
 * <p>It contains everything the engine likes to stumble over: an alpha channel, a repeating pattern with
 * a known period so that the stereogram solver has something to find, and a payload hidden in the least
 * significant bits so that the extraction and the payload detector have something to report.</p>
 */
public final class SyntheticImage {

    private static final int WIDTH = 512;
    private static final int HEIGHT = 384;
    /** The period of the repeating pattern, which the stereogram solver should discover. */
    public static final int PATTERN_PERIOD = 48;
    private static final String HIDDEN_MESSAGE = "StegSolver self test payload";

    private SyntheticImage() {
    }

    /** Creates the image. */
    public static ImageData create() {
        int[] pixels = new int[WIDTH * HEIGHT];
        Random random = new Random(20250101L);
        // Per column noise keeps the texture interesting while keeping the image exactly periodic in x,
        // which is what the stereogram solver has to find.
        int[] columnNoise = new int[PATTERN_PERIOD];
        for (int i = 0; i < columnNoise.length; i++) {
            columnNoise[i] = random.nextInt(8);
        }
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int base = ((x % PATTERN_PERIOD) * 5 + (y / 8) * 3) & 0xff;
                int noise = columnNoise[x % PATTERN_PERIOD];
                int red = (base + noise) & 0xff;
                int green = ((base * 2) + noise) & 0xff;
                int blue = ((255 - base) + noise) & 0xff;
                int alpha = 255 - ((x + y) % 16);
                pixels[y * WIDTH + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        embedMessage(pixels, HIDDEN_MESSAGE.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return ImageData.of(WIDTH, HEIGHT, pixels, true);
    }

    /** The message hidden in the red channel's least significant bits. */
    public static String hiddenMessage() {
        return HIDDEN_MESSAGE;
    }

    private static void embedMessage(int[] pixels, byte[] message) {
        int bitIndex = 0;
        for (byte value : message) {
            for (int shift = 7; shift >= 0; shift--) {
                int bit = (value >> shift) & 1;
                pixels[bitIndex] = (pixels[bitIndex] & 0xfffeffff) | (bit << 16);
                bitIndex++;
            }
        }
    }
}
