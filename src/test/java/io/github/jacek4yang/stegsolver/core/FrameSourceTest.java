package io.github.jacek4yang.stegsolver.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameSourceTest {
    @TempDir Path dir;

    @Test void thumbnailsRespectRequestedSizeAndClosedSource() throws Exception {
        Path path = dir.resolve("frame.png");
        ImageIO.write(new BufferedImage(128, 64, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        try (var source = FrameSource.open(path)) {
            assertEquals(0, source.cachedFrameCount());
            assertEquals(32, source.thumbnail(0, 32).width());
            assertEquals(64, source.thumbnail(0, 64).width());
            assertEquals(1, source.cachedFrameCount());
            assertThrows(IllegalArgumentException.class, () -> source.thumbnail(0, 0));
            source.close();
            assertThrows(IOException.class, () -> source.frame(0));
            assertThrows(IOException.class, () -> source.thumbnail(0, 32));
        }
    }

    @Test void compressedBombIsRejectedBeforeDecode() throws Exception {
        // GIF logical dimensions and first frame dimensions; no pixel data is necessary.
        byte[] gif = new byte[] {'G','I','F','8','9','a', -1,-1,-1,-1, 0,0,0,
                0x2c,0,0,0,0,-1,-1,-1,-1,0, 2,0,0x3b};
        Path path = dir.resolve("bomb.gif");
        java.nio.file.Files.write(path, gif);
        IOException error = assertThrows(IOException.class, () -> ImageIoUtil.load(path));
        assertTrue(error.getMessage().contains("limit"), error.toString());
        String report = io.github.jacek4yang.stegsolver.parser.FileAnalyzer.analyze(gif, "bomb.gif").toText();
        assertTrue(report.contains("limit"), report);
    }

    @Test void animatedFramesAreLazyAndCacheEvicts() throws Exception {
        Path path = dir.resolve("many.gif");
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < 8; i++) {
                var image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, (i & 1) == 0 ? 0xffff0000 : 0xff0000ff);
                writer.writeToSequence(new javax.imageio.IIOImage(image, null, null), null);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        try (var source = FrameSource.open(path, 2)) {
            assertEquals(8, source.frameCount());
            assertEquals(0, source.cachedFrameCount());
            for (int i = 0; i < 8; i++) {
                assertEquals((i & 1) == 0 ? 0xffff0000 : 0xff0000ff, source.frame(i).pixelAt(0, 0));
                assertTrue(source.cachedFrameCount() <= 2);
            }
            assertEquals(0xffff0000, source.frame(0).pixelAt(0, 0));
        }
    }

    @Test void impossibleDimensionsAreRejected() {
        assertThrows(IOException.class, () -> ImageIoUtil.checkDimensions(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IOException.class, () -> ImageIoUtil.checkDimensions(0, 10));
        assertDoesNotThrow(() -> ImageIoUtil.checkDimensions(4096, 2048));
    }
}
