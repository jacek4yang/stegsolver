package io.github.jacek4yang.stegsolver.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.ImageData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StereoTransformTest {

    @Test
    @DisplayName("the shifted XOR matches the legacy stereogram solver for every offset")
    void legacyParity() {
        ImageData image = TestImages.randomRgb(23, 7, 4242);
        for (int offset = 0; offset < 23; offset++) {
            assertArrayEquals(LegacyReference.stereo(image, offset), StereoTransform.shiftedXor(image, offset),
                    "offset " + offset);
        }
    }

    @Test
    @DisplayName("an offset of zero produces an all black, fully opaque image")
    void zeroOffsetIsBlack() {
        ImageData image = TestImages.randomArgb(8, 4, 1);
        int[] result = StereoTransform.shiftedXor(image, 0);
        for (int pixel : result) {
            // Black rather than transparent: a fully transparent result would be invisible in the viewer.
            assertEquals(0xff000000, pixel);
        }
    }

    @Test
    @DisplayName("a repeating pattern becomes black where the pattern period equals the offset")
    void repeatingPatternIsRevealed() {
        // A horizontally repeating pattern of period 4: the XOR at offset 4 must be black everywhere.
        int width = 16;
        int height = 3;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = ((x % 4) * 40) << 16;
            }
        }
        ImageData image = ImageData.opaque(width, height, pixels);
        int[] solved = StereoTransform.shiftedXor(image, 4);
        for (int pixel : solved) {
            assertEquals(0xff000000, pixel);
        }
        int[] wrongOffset = StereoTransform.shiftedXor(image, 3);
        boolean anyNonZero = false;
        for (int pixel : wrongOffset) {
            anyNonZero |= (pixel & 0xffffff) != 0;
        }
        assertEquals(true, anyNonZero);
    }

    @Test
    @DisplayName("the offset wraps around like the legacy back/forward buttons")
    void offsetNavigation() {
        assertEquals(1, StereoTransform.nextOffset(0, 10));
        assertEquals(0, StereoTransform.nextOffset(9, 10));
        assertEquals(9, StereoTransform.previousOffset(0, 10));
        assertEquals(9, StereoTransform.previousOffset(10, 10));
        assertEquals(3, StereoTransform.normalizeOffset(-7, 10));
        assertEquals(7, StereoTransform.normalizeOffset(17, 10));
    }

    @Test
    @DisplayName("the edge holding variant removes the wrap around seam")
    void edgeHold() {
        ImageData image = TestImages.randomRgb(10, 2, 5);
        int[] wrapped = StereoTransform.shiftedXor(image, 3);
        int[] held = StereoTransform.withEdgeHold(image, 3);
        // Both agree away from the last columns.
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 7; x++) {
                assertEquals(wrapped[y * 10 + x], held[y * 10 + x], "at " + x + "," + y);
            }
        }
    }
}
