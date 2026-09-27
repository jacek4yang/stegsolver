package io.github.jacek4yang.stegsolver.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.ImageData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CombineModeTest {

    @Test
    @DisplayName("the combiner keeps the legacy 13 modes in the legacy order")
    void modeOrder() {
        assertEquals(13, CombineMode.values().length);
        assertEquals("XOR", CombineMode.byLegacyIndex(0).label());
        assertEquals("OR", CombineMode.byLegacyIndex(1).label());
        assertEquals("AND", CombineMode.byLegacyIndex(2).label());
        assertEquals("ADD", CombineMode.byLegacyIndex(3).label());
        assertEquals("SUB", CombineMode.byLegacyIndex(5).label());
        assertEquals("MUL", CombineMode.byLegacyIndex(7).label());
        assertEquals("Interlace rows", CombineMode.byLegacyIndex(11).label());
        assertEquals("Interlace columns", CombineMode.byLegacyIndex(12).label());
        assertTrue(CombineMode.byLegacyIndex(11).isInterlace());
        assertThrows(IndexOutOfBoundsException.class, () -> CombineMode.byLegacyIndex(13));
    }

    @Test
    @DisplayName("navigation wraps around in both directions")
    void navigation() {
        assertEquals(CombineMode.OR, CombineMode.next(CombineMode.XOR));
        assertEquals(CombineMode.XOR, CombineMode.next(CombineMode.INTERLACE_COLUMNS));
        assertEquals(CombineMode.INTERLACE_COLUMNS, CombineMode.previous(CombineMode.XOR));
    }

    @Test
    @DisplayName("per pixel results match the legacy comb() arithmetic exactly")
    void legacyParityForEveryMode() {
        ImageData first = TestImages.randomRgb(13, 9, 21);
        ImageData second = TestImages.randomRgb(13, 9, 22);
        ImageData bigger = TestImages.randomRgb(17, 5, 23);
        for (CombineMode mode : CombineMode.values()) {
            if (mode.isInterlace()) {
                continue;
            }
            ImageData combined = mode.combine(first, second);
            for (int i = 0; i < combined.pixelCount(); i++) {
                int expected = LegacyReference.combinePixels(mode.legacyIndex(), first.pixels()[i],
                        second.pixels()[i]);
                assertEquals(expected, combined.pixels()[i],
                        "mode " + mode + " differs at pixel " + i);
            }
            // Unequal sizes: the result is as large as the bigger image, missing pixels read as 0.
            ImageData mixed = mode.combine(first, bigger);
            assertEquals(17, mixed.width());
            assertEquals(9, mixed.height());
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 17; x++) {
                    int c1 = x < 13 && y < 9 ? first.pixelAt(x, y) : 0;
                    int c2 = x < 17 && y < 5 ? bigger.pixelAt(x, y) : 0;
                    assertEquals(LegacyReference.combinePixels(mode.legacyIndex(), c1, c2),
                            mixed.pixelAt(x, y), "mode " + mode + " at " + x + "," + y);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = CombineMode.class, names = {"INTERLACE_ROWS", "INTERLACE_COLUMNS"})
    @DisplayName("interlace modes use the smaller of the two sizes and alternate rows or columns")
    void interlace(CombineMode mode) {
        ImageData first = TestImages.solid(4, 3, 0xff0000);
        ImageData second = TestImages.solid(2, 5, 0x00ff00);
        ImageData interleaved = mode.combine(first, second);
        if (mode == CombineMode.INTERLACE_ROWS) {
            assertEquals(2, interleaved.width());
            assertEquals(6, interleaved.height());
            for (int y = 0; y < 6; y++) {
                int expected = y % 2 == 0 ? 0xffff0000 : 0xff00ff00;
                for (int x = 0; x < 2; x++) {
                    assertEquals(expected, interleaved.pixelAt(x, y), "at " + x + "," + y);
                }
            }
        } else {
            assertEquals(4, interleaved.width());
            assertEquals(3, interleaved.height());
            for (int x = 0; x < 4; x++) {
                int expected = x % 2 == 0 ? 0xffff0000 : 0xff00ff00;
                for (int y = 0; y < 3; y++) {
                    assertEquals(expected, interleaved.pixelAt(x, y), "at " + x + "," + y);
                }
            }
        }
    }

    @Test
    @DisplayName("interlace uses the overlapping area of two different sized images")
    void interlaceUsesOverlap() {
        ImageData first = TestImages.solid(6, 6, 0xffffff);
        ImageData interleaved = CombineMode.INTERLACE_COLUMNS.combine(first, TestImages.solid(2, 3, 0));
        assertEquals(4, interleaved.width());
        assertEquals(3, interleaved.height());
        assertThrows(IllegalArgumentException.class, () -> CombineMode.XOR.combine(null, first));
    }

    @Test
    @DisplayName("the per channel modes work per channel without carrying into the neighbouring channel")
    void perChannelArithmetic() {
        // Every result is fully opaque, matching the legacy write into a TYPE_INT_RGB image.
        assertEquals(0xff111213, CombineMode.combinePixels(CombineMode.ADD_PER_CHANNEL, 0x010203, 0x101010));
        assertEquals(0xff010102,
                CombineMode.combinePixels(CombineMode.SUBTRACT_PER_CHANNEL, 0x110203, 0x100101));
        assertEquals(0xff060608,
                CombineMode.combinePixels(CombineMode.MULTIPLY_PER_CHANNEL, 0x020304, 0x030202));
        assertEquals(0xff01ff03, CombineMode.combinePixels(CombineMode.LIGHTEST, 0x010203, 0x01ff01));
        assertEquals(0xff010201, CombineMode.combinePixels(CombineMode.DARKEST, 0x010203, 0x01ff01));
        // The whole pixel modes do carry, which is the documented legacy behaviour.
        assertEquals(0xffffffff, CombineMode.combinePixels(CombineMode.ADD, 0xffff00, 0x0000ff));
        assertEquals(0xff020104,
                CombineMode.combinePixels(CombineMode.ADD_PER_CHANNEL, 0x010203, 0x01ff01));
    }

    @Test
    @DisplayName("the result is always opaque, like the legacy TYPE_INT_RGB output")
    void resultIsOpaque() {
        ImageData withAlpha = TestImages.randomArgb(5, 5, 7);
        ImageData combined = CombineMode.XOR.combine(withAlpha, withAlpha);
        for (int pixel : combined.pixels()) {
            assertEquals(0xff, pixel >>> 24, "pixel " + Integer.toHexString(pixel) + " is not opaque");
        }
        // XORing an image with itself is black, and black must be visible (fully opaque).
        assertEquals(0xff000000, combined.pixels()[0]);

        // Interlace modes must be opaque as well.
        ImageData interleaved = CombineMode.INTERLACE_ROWS.combine(withAlpha, withAlpha);
        for (int pixel : interleaved.pixels()) {
            assertEquals(0xff, pixel >>> 24, "pixel " + Integer.toHexString(pixel) + " is not opaque");
        }
    }
}
