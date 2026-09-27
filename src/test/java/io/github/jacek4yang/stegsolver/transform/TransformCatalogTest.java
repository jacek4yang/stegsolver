package io.github.jacek4yang.stegsolver.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransformCatalogTest {

    @Test
    @DisplayName("the catalog keeps the legacy numbering and size")
    void catalogMatchesLegacy() {
        assertEquals(42, TransformCatalog.count());
        assertEquals(0, TransformCatalog.byIndex(0).index());
        assertEquals(TransformKind.ORIGINAL, TransformCatalog.byIndex(0).kind());
        assertEquals(TransformKind.INVERT, TransformCatalog.byIndex(1).kind());
        assertEquals("Invert colours (XOR 0xFFFFFF)", TransformCatalog.byIndex(1).label());
        assertEquals(41, TransformCatalog.byIndex(41).index());
        assertEquals(TransformKind.GRAY_BITS, TransformCatalog.byIndex(41).kind());
    }

    @Test
    @DisplayName("alpha, red, green and blue planes map to the legacy bit positions")
    void planeNumbering() {
        // Legacy: alpha 2..9 are bits 31..24, red 10..17 are 23..16, green 18..25 are 15..8, blue 26..33.
        assertEquals(31, TransformCatalog.byIndex(2).argbBit());
        assertEquals(24, TransformCatalog.byIndex(9).argbBit());
        assertEquals(7, TransformCatalog.byIndex(2).plane());
        assertEquals(Channel.ALPHA, TransformCatalog.byIndex(2).channel());
        assertEquals(23, TransformCatalog.byIndex(10).argbBit());
        assertEquals(16, TransformCatalog.byIndex(17).argbBit());
        assertEquals(Channel.RED, TransformCatalog.byIndex(10).channel());
        assertEquals(15, TransformCatalog.byIndex(18).argbBit());
        assertEquals(8, TransformCatalog.byIndex(25).argbBit());
        assertEquals(Channel.GREEN, TransformCatalog.byIndex(18).channel());
        assertEquals(7, TransformCatalog.byIndex(26).argbBit());
        assertEquals(0, TransformCatalog.byIndex(33).argbBit());
        assertEquals(Channel.BLUE, TransformCatalog.byIndex(33).channel());
    }

    @Test
    @DisplayName("full channel transforms use the legacy ARGB masks")
    void fullChannelMasks() {
        assertEquals(0xff000000, TransformCatalog.byIndex(34).channelMask());
        assertEquals(0x00ff0000, TransformCatalog.byIndex(35).channelMask());
        assertEquals(0x0000ff00, TransformCatalog.byIndex(36).channelMask());
        assertEquals(0x000000ff, TransformCatalog.byIndex(37).channelMask());
        assertEquals(TransformKind.FULL_CHANNEL, TransformCatalog.byIndex(35).kind());
    }

    @Test
    @DisplayName("navigation wraps around exactly like the legacy back/forward buttons")
    void navigationWraps() {
        assertEquals(1, TransformCatalog.next(0));
        assertEquals(41, TransformCatalog.next(40));
        assertEquals(0, TransformCatalog.next(41));
        assertEquals(40, TransformCatalog.previous(41));
        assertEquals(41, TransformCatalog.previous(0));
    }

    @Test
    @DisplayName("navigation inside a group stays in that group")
    void navigationInGroup() {
        // 2..9 are the alpha planes; walking forward from the last one returns to the first.
        assertEquals(3, TransformCatalog.nextInGroup(2));
        assertEquals(2, TransformCatalog.nextInGroup(9));
        assertEquals(9, TransformCatalog.previousInGroup(2));
        // 38..40 are the random colour maps, 41 is a group of its own.
        assertEquals(39, TransformCatalog.previousInGroup(40));
        assertEquals(38, TransformCatalog.nextInGroup(40));
        assertEquals(41, TransformCatalog.previousInGroup(41));
        assertEquals(41, TransformCatalog.nextInGroup(41));
    }

    @Test
    @DisplayName("groups are exposed in catalog order for menu construction")
    void grouping() {
        Map<String, List<TransformDef>> groups = TransformCatalog.grouped();
        assertEquals(List.of("Original", "Invert", "Alpha planes", "Red planes", "Green planes",
                "Blue planes", "Channels", "Random colour maps", "Gray pixels"),
                List.copyOf(groups.keySet()));
        assertEquals(8, groups.get("Blue planes").size());
        assertEquals(4, groups.get("Channels").size());
    }

    @Test
    @DisplayName("random colour maps are the catalog entries 38, 39 and 40")
    void randomColourMaps() {
        for (int index = 38; index <= 40; index++) {
            assertEquals(TransformKind.RANDOM_MAP, TransformCatalog.byIndex(index).kind());
        }
        assertTrue(TransformCatalog.byIndex(41).label().contains("Gray"));
    }

    @Test
    @DisplayName("applying a catalog entry of every index produces one pixel per source pixel")
    void everyTransformProducesFullImage() {
        ImageData image = TestImages.randomArgb(7, 5, 42);
        for (TransformDef def : TransformCatalog.definitions()) {
            int[] pixels = ImageTransforms.apply(def, image);
            assertEquals(image.pixelCount(), pixels.length, "wrong size for " + def.label());
        }
    }

    @Test
    @DisplayName("legacy parity: every transform matches the original algorithm byte for byte")
    void legacyParityForAllTransforms() {
        ImageData image = LegacyReference.fuzzImage(11, 6, 7);
        for (TransformDef def : TransformCatalog.definitions()) {
            if (def.kind() == TransformKind.RANDOM_MAP) {
                // The legacy tool used a fresh Random per visit; the rewrite uses a fixed seed per
                // variant so that revisiting a transform shows the same mapping. Verified separately.
                continue;
            }
            int[] expected = LegacyReference.apply(image, def.index());
            int[] actual = ImageTransforms.apply(def, image);
            assertArrayEquals(expected, actual, "transform " + def.index() + " (" + def.label() + ") diverged");
        }
    }

    @Test
    @DisplayName("the random colour map matches the legacy arithmetic for the same seed")
    void randomColourMapParity() {
        ImageData image = LegacyReference.fuzzImage(9, 4, 3);
        int[] expected = LegacyReference.randomColourMap(image, ImageTransforms.seedFor(1));
        assertArrayEquals(expected, ImageTransforms.randomComponentMap(image, ImageTransforms.seedFor(1)));
    }

    @Test
    @DisplayName("the random colour map is deterministic per variant and differs between variants")
    void randomColourMapIsDeterministic() {
        ImageData image = TestImages.randomRgb(16, 16, 11);
        assertArrayEquals(ImageTransforms.randomMap(image, 1), ImageTransforms.randomMap(image, 1));
        boolean differs = false;
        int[] first = ImageTransforms.randomMap(image, 1);
        int[] second = ImageTransforms.randomMap(image, 2);
        for (int i = 0; i < first.length && !differs; i++) {
            differs = first[i] != second[i];
        }
        assertTrue(differs, "variant 1 and 2 must use different mappings");
        assertThrows(IllegalArgumentException.class, () -> ImageTransforms.randomMap(image, 4));
    }

    @Test
    @DisplayName("the engine returns the cached array for repeated requests")
    void engineCaches() {
        ImageData image = TestImages.randomRgb(20, 20, 5);
        TransformEngine engine = new TransformEngine(image, 1024 * 1024);
        int[] first = engine.pixelsFor(35);
        int[] second = engine.pixelsFor(35);
        assertSame(first, second);
        assertTrue(engine.isCached(35));
        assertEquals(1, engine.cachedTransformCount());
        // The original transform is not cached, it is the source array itself.
        assertSame(image.pixels(), engine.pixelsFor(0));
        assertEquals(1, engine.cachedTransformCount());
    }

    @Test
    @DisplayName("the engine evicts the least recently used transform when the budget is exceeded")
    void engineEvicts() {
        ImageData image = TestImages.randomRgb(512, 512, 5);
        TransformEngine engine = new TransformEngine(image, TransformEngine.MIN_CACHE_BYTES);
        for (int index = 2; index <= 41; index++) {
            engine.pixelsFor(index);
        }
        long frameBytes = 4L * image.pixelCount();
        long framesThatFit = TransformEngine.MIN_CACHE_BYTES / frameBytes;
        assertTrue(engine.cachedBytes() <= TransformEngine.MIN_CACHE_BYTES,
                "cache holds " + engine.cachedBytes() + " bytes, budget is "
                        + TransformEngine.MIN_CACHE_BYTES);
        assertTrue(engine.cachedTransformCount() <= framesThatFit,
                "expected at most " + framesThatFit + " cached transforms but found "
                        + engine.cachedTransformCount());
        assertTrue(engine.isCached(41), "the most recent transform must stay cached");
        assertTrue(!engine.isCached(2), "the oldest transform should have been evicted");
    }

    @Test
    @DisplayName("engineFor returns an image with the same dimensions as the source")
    void engineImageFor() {
        ImageData image = TestImages.randomRgb(13, 9, 5);
        TransformEngine engine = new TransformEngine(image);
        ImageData transformed = engine.imageFor(TransformCatalog.byIndex(1));
        assertEquals(13, transformed.width());
        assertEquals(9, transformed.height());
        assertSame(image, engine.imageFor(TransformCatalog.byIndex(0)));
    }
}
