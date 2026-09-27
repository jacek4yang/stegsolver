package io.github.jacek4yang.stegsolver.barcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageOps;
import io.github.jacek4yang.stegsolver.core.Roi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RotationMapperTest {

    @Test
    @DisplayName("the rotated dimensions are swapped for quarter and three quarter turns")
    void rotatedDimensions() {
        assertEquals(100, RotationMapper.rotatedWidth(0, 100, 40));
        assertEquals(40, RotationMapper.rotatedHeight(0, 100, 40));
        assertEquals(40, RotationMapper.rotatedWidth(1, 100, 40));
        assertEquals(100, RotationMapper.rotatedHeight(1, 100, 40));
        assertEquals(100, RotationMapper.rotatedWidth(2, 100, 40));
        assertEquals(40, RotationMapper.rotatedHeight(2, 100, 40));
        assertEquals(40, RotationMapper.rotatedWidth(3, 100, 40));
        assertEquals(100, RotationMapper.rotatedHeight(3, 100, 40));
    }

    @Test
    @DisplayName("mapping every pixel of a rotated image back returns the original pixel")
    void roundTripThroughRealRotation() {
        int width = 7;
        int height = 5;
        ImageData source = TestImages.randomRgb(width, height, 12);
        for (int turns = 0; turns < 4; turns++) {
            ImageData rotated = ImageOps.rotateCounterClockwise(source, turns);
            assertEquals(RotationMapper.rotatedWidth(turns, width, height), rotated.width());
            assertEquals(RotationMapper.rotatedHeight(turns, width, height), rotated.height());
            for (int y = 0; y < rotated.height(); y++) {
                for (int x = 0; x < rotated.width(); x++) {
                    RotationMapper.Point original = RotationMapper.toOriginal(x, y, turns, width, height);
                    int ox = (int) Math.round(original.x());
                    int oy = (int) Math.round(original.y());
                    assertTrue(ox >= 0 && ox < width && oy >= 0 && oy < height,
                            "mapped point " + original + " is outside the original image");
                    assertEquals(source.pixelAt(ox, oy), rotated.pixelAt(x, y),
                            "pixel " + x + "," + y + " at " + turns + " turns");
                }
            }
        }
    }

    @Test
    @DisplayName("a known point lands where a quarter turn puts it")
    void knownPoints() {
        // A 4x2 image rotated a quarter turn counter clockwise becomes 2x4, and the source right hand
        // edge becomes the top edge of the result.
        RotationMapper.Point topLeft = RotationMapper.toOriginal(0, 0, 1, 4, 2);
        assertEquals(3, topLeft.x(), 1e-9);
        assertEquals(0, topLeft.y(), 1e-9);
        RotationMapper.Point belowTopLeft = RotationMapper.toOriginal(1, 0, 1, 4, 2);
        assertEquals(3, belowTopLeft.x(), 1e-9);
        assertEquals(1, belowTopLeft.y(), 1e-9);
        // Identity case.
        assertEquals(new RotationMapper.Point(3, 1), RotationMapper.toOriginal(3, 1, 0, 4, 2));
        assertEquals(new RotationMapper.Point(3, 1), RotationMapper.toOriginal(3, 1, 4, 4, 2));
    }

    @Test
    @DisplayName("a rectangle maps back to the bounding box of its corners and stays inside the image")
    void rectangles() {
        Roi rotated = new Roi(0, 0, 2, 4);
        Roi original = RotationMapper.toOriginalRect(rotated, 1, 4, 2);
        assertEquals(0, original.x());
        assertEquals(0, original.y());
        assertEquals(4, original.width());
        assertEquals(2, original.height());

        Roi identity = RotationMapper.toOriginalRect(new Roi(1, 1, 2, 2), 0, 10, 10);
        assertEquals(new Roi(1, 1, 2, 2), identity);

        Roi clamped = RotationMapper.toOriginalRect(new Roi(0, 0, 2, 4), 1, 4, 2);
        assertTrue(clamped.maxX() <= 4 && clamped.maxY() <= 2);
        assertEquals(Roi.EMPTY, RotationMapper.toOriginalRect(Roi.EMPTY, 1, 4, 2));
        assertEquals(Roi.EMPTY, RotationMapper.toOriginalRect(null, 1, 4, 2));
    }

    @Test
    @DisplayName("scaling is undone for the rescaled fallback passes")
    void unscaling() {
        assertEquals(new RotationMapper.Point(10, 5), RotationMapper.unscale(20, 10, 2.0));
        assertEquals(new RotationMapper.Point(20, 10), RotationMapper.unscale(10, 5, 0.5));
        assertEquals(new RotationMapper.Point(10, 5), RotationMapper.unscale(10, 5, 0));
    }

    @Test
    @DisplayName("the full chain used by the scanner - scale, then rotate - maps back to the source pixel")
    void scaleThenRotateChain() {
        int width = 9;
        int height = 6;
        ImageData source = TestImages.randomRgb(width, height, 3);
        double scale = 2.0;
        ImageData scaled = ImageOps.scaleNearest(source, scale);
        assertEquals(width * 2, scaled.width());
        assertEquals(height * 2, scaled.height());

        for (int turns = 0; turns < 4; turns++) {
            ImageData bitmap = ImageOps.rotateCounterClockwise(scaled, turns);
            for (int sy = 0; sy < height; sy++) {
                for (int sx = 0; sx < width; sx++) {
                    // Nearest neighbour scaling puts source pixel (sx, sy) at (2sx, 2sy).
                    int scaledX = (int) (sx * scale);
                    int scaledY = (int) (sy * scale);
                    assertEquals(source.pixelAt(sx, sy), scaled.pixelAt(scaledX, scaledY));
                    // Find that pixel in the rotated bitmap using the forward convention.
                    int[] rotated = forward(scaledX, scaledY, turns, scaled.width(), scaled.height());
                    RotationMapper.Point unrotated = RotationMapper.toOriginal(rotated[0], rotated[1], turns,
                            scaled.width(), scaled.height());
                    RotationMapper.Point original = RotationMapper.unscale(unrotated.x(), unrotated.y(), scale);
                    assertEquals(scaledX, Math.round(unrotated.x()), 1e-6);
                    assertEquals(scaledY, Math.round(unrotated.y()), 1e-6);
                    assertEquals(sx, Math.round(original.x()), 1e-6);
                    assertEquals(sy, Math.round(original.y()), 1e-6);
                    assertEquals(source.pixelAt(sx, sy), bitmap.pixelAt(rotated[0], rotated[1]));
                }
            }
        }
    }

    /** Forward rotation: where does the pixel at (x, y) land after the given quarter turns? */
    private static int[] forward(int x, int y, int quarterTurns, int width, int height) {
        int px = x;
        int py = y;
        int w = width;
        int h = height;
        for (int i = 0; i < quarterTurns; i++) {
            int nx = py;
            int ny = w - 1 - px;
            px = nx;
            py = ny;
            int swap = w;
            w = h;
            h = swap;
        }
        return new int[] {px, py};
    }
}
