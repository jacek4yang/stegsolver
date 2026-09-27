package io.github.jacek4yang.stegsolver.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.transform.LegacyReference;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataExtractorTest {

    @Test
    void arbitraryPlansAndBoundedPrefixesMatchFullExtraction() {
        var random = new java.util.Random(43);
        var image = io.github.jacek4yang.stegsolver.TestImages.randomArgb(9, 7, 81);
        for (int trial = 0; trial < 128; trial++) {
            var options = ExtractionOptions.none().withLsbFirst(random.nextBoolean())
                    .withRowFirst(random.nextBoolean()).withInvertBits(random.nextBoolean());
            for (var channel : io.github.jacek4yang.stegsolver.core.Channel.values())
                for (int bit = 0; bit < 8; bit++) options = options.with(channel, bit, random.nextBoolean());
            byte[] full = DataExtractor.extract(image, options);
            for (int limit = 0; limit < 9; limit++) {
                byte[] prefix = DataExtractor.extract(image, null, options, limit).data();
                org.junit.jupiter.api.Assertions.assertArrayEquals(java.util.Arrays.copyOf(full, Math.min(limit, full.length)), prefix);
            }
        }
    }

    @Test
    @DisplayName("a single LSB plane of one channel extracts exactly that bit per pixel")
    void singlePlaneExtraction() {
        // 8 pixels of red = 0b10110010, extracted LSB..MSB into bits 7..0 of one byte.
        int[] pixels = {0x00ff0000, 0x00000000, 0x00ff0000, 0x00ff0000,
                0x00000000, 0x00000000, 0x00ff0000, 0x00000000};
        ImageData image = ImageData.opaque(8, 1, pixels);
        ExtractionOptions options = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .withLsbFirst(true);
        byte[] extracted = DataExtractor.extract(image, options);
        assertEquals(1, extracted.length);
        // The first extracted bit is bit 7 of the first byte: 1,0,1,1,0,0,1,0 -> 0b10110010
        assertEquals((byte) 0b1011_0010, extracted[0]);
    }

    @Test
    @DisplayName("MSB-first extraction reverses the bit order inside each channel")
    void msbFirstExtraction() {
        int[] pixels = {0x00800000, 0x00000000, 0x00000000, 0x00000000,
                0x00000000, 0x00000000, 0x00000000, 0x00000000};
        ImageData image = ImageData.opaque(8, 1, pixels);
        ExtractionOptions lsb = ExtractionOptions.none().with(Channel.RED, 7, true).withLsbFirst(true);
        ExtractionOptions msb = ExtractionOptions.none().with(Channel.RED, 7, true).withLsbFirst(false);
        assertEquals((byte) 0b1000_0000, DataExtractor.extract(image, lsb)[0]);
        assertEquals((byte) 0b1000_0000, DataExtractor.extract(image, msb)[0]);

        // Plane 0 of red, MSB first: the byte is read from bit 7 down to bit 0 of the channel.
        int[] ramp = new int[8];
        for (int i = 0; i < 8; i++) {
            ramp[i] = (i & 1) << 16;
        }
        ImageData rampImage = ImageData.opaque(8, 1, ramp);
        ExtractionOptions msbPlane0 = ExtractionOptions.none().with(Channel.RED, 0, true).withLsbFirst(false);
        // MSB first walks plane 7..0, only plane 0 is selected, so one bit per pixel: 0,1,0,1,0,1,0,1
        assertEquals((byte) 0b0101_0101, DataExtractor.extract(rampImage, msbPlane0)[0]);
    }

    @Test
    @DisplayName("extraction matches the legacy implementation for every option combination")
    void legacyParity() {
        ImageData image = LegacyReference.fuzzImage(9, 7, 1234);
        Set<String> orders = new HashSet<>();
        for (RgbOrder order : RgbOrder.values()) {
            orders.add(order.label().toLowerCase());
        }
        boolean[][][] selections = {
                LegacyReference.selection(0, 0, 1, 0, 2, 0, 3, 0),
                LegacyReference.selection(0, 7, 1, 3, 2, 1),
                LegacyReference.selection(1, 5, 3, 2, 2, 6, 0, 0),
                LegacyReference.selection(0, 0, 0, 0, 0, 0, 0, 0),
        };
        for (boolean[][] selection : selections) {
            for (RgbOrder order : RgbOrder.values()) {
                for (boolean lsbFirst : new boolean[] {false, true}) {
                    for (boolean rowFirst : new boolean[] {false, true}) {
                        ExtractionOptions options = optionsFrom(selection, order, lsbFirst, rowFirst);
                        byte[] expected = LegacyReference.extract(image, selection,
                                order.label().toLowerCase(), lsbFirst, rowFirst);
                        byte[] actual = DataExtractor.extract(image, options);
                        assertArrayEquals(expected, actual, "options: " + order + " lsbFirst=" + lsbFirst
                                + " rowFirst=" + rowFirst + " selection=" + describe(selection));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the legacy ARGB mask is reproduced for the user interface")
    void argbMask() {
        ExtractionOptions options = ExtractionOptions.none()
                .with(Channel.ALPHA, 7, true)
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 3, true)
                .with(Channel.BLUE, 0, true);
        assertEquals((1 << 31) | (1 << 16) | (1 << 11) | 1, options.argbMask());
        assertEquals(4, options.selectedCount());
    }

    @Test
    @DisplayName("a trailing partial byte is zero padded")
    void trailingPartialByte() {
        int[] pixels = {0x00ff0000, 0x00ff0000, 0x00ff0000};
        ImageData image = ImageData.opaque(3, 1, pixels);
        ExtractionOptions options = ExtractionOptions.none().with(Channel.RED, 0, true);
        byte[] extracted = DataExtractor.extract(image, options);
        assertEquals(1, extracted.length);
        assertEquals((byte) 0b1110_0000, extracted[0]);
    }

    @Test
    @DisplayName("column traversal visits the pixels in the other order")
    void columnTraversal() {
        // Both pixels of column 0 are red, both pixels of column 1 are black.
        int[] pixels = {
                0x00ff0000, 0x00000000,
                0x00ff0000, 0x00000000,
        };
        ImageData image = ImageData.opaque(2, 2, pixels);
        ExtractionOptions rows = ExtractionOptions.none().with(Channel.RED, 0, true).withRowFirst(true);
        ExtractionOptions columns = ExtractionOptions.none().with(Channel.RED, 0, true).withRowFirst(false);
        // Row order: (0,0)=1 (1,0)=0 (0,1)=1 (1,1)=0
        assertEquals((byte) 0b1010_0000, DataExtractor.extract(image, rows)[0]);
        // Column order: (0,0)=1 (0,1)=1 (1,0)=0 (1,1)=0
        assertEquals((byte) 0b1100_0000, DataExtractor.extract(image, columns)[0]);

        // A pattern that is symmetric under transposition gives the same bits either way.
        int[] diagonal = {
                0x00ff0000, 0x00000000,
                0x00000000, 0x00ff0000,
        };
        ImageData diagonalImage = ImageData.opaque(2, 2, diagonal);
        assertEquals((byte) 0b1001_0000, DataExtractor.extract(diagonalImage, rows)[0]);
        assertEquals((byte) 0b1001_0000, DataExtractor.extract(diagonalImage, columns)[0]);
    }

    @Test
    @DisplayName("inverting the bits flips every extracted bit")
    void invertedBits() {
        ImageData image = TestImages.randomArgb(6, 4, 99);
        ExtractionOptions plain = ExtractionOptions.allPlanes();
        ExtractionOptions inverted = plain.withInvertBits(true);
        byte[] a = DataExtractor.extract(image, plain);
        byte[] b = DataExtractor.extract(image, inverted);
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals((byte) ~a[i], b[i], "byte " + i);
        }
    }

    @Test
    @DisplayName("extracting a region only uses the pixels of that region")
    void regionExtraction() {
        ImageData image = TestImages.randomArgb(8, 8, 7);
        Roi region = new Roi(2, 3, 4, 2);
        ExtractionOptions options = ExtractionOptions.allPlanes();
        byte[] expected = DataExtractor.extract(image.crop(region), options);
        byte[] actual = DataExtractor.extract(image, region, options);
        assertArrayEquals(expected, actual);
        // 8 pixels with all 32 bit planes selected are 32 bytes.
        assertEquals(32, actual.length);
    }

    @Test
    @DisplayName("a bounded extraction reports the full size and stops early")
    void boundedExtraction() {
        ImageData image = TestImages.randomRgb(64, 64, 5);
        ExtractionOptions options = ExtractionOptions.allPlanes();
        DataExtractor.Result full = DataExtractor.extract(image, Roi.whole(64, 64), options, Integer.MAX_VALUE);
        assertEquals(64 * 64 * 4, full.totalBytes());
        assertFalse(full.truncated());
        assertEquals(full.totalBytes(), full.data().length);

        DataExtractor.Result bounded = DataExtractor.extract(image, Roi.whole(64, 64), options, 100);
        assertEquals(full.totalBytes(), bounded.totalBytes());
        assertTrue(bounded.truncated());
        assertEquals(100, bounded.data().length);
        byte[] prefix = java.util.Arrays.copyOf(full.data(), 100);
        assertArrayEquals(prefix, bounded.data());
    }

    @Test
    @DisplayName("an empty selection produces an empty extract with a known total size")
    void emptySelection() {
        ImageData image = TestImages.randomRgb(4, 4, 1);
        DataExtractor.Result result = DataExtractor.extract(image, Roi.whole(4, 4), ExtractionOptions.none(),
                1024);
        assertEquals(0, result.totalBytes());
        assertEquals(0, result.data().length);
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("extracted ASCII payloads survive the round trip")
    void asciiRoundTrip() {
        byte[] message = "secret message".getBytes(StandardCharsets.US_ASCII);
        ImageData image = embedLsb(message, 40, 4);
        ExtractionOptions options = ExtractionOptions.none().with(Channel.RED, 0, true);
        byte[] extracted = DataExtractor.extract(image, options);
        assertEquals(message[0], extracted[0]);
        assertArrayEquals(java.util.Arrays.copyOf(extracted, message.length), message);
    }

    @Test
    @DisplayName("the options record is compact and immutable")
    void optionsBehaviour() {
        ExtractionOptions options = ExtractionOptions.defaults();
        assertEquals(3, options.selectedCount());
        assertTrue(options.isSelected(Channel.RED, 0));
        assertFalse(options.isSelected(Channel.RED, 1));
        assertEquals(options, options.withOrder(RgbOrder.RGB));
        assertThrows(IllegalArgumentException.class, () -> options.isSelected(Channel.RED, 8));
        // All planes is 4 channels x 8 planes.
        assertEquals(32, ExtractionOptions.allPlanes().selectedCount());
        assertEquals(4, ExtractionOptions.none().withPlaneAcrossChannels(3, true).selectedCount());
        assertEquals(8, ExtractionOptions.none().withChannel(Channel.GREEN, true).selectedCount());
        ExtractionOptions cleared = ExtractionOptions.allPlanes().withChannel(Channel.ALPHA, false);
        assertEquals(24, cleared.selectedCount());
    }

    @Test
    @DisplayName("extraction visits alpha before the colour channels")
    void alphaComesFirst() {
        // Alpha bit 0 set, red bit 0 clear: with everything selected and LSB first, the first bit of
        // the output byte is the alpha bit and the second bit is the red bit.
        ImageData image = ImageData.opaque(1, 1, new int[] {0x01000000});
        ExtractionOptions options = ExtractionOptions.allPlanes().withLsbFirst(true);
        byte[] extracted = DataExtractor.extract(image, options);
        assertEquals((byte) 0b1000_0000, extracted[0]);
    }

    private static ExtractionOptions optionsFrom(boolean[][] selection, RgbOrder order, boolean lsbFirst,
            boolean rowFirst) {
        ExtractionOptions options = ExtractionOptions.none().withOrder(order).withLsbFirst(lsbFirst)
                .withRowFirst(rowFirst);
        Channel[] channels = {Channel.ALPHA, Channel.RED, Channel.GREEN, Channel.BLUE};
        for (int c = 0; c < channels.length; c++) {
            for (int plane = 0; plane < 8; plane++) {
                if (selection[c][plane]) {
                    options = options.with(channels[c], plane, true);
                }
            }
        }
        return options;
    }

    private static String describe(boolean[][] selection) {
        StringBuilder text = new StringBuilder();
        Channel[] channels = {Channel.ALPHA, Channel.RED, Channel.GREEN, Channel.BLUE};
        for (int c = 0; c < channels.length; c++) {
            for (int plane = 0; plane < 8; plane++) {
                if (selection[c][plane]) {
                    text.append(channels[c].symbol()).append(plane).append(' ');
                }
            }
        }
        return text.toString();
    }

    /** Embeds {@code payload} in the red LSB plane, most significant bit first. */
    private static ImageData embedLsb(byte[] payload, int width, int height) {
        int[] pixels = new int[width * height];
        int bit = 0;
        for (byte value : payload) {
            for (int shift = 7; shift >= 0; shift--) {
                if (((value >> shift) & 1) != 0) {
                    pixels[bit] |= 0x00010000;
                }
                bit++;
            }
        }
        return ImageData.opaque(width, height, pixels);
    }
}
