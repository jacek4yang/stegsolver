package io.github.jacek4yang.stegsolver.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.ImageData;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of the structural file analysers: correct reports for well formed files, useful warnings for
 * files with appended data, and above all no exception for any malformed input.
 */
class FileAnalyzerTest {

    private final ImageData sample = TestImages.randomRgb(16, 12, 8);

    @Test
    @DisplayName("a PNG file is analysed: chunk list, IHDR fields and no warnings")
    void wellFormedPng() {
        byte[] png = TestImages.pngBytes(sample);
        FileReport report = FileAnalyzer.analyze(png, "test.png");
        assertEquals(ImageFormat.PNG, report.format());
        assertEquals(png.length, report.fileSize());
        String text = report.toText();
        assertTrue(text.contains("IHDR"), text);
        assertTrue(text.contains("Width: 16"), text);
        assertTrue(text.contains("Height: 12"), text);
        assertTrue(text.contains("truecolour"), text);
        assertTrue(text.contains("CRC") && text.contains("valid"), text);
        assertFalse(report.hasWarnings(), () -> "unexpected warnings: " + report.warnings());
    }

    @Test
    @DisplayName("data appended to a PNG is reported with its size")
    void pngWithTrailingData() {
        byte[] png = TestImages.pngBytes(sample);
        byte[] withTrailer = TestImages.withTrailingBytes(png, "hidden payload".getBytes(StandardCharsets.US_ASCII));
        FileReport report = FileAnalyzer.analyze(withTrailer, "trailer.png");
        assertTrue(report.hasWarnings());
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("14 bytes appended after the IEND")),
                () -> "warnings: " + report.warnings());
        // The dump shows both the hex and the printable rendering of the appended bytes.
        assertTrue(report.toText().contains("68696464656e2070"), report.toText());
        assertTrue(report.toText().contains("hidden p ayload"), report.toText());
    }

    @Test
    @DisplayName("a broken PNG CRC is reported together with the correct value")
    void pngWithBrokenCrc() {
        byte[] png = TestImages.pngBytes(sample);
        // Flip a byte inside the IHDR payload, which invalidates its CRC.
        png[17] ^= 0x01;
        FileReport report = FileAnalyzer.analyze(png, "broken-crc.png");
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("CRC mismatch")),
                () -> "warnings: " + report.warnings());
        assertTrue(report.toText().contains("computed 0x"), report.toText());
    }

    @Test
    @DisplayName("a chunk with an impossible length is reported instead of reading out of bounds")
    void pngWithImpossibleChunkLength() {
        byte[] png = TestImages.pngBytes(sample);
        byte[] tampered = png.clone();
        // The first chunk starts at offset 8; overwrite its length with 0x7FFFFFFF.
        tampered[8] = 0x7f;
        tampered[9] = (byte) 0xff;
        tampered[10] = (byte) 0xff;
        tampered[11] = (byte) 0xff;
        FileReport report = FileAnalyzer.analyze(tampered, "huge-chunk.png");
        assertTrue(report.hasWarnings());
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("declares")),
                () -> "warnings: " + report.warnings());
    }

    @Test
    @DisplayName("a JPEG file is analysed: SOF geometry, APP0 segment and the scan length")
    void wellFormedJpeg() {
        BufferedImage image = sample.toBufferedImage();
        byte[] jpeg = TestImages.jpegBytes(image);
        FileReport report = FileAnalyzer.analyze(jpeg, "test.jpg");
        assertEquals(ImageFormat.JPEG, report.format());
        String text = report.toText();
        assertTrue(text.contains("SOI"), text);
        assertTrue(text.contains("EOI"), text);
        assertTrue(text.contains("Start of frame"), text);
        assertTrue(text.contains("Width: 16"), text);
        assertTrue(text.contains("Height: 12"), text);
        assertTrue(text.contains("Start of scan"), text);
        assertTrue(text.contains("Components in scan: 3"), text);
        assertTrue(text.contains("Entropy coded scan data"), text);
        assertTrue(text.contains("Application data APP0"), text);
    }

    @Test
    @DisplayName("data appended to a JPEG after the EOI marker is reported")
    void jpegWithTrailingData() {
        byte[] jpeg = TestImages.jpegBytes(sample.toBufferedImage());
        byte[] withTrailer = TestImages.withTrailingBytes(jpeg, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        FileReport report = FileAnalyzer.analyze(withTrailer, "trailer.jpg");
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("appended after the end")),
                () -> "warnings: " + report.warnings());
    }

    @Test
    @DisplayName("a GIF file is analysed: logical screen, frames, comment extension and palette")
    void wellFormedGif() {
        BufferedImage image = sample.toBufferedImage();
        byte[] gif = TestImages.gifBytes(image);
        FileReport report = FileAnalyzer.analyze(gif, "test.gif");
        assertEquals(ImageFormat.GIF, report.format());
        String text = report.toText();
        assertTrue(text.contains("GIF89a") || text.contains("GIF87a"), text);
        assertTrue(text.contains("Logical screen: 16x12"), text);
        assertTrue(text.contains("Global colour table"), text);
        assertTrue(text.contains("Image descriptor #1"), text);
        assertTrue(text.contains("Trailer"), text);
        assertTrue(text.contains("Frames: 1"), text);
    }

    @Test
    @DisplayName("a GIF comment extension is dumped")
    void gifWithComment() {
        byte[] gif = TestImages.gifBytes(sample.toBufferedImage());
        byte[] withComment = insertGifComment(gif, "hello from a comment block");
        FileReport report = FileAnalyzer.analyze(withComment, "comment.gif");
        assertTrue(report.toText().contains("Comment extension"), report.toText());
        assertTrue(report.toText().contains("hello from a comment block"), report.toText());
    }

    @Test
    @DisplayName("a BMP file is analysed: headers, stride and no warnings for a well formed file")
    void wellFormedBmp() {
        byte[] bmp = TestImages.bmpBytes(sample.toBufferedImage());
        FileReport report = FileAnalyzer.analyze(bmp, "test.bmp");
        assertEquals(ImageFormat.BMP, report.format());
        String text = report.toText();
        assertTrue(text.contains("BITMAPINFOHEADER"), text);
        assertTrue(text.contains("Width: 16 pixels"), text);
        assertTrue(text.contains("Height: 12 pixels"), text);
        assertTrue(text.contains("Bits per pixel: 24"), text);
        assertTrue(text.contains("Row stride"), text);
        assertFalse(report.hasWarnings(), () -> "unexpected warnings: " + report.warnings());
    }

    @Test
    @DisplayName("appended bytes in a BMP are reported with the hint about the height")
    void bmpWithTrailingData() {
        byte[] bmp = TestImages.bmpBytes(sample.toBufferedImage());
        byte[] hidden = new byte[256];
        new Random(5).nextBytes(hidden);
        byte[] withTrailer = TestImages.withTrailingBytes(bmp, hidden);
        FileReport report = FileAnalyzer.analyze(withTrailer, "trailer.bmp");
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("appended after the pixel data")),
                () -> "warnings: " + report.warnings());
        assertTrue(report.toText().contains("increasing the height"), report.toText());
    }

    @Test
    @DisplayName("every truncation of every supported format is analysed without throwing")
    void truncationsAreSafe() {
        byte[][] files = {
                TestImages.pngBytes(sample),
                TestImages.jpegBytes(sample.toBufferedImage()),
                TestImages.gifBytes(sample.toBufferedImage()),
                TestImages.bmpBytes(sample.toBufferedImage()),
        };
        for (byte[] file : files) {
            for (int length = 0; length <= file.length; length += Math.max(1, file.length / 40)) {
                byte[] truncated = Arrays.copyOf(file, length);
                assertReportIsUsable(truncated, "truncated to " + length);
            }
            // Also every single byte position shortened by one, for the first and last 32 bytes.
            for (int length = Math.max(0, file.length - 32); length < file.length; length++) {
                assertReportIsUsable(Arrays.copyOf(file, length), "shortened to " + length);
            }
            for (int length = 0; length < Math.min(32, file.length); length++) {
                assertReportIsUsable(Arrays.copyOf(file, length), "cut to " + length);
            }
        }
    }

    @Test
    @DisplayName("random data behind a valid signature is analysed without throwing")
    void garbageWithSignaturesIsSafe() {
        byte[][] signatures = {
                {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a},
                {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0},
                {'G', 'I', 'F', '8', '9', 'a'},
                {'B', 'M'},
        };
        Random random = new Random(99);
        for (byte[] signature : signatures) {
            for (int iteration = 0; iteration < 25; iteration++) {
                byte[] data = new byte[signature.length + random.nextInt(400)];
                System.arraycopy(signature, 0, data, 0, signature.length);
                for (int i = signature.length; i < data.length; i++) {
                    data[i] = (byte) random.nextInt(256);
                }
                assertReportIsUsable(data, "garbage iteration " + iteration);
            }
        }
    }

    @Test
    @DisplayName("single byte corruptions of a valid PNG and GIF never throw")
    void singleByteCorruptionsAreSafe() {
        for (byte[] file : new byte[][] {TestImages.pngBytes(sample), TestImages.gifBytes(sample.toBufferedImage()),
                TestImages.bmpBytes(sample.toBufferedImage())}) {
            for (int offset = 0; offset < file.length; offset += 7) {
                byte[] corrupted = file.clone();
                corrupted[offset] ^= (byte) 0xa5;
                assertReportIsUsable(corrupted, "corrupted at " + offset);
            }
        }
    }

    @Test
    @DisplayName("an empty file and a file with an unknown signature are reported clearly")
    void emptyAndUnknownFiles() {
        FileReport empty = FileAnalyzer.analyze(new byte[0], "empty.bin");
        assertEquals(ImageFormat.UNKNOWN, empty.format());
        assertTrue(empty.hasWarnings());

        FileReport unknown = FileAnalyzer.analyze("not an image at all".getBytes(StandardCharsets.US_ASCII), "x.txt");
        assertEquals(ImageFormat.UNKNOWN, unknown.format());
        assertTrue(unknown.warnings().stream().anyMatch(warning -> warning.contains("container signature")),
                () -> "warnings: " + unknown.warnings());
        assertTrue(unknown.toText().contains("SHA-256"));
    }

    @Test
    @DisplayName("the report contains the decoded image facts as a cross check")
    void decodedFacts() {
        byte[] png = TestImages.pngBytes(sample);
        FileReport report = FileAnalyzer.analyze(png, "facts.png");
        String text = report.toText();
        assertTrue(text.contains("Decoded image"), text);
        assertTrue(text.contains("Dimensions: 16x12"), text);
        assertTrue(text.contains("Colour model"), text);
    }

    @Test
    @DisplayName("the report can always be rendered as text")
    void reportRendering() {
        FileReport report = FileAnalyzer.analyze(TestImages.pngBytes(sample), "render.png");
        String text = report.toText();
        assertTrue(text.startsWith("StegSolver file analysis"), text);
        assertTrue(text.contains("[Warnings]"), text);
        assertTrue(text.contains("(none)"), text);
        assertNotNull(report.summary());
        assertTrue(report.summary().contains("PNG"));
    }

    private static void assertReportIsUsable(byte[] data, String description) {
        FileReport report = FileAnalyzer.analyze(data, "fuzz.bin");
        assertNotNull(report, description);
        assertNotNull(report.toText(), description);
        assertNotNull(report.warnings(), description);
        assertNotNull(report.format(), description);
        // Rendering must always work, whatever the parser hit.
        assertFalse(report.toText().isEmpty(), description);
    }

    /** Inserts a GIF comment extension right after the header, which keeps the file decodable. */
    private static byte[] insertGifComment(byte[] gif, String comment) {
        int insertAt = 6 + 7; // header + logical screen descriptor
        byte[] flags = {0};
        int globalTableSize = 0;
        if (gif.length > 10) {
            int packed = gif[10] & 0xff;
            if ((packed & 0x80) != 0) {
                globalTableSize = 3 * (1 << ((packed & 0x07) + 1));
            }
        }
        insertAt += globalTableSize;
        byte[] text = comment.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Arrays.copyOfRange(gif, 0, insertAt));
        out.write(0x21);
        out.write(0xfe);
        out.write(text.length);
        out.writeBytes(text);
        out.write(0x00);
        out.writeBytes(Arrays.copyOfRange(gif, insertAt, gif.length));
        assertEquals(0, flags[0]);
        return out.toByteArray();
    }

    @Test
    @DisplayName("the analyser works on a file on disk as well")
    void analyseFromDisk(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path file = tempDir.resolve("disk.png");
        java.nio.file.Files.write(file, TestImages.pngBytes(sample));
        FileReport report = FileAnalyzer.analyze(file);
        assertTrue(report.toText().contains("Width: 16"), report.toText());
        assertEquals("disk.png", report.fileName());

        FileReport missing = FileAnalyzer.analyze(tempDir.resolve("nope.png"));
        assertTrue(missing.hasWarnings());
    }

    @Test
    @DisplayName("ImageIO cross checks are used for indexed images too")
    void indexedImageFacts() {
        BufferedImage indexed = new BufferedImage(8, 4, BufferedImage.TYPE_BYTE_INDEXED);
        byte[] gif = TestImages.encode(indexed, "gif");
        FileReport report = FileAnalyzer.analyze(gif, "indexed.gif");
        assertTrue(report.toText().contains("IndexColorModel"), report.toText());
    }
}
