package io.github.jacek4yang.stegsolver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageIoUtilTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("PNG round trip preserves size and pixel values")
    void pngRoundTrip() throws IOException {
        ImageData original = randomImage(23, 17, true);
        Path file = tempDir.resolve("image.png");
        ImageIoUtil.save(original, file);
        ImageData loaded = ImageIoUtil.load(file);
        assertEquals(original.width(), loaded.width());
        assertEquals(original.height(), loaded.height());
        for (int i = 0; i < original.pixelCount(); i++) {
            assertEquals(original.pixels()[i], loaded.pixels()[i], "pixel " + i);
        }
    }

    @Test
    @DisplayName("the format is taken from the file extension, including the jpeg alias")
    void formatFromExtension() {
        assertEquals("png", ImageIoUtil.formatForFileName(Path.of("a.png")));
        assertEquals("jpeg", ImageIoUtil.formatForFileName(Path.of("a.jpg")));
        assertEquals("jpeg", ImageIoUtil.formatForFileName(Path.of("A.JPEG")));
        assertEquals("bmp", ImageIoUtil.formatForFileName(Path.of("a.bmp")));
        assertEquals("png", ImageIoUtil.formatForFileName(Path.of("no-extension")));
    }

    @Test
    @DisplayName("saving to a new directory creates it")
    void saveCreatesDirectories() throws IOException {
        Path nested = tempDir.resolve("deep/nested/image.png");
        ImageIoUtil.save(randomImage(4, 4, false), nested);
        assertTrue(Files.isRegularFile(nested));
    }

    @Test
    @DisplayName("saving over an existing file replaces it atomically without leaving temporary files")
    void saveReplacesExisting() throws IOException {
        ImageData first = TestImages.solid(8, 8, 0x112233);
        Path file = tempDir.resolve("image.bmp");
        ImageIoUtil.save(first, file);
        long firstLength = Files.size(file);
        ImageIoUtil.save(TestImages.solid(4, 4, 0x445566), file);
        assertTrue(Files.size(file) != firstLength || firstLength > 0);
        assertEquals(4, ImageIoUtil.load(file).width());
        try (var entries = Files.list(tempDir)) {
            List<Path> leftovers = entries.filter(path -> path.getFileName().toString().startsWith(".stegsolver"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "temporary files were left behind: " + leftovers);
        }
    }

    @Test
    @DisplayName("a corrupt file produces a clear error instead of a null image")
    void corruptFile() throws IOException {
        Path file = tempDir.resolve("broken.png");
        Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4, 5});
        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ImageIoUtil.load(file));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("broken.png") || error.getMessage().contains("Unsupported"));
    }

    @Test
    @DisplayName("an empty file and a missing file are reported clearly")
    void emptyAndMissingFiles() throws IOException {
        Path empty = tempDir.resolve("empty.png");
        Files.write(empty, new byte[0]);
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ImageIoUtil.load(empty)).getMessage().contains("empty"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ImageIoUtil.load(tempDir.resolve("missing.png"))).getMessage().contains("readable"));
    }

    @Test
    @DisplayName("writable extensions are discovered from ImageIO and include the usual ones")
    void writableExtensions() {
        List<String> extensions = ImageIoUtil.writableExtensions();
        assertTrue(extensions.contains("png"));
        assertTrue(extensions.contains("bmp"));
        assertTrue(extensions.contains("jpg"));
        assertTrue(ImageIoUtil.canWrite(Path.of("x.png")));
        assertFalse(extensions.isEmpty());
    }

    @Test
    @DisplayName("an indexed image keeps its palette information for the random palette map")
    void indexedImagesKeepPalette() {
        BufferedImage indexed = new BufferedImage(4, 2, BufferedImage.TYPE_BYTE_INDEXED);
        indexed.setRGB(0, 0, 0xff0000);
        indexed.setRGB(1, 0, 0xff0000);
        indexed.setRGB(2, 0, 0x00ff00);
        ImageData data = ImageData.fromBufferedImage(indexed);
        assertTrue(data.isIndexed());
        assertNotNull(data.palette());
        assertEquals(4 * 2, data.indices().length);
        // Both red pixels share one palette entry, which is why the palette matters.
        assertEquals(data.indices()[0], data.indices()[1]);
        assertTrue(data.indices()[0] != data.indices()[2]);
    }

    private static ImageData randomImage(int width, int height, boolean alpha) {
        return alpha ? TestImages.randomArgb(width, height, 5) : TestImages.randomRgb(width, height, 5);
    }
}
