package io.github.jacek4yang.stegsolver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jacek4yang.stegsolver.TestImages;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageOpsTest {

    @Test void imageDimensionsCannotOverflowPixelArrayValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ImageData.of(65536, 65536, new int[0], false));
    }

    @Test
    @DisplayName("a quarter turn swaps the dimensions and moves known pixels predictably")
    void quarterTurn() {
        // 3x2 image:  a b c
        //             d e f
        ImageData image = ImageData.opaque(3, 2, new int[] {1, 2, 3, 4, 5, 6});
        ImageData rotated = ImageOps.rotateCounterClockwise(image);
        assertEquals(2, rotated.width());
        assertEquals(3, rotated.height());
        // Counter clockwise: the source right hand edge becomes the top edge, so the layout is
        //   c f
        //   b e
        //   a d
        assertEquals(3, rotated.pixelAt(0, 0));
        assertEquals(6, rotated.pixelAt(1, 0));
        assertEquals(2, rotated.pixelAt(0, 1));
        assertEquals(5, rotated.pixelAt(1, 1));
        assertEquals(1, rotated.pixelAt(0, 2));
        assertEquals(4, rotated.pixelAt(1, 2));
    }

    @Test
    @DisplayName("four quarter turns return the original image")
    void fourTurnsIsIdentity() {
        ImageData image = TestImages.randomArgb(9, 4, 3);
        ImageData turned = ImageOps.rotateCounterClockwise(image, 4);
        assertEquals(image.width(), turned.width());
        assertEquals(image.height(), turned.height());
        assertEquals(0, ImageOps.rotateCounterClockwise(image, 0) == image ? 0 : 1);
        for (int i = 0; i < image.pixelCount(); i++) {
            assertEquals(image.pixels()[i], turned.pixels()[i], "pixel " + i);
        }
        assertSame(image, ImageOps.rotateCounterClockwise(image, 0));
    }

    @Test
    @DisplayName("two quarter turns are a half turn")
    void halfTurn() {
        ImageData image = TestImages.randomRgb(5, 3, 9);
        ImageData half = ImageOps.rotateCounterClockwise(image, 2);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                assertEquals(image.pixelAt(x, y),
                        half.pixelAt(image.width() - 1 - x, image.height() - 1 - y));
            }
        }
    }

    @Test
    @DisplayName("negative and large turn counts are normalised")
    void turnCountsAreNormalised() {
        ImageData image = TestImages.randomRgb(4, 2, 1);
        assertEquals(0, compare(ImageOps.rotateCounterClockwise(image, -4), image));
        assertEquals(0, compare(ImageOps.rotateCounterClockwise(image, 8), image));
        assertEquals(0, compare(ImageOps.rotateCounterClockwise(image, 5),
                ImageOps.rotateCounterClockwise(image, 1)));
    }

    @Test
    @DisplayName("nearest neighbour scaling keeps the pixel values and the requested size")
    void scaling() {
        ImageData image = ImageData.opaque(2, 2, new int[] {0x111111, 0x222222, 0x333333, 0x444444});
        ImageData doubled = ImageOps.scaleNearest(image, 2.0);
        assertEquals(4, doubled.width());
        assertEquals(4, doubled.height());
        assertEquals(0x111111, doubled.pixelAt(0, 0));
        assertEquals(0x111111, doubled.pixelAt(1, 1));
        assertEquals(0x222222, doubled.pixelAt(2, 0));
        assertEquals(0x333333, doubled.pixelAt(0, 2));
        assertEquals(0x444444, doubled.pixelAt(3, 3));

        ImageData halved = ImageOps.scaleNearest(doubled, 0.5);
        assertEquals(2, halved.width());
        assertEquals(2, halved.height());
        assertEquals(0x111111, halved.pixelAt(0, 0));

        assertSame(image, ImageOps.scaleNearest(image, 1.0));
        assertThrows(IllegalArgumentException.class, () -> ImageOps.scaleNearest(image, 0));
        assertEquals(1, ImageOps.scaleNearest(image, 0.01).width());
    }

    @Test
    @DisplayName("cropping returns the source itself when the region is the whole image")
    void cropping() {
        ImageData image = TestImages.randomRgb(6, 4, 2);
        assertSame(image, ImageOps.crop(image, Roi.whole(6, 4)));
        assertSame(image, ImageOps.crop(image, new Roi(0, 0, 100, 100)));
        ImageData cropped = ImageOps.crop(image, new Roi(1, 1, 2, 2));
        assertEquals(2, cropped.width());
        assertEquals(image.pixelAt(1, 1), cropped.pixelAt(0, 0));
        assertEquals(image.pixelAt(2, 2), cropped.pixelAt(1, 1));
    }

    @Test
    @DisplayName("the internal representation round trips through a BufferedImage")
    void bufferedImageRoundTrip() {
        ImageData image = TestImages.randomArgb(5, 3, 11);
        BufferedImage buffered = image.toBufferedImage();
        ImageData back = ImageData.fromBufferedImage(buffered);
        assertEquals(image.width(), back.width());
        assertEquals(image.height(), back.height());
        for (int i = 0; i < image.pixelCount(); i++) {
            assertEquals(image.pixels()[i], back.pixels()[i], "pixel " + i);
        }
    }

    private static int compare(ImageData first, ImageData second) {
        if (first.width() != second.width() || first.height() != second.height()) {
            return 1;
        }
        for (int i = 0; i < first.pixelCount(); i++) {
            if (first.pixels()[i] != second.pixels()[i]) {
                return 1;
            }
        }
        return 0;
    }
}
