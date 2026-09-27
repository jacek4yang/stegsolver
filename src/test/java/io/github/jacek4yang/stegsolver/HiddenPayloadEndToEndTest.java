package io.github.jacek4yang.stegsolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.barcode.PayloadDetector;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.barcode.PayloadType;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.extract.DataExtractor;
import io.github.jacek4yang.stegsolver.extract.ExtractionOptions;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workflow the tool exists for, end to end: a payload is hidden in the least significant bits of a
 * photo, recovered by the extractor and identified by the payload detector.
 *
 * <p>This is the integration test that ties the extraction and the payload classification together, and
 * it checks the detail that matters most for binary data: the recovered bytes must be identical to the
 * bytes that were hidden, and the identified type must match.</p>
 */
class HiddenPayloadEndToEndTest {

    @Test
    @DisplayName("a ZIP archive hidden in the RGB least significant bits is recovered byte exactly")
    void zipArchiveInTheLeastSignificantBits() throws Exception {
        byte[] archive = zipWithFlag();
        ImageData cover = TestImages.randomRgb(220, 120, 4711);
        ImageData stego = embedInLeastSignificantBits(cover, archive);

        // Extraction with the settings a user would pick: RGB LSBs, LSB first, row by row.
        ExtractionOptions options = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true)
                .withLsbFirst(true);
        byte[] extracted = DataExtractor.extract(stego, options);

        assertArrayEquals(archive, java.util.Arrays.copyOf(extracted, archive.length),
                "the hidden bytes must come back unchanged");

        PayloadInfo info = PayloadDetector.detect(extracted, null);
        // The extract contains the archive followed by the cover image's remaining LSBs, so the
        // detector sees a ZIP stream that is longer than the archive itself: the signature still wins.
        assertEquals(PayloadType.ZIP, info.type());
        assertEquals("zip", info.suggestedExtension());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("Central directory")),
                () -> "the ZIP structure should be described in the notes: " + info.notes());
    }

    @Test
    @DisplayName("a message hidden in one channel is recovered and classified as text")
    void textMessageInTheRedChannel() {
        String message = "the flag is under the third bit plane";
        ImageData cover = TestImages.randomRgb(160, 80, 99);
        ImageData stego = embedTextInPlane(cover, Channel.RED, 0, message);

        ExtractionOptions options = ExtractionOptions.none().with(Channel.RED, 0, true).withLsbFirst(true);
        byte[] extracted = DataExtractor.extract(stego, options);

        byte[] expected = message.getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(expected, java.util.Arrays.copyOf(extracted, expected.length));
        PayloadInfo info = PayloadDetector.detect(java.util.Arrays.copyOf(extracted, expected.length), null);
        assertEquals(PayloadType.TEXT_ASCII, info.type());
        assertEquals(message, info.text());
    }

    @Test
    @DisplayName("extracting only a region of the stego image still recovers the payload it contains")
    void payloadInARegionOfTheImage() throws Exception {
        byte[] payload = "PNG-like payload".getBytes(StandardCharsets.US_ASCII);
        // Hide the payload in a 64x16 band, leaving the rest of the image untouched.
        Roi region = new Roi(20, 40, 64, 16);
        ImageData cover = TestImages.randomRgb(200, 100, 5);
        int[] pixels = cover.pixels().clone();
        int bitIndex = 0;
        outer:
        for (int y = region.y(); y < region.maxY(); y++) {
            for (int x = region.x(); x < region.maxX(); x++) {
                if (bitIndex >= payload.length * 8) {
                    break outer;
                }
                int bit = (payload[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1;
                pixels[y * cover.width() + x] = (pixels[y * cover.width() + x] & 0xfffeffff) | (bit << 16);
                bitIndex++;
            }
        }
        ImageData stego = ImageData.opaque(cover.width(), cover.height(), pixels);

        ExtractionOptions options = ExtractionOptions.none().with(Channel.RED, 0, true);
        byte[] extracted = DataExtractor.extract(stego, region, options);
        // Reading the whole image gives the region's bits first, but with the surrounding bits in between.
        byte[] wholeImage = DataExtractor.extract(stego, options);
        assertArrayEquals(payload, java.util.Arrays.copyOf(extracted, payload.length));
        // One bit per pixel, packed most significant bit first: 1024 pixels are 128 bytes.
        assertEquals(region.area() / 8, extracted.length);
        assertTrue(wholeImage.length > extracted.length);
    }

    /** Embeds {@code payload} in the least significant bit of red, green and blue, in that order. */
    private static ImageData embedInLeastSignificantBits(ImageData cover, byte[] payload) {
        int[] pixels = cover.pixels().clone();
        Channel[] channels = {Channel.RED, Channel.GREEN, Channel.BLUE};
        int bitIndex = 0;
        for (int pixelIndex = 0; pixelIndex < pixels.length && bitIndex < payload.length * 8; pixelIndex++) {
            for (Channel channel : channels) {
                if (bitIndex >= payload.length * 8) {
                    break;
                }
                int bit = (payload[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1;
                int mask = ~(1 << channel.shift());
                pixels[pixelIndex] = (pixels[pixelIndex] & mask) | (bit << channel.shift());
                bitIndex++;
            }
        }
        return ImageData.opaque(cover.width(), cover.height(), pixels);
    }

    /** Embeds a text message in one bit plane of one channel. */
    private static ImageData embedTextInPlane(ImageData cover, Channel channel, int plane, String message) {
        byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
        int[] pixels = cover.pixels().clone();
        int shift = channel.shift() + plane;
        for (int bitIndex = 0; bitIndex < bytes.length * 8; bitIndex++) {
            int bit = (bytes[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1;
            pixels[bitIndex] = (pixels[bitIndex] & ~(1 << shift)) | (bit << shift);
        }
        return ImageData.opaque(cover.width(), cover.height(), pixels);
    }

    private static byte[] zipWithFlag() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("flag.txt"));
            zip.write("flag{least_significant_bits}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
