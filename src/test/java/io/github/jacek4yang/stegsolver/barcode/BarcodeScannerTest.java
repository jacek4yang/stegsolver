package io.github.jacek4yang.stegsolver.barcode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BarcodeFormat;
import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageOps;
import io.github.jacek4yang.stegsolver.core.Roi;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End to end tests of the barcode scanner: real QR symbols are rendered in memory, scanned, and the
 * recovered payload is compared byte for byte.
 */
class BarcodeScannerTest {

    private final BarcodeScanner scanner = new BarcodeScanner();

    @Test
    @DisplayName("a text QR code is found and its text returned")
    void textQrCode() {
        ImageData image = TestImages.qrCode("hello stegsolver", 4);
        ScanResult result = scanner.scan(image, ScanOptions.defaults());
        assertEquals(1, result.count(), () -> "expected one symbol, notes: " + result.notes());
        BarcodeHit hit = result.hits().get(0);
        assertEquals(BarcodeFormat.QR_CODE, hit.format());
        assertEquals("hello stegsolver", hit.text());
        assertTrue(hit.hasBinaryPayload(), "a QR code always carries byte segments");
        assertEquals("hello stegsolver", new String(hit.payload(), StandardCharsets.US_ASCII));
        assertEquals(PayloadType.TEXT_ASCII, hit.payloadInfo().type());
        assertTrue(result.summary().contains("1 symbol"));
    }

    @Test
    @DisplayName("a binary payload keeps its exact bytes, including NUL and high bytes")
    void binaryPayloadIsPreservedExactly() {
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        ImageData image = TestImages.qrCodeForBytes(payload, 4);
        ScanResult result = scanner.scan(image, ScanOptions.defaults());
        assertEquals(1, result.count(), () -> "notes: " + result.notes());
        BarcodeHit hit = result.hits().get(0);
        assertTrue(hit.hasBinaryPayload());
        assertArrayEquals(payload, hit.payload(), "the payload bytes must survive unchanged");

        // The decoded text is a different thing entirely: it is the same bytes interpreted with some
        // charset, and re-encoding that text (here as UTF-8) does not reproduce the payload. That is
        // exactly why the payload is never rebuilt from the text.
        assertNotNull(hit.text());
        byte[] viaText = hit.text().getBytes(StandardCharsets.UTF_8);
        assertFalse(java.util.Arrays.equals(payload, viaText),
                "re-encoding the decoded text must never be used as the payload");
        assertTrue(hit.decoderRawBytes() != null && hit.decoderRawBytes().length > 0,
                "ZXing raw codewords are kept separately");
        assertFalse(java.util.Arrays.equals(hit.payload(), hit.decoderRawBytes()),
                "the raw codewords of a QR symbol are not the payload: mode and padding bits are included");
    }

    @Test
    @DisplayName("a ZIP archive hidden in a QR code is detected with the right extension and saved byte exactly")
    void zipPayloadInQrCode() throws Exception {
        byte[] archive = createZip();
        ImageData image = TestImages.qrCodeForBytes(archive, 3);
        ScanResult result = scanner.scan(image, ScanOptions.defaults());
        assertEquals(1, result.count(), () -> "notes: " + result.notes());
        BarcodeHit hit = result.hits().get(0);
        assertArrayEquals(archive, hit.payload());
        assertEquals(PayloadType.ZIP, hit.payloadInfo().type());
        assertEquals("zip", hit.payloadInfo().suggestedExtension());
        assertTrue(hit.payloadInfo().isText() == false);
        assertTrue(hit.listLabel().contains("ZIP archive"), hit.listLabel());
    }

    @Test
    @DisplayName("several symbols in one image are all found")
    void multipleSymbols() {
        ImageData first = TestImages.qrCode("first symbol", 3);
        ImageData second = TestImages.qrCode("second symbol", 3);
        ImageData third = TestImages.qrCode("third symbol", 3);
        ImageData canvas = TestImages.sideBySide(first, second, third);
        ScanResult result = scanner.scan(canvas, ScanOptions.defaults());
        assertEquals(3, result.count(), () -> "notes: " + result.notes());
        java.util.List<String> texts = result.hits().stream().map(BarcodeHit::text).sorted().toList();
        assertEquals(java.util.List.of("first symbol", "second symbol", "third symbol"), texts);
    }

    @Test
    @DisplayName("scanning a dragged region only finds the symbol inside it")
    void regionScan() {
        ImageData symbol = TestImages.qrCode("inside the region", 4);
        ImageData other = TestImages.qrCode("outside the region", 4);
        ImageData canvas = TestImages.overlay(TestImages.whiteCanvas(600, 400), other, 20, 20);
        canvas = TestImages.overlay(canvas, symbol, 300, 150);
        // Scanning everything finds both symbols.
        assertEquals(2, scanner.scan(canvas, ScanOptions.defaults()).count());
        // Scanning only the bottom right region finds the second one.
        ScanResult region = scanner.scan(canvas, new Roi(280, 130, 320, 270), ScanOptions.defaults());
        assertEquals(1, region.count(), () -> "notes: " + region.notes());
        assertEquals("inside the region", region.hits().get(0).text());
        BarcodeHit hit = region.hits().get(0);
        assertTrue(hit.bounds().x() >= 280, "the reported bounds must be in image coordinates: " + hit.bounds());
        assertTrue(hit.bounds().maxX() <= 600);
        assertTrue(hit.bounds().y() >= 130);
        assertTrue(hit.bounds().maxY() <= 400);
    }

    @Test
    @DisplayName("an inverted symbol is found when inversion fallbacks are enabled")
    void invertedSymbol() {
        ImageData image = TestImages.invert(TestImages.qrCode("negative", 4));
        ScanResult result = scanner.scan(image, ScanOptions.defaults());
        assertEquals(1, result.count(), () -> "notes: " + result.notes());
        assertEquals("negative", result.hits().get(0).text());
        assertTrue(result.hits().stream().anyMatch(BarcodeHit::inverted),
                "the hit should be marked as found in an inverted image");
        // The payload is still the same bytes, independent of the polarity that found it.
        assertEquals("negative", new String(result.hits().get(0).payload(), StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("a quarter turned QR code is decoded and its position is reported correctly")
    void rotatedQrCode() {
        ImageData symbol = TestImages.qrCode("rotated", 4);
        ImageData original = TestImages.pasteOnCanvas(symbol, 400, 200, 40, 60);
        ImageData rotated = ImageOps.rotateCounterClockwise(original);
        assertEquals(200, rotated.width());
        assertEquals(400, rotated.height());
        ScanResult result = scanner.scan(rotated, ScanOptions.defaults());
        assertEquals(1, result.count(), () -> "notes: " + result.notes());
        BarcodeHit hit = result.hits().get(0);
        assertEquals("rotated", hit.text());
        // A QR symbol is orientation independent, so no bitmap rotation was needed; the position must still
        // come back in the coordinates of the image that was scanned.
        assertTrue(hit.bounds().x() >= 55 && hit.bounds().maxX() <= 60 + symbol.width(),
                "bounds " + hit.bounds() + " should be inside the 200x400 rotated image");
        assertTrue(hit.bounds().maxX() <= 200 && hit.bounds().maxY() <= 400);
        assertEquals(0, hit.rotationDegrees());
    }

    @Test
    @DisplayName("a quarter turned 1D barcode needs a turn and its position is reported correctly")
    void rotatedOneDimensionalBarcode() {
        ImageData barcode = TestImages.code128("STEG-128", 260, 90);
        ImageData upright = TestImages.pasteOnCanvas(barcode, 400, 320, 50, 100);
        ScanResult uprightResult = scanner.scan(upright, ScanOptions.defaults());
        assertEquals(1, uprightResult.count(), () -> "notes: " + uprightResult.notes());
        assertEquals("STEG-128", uprightResult.hits().get(0).text());
        assertEquals(null, uprightResult.hits().get(0).metadata().get("orientation"),
                "an upright barcode needs no orientation handling");

        // Turn the whole canvas a quarter turn counter clockwise. The barcode then occupies
        // x' = 100..189, y' = 90..349 in the rotated 320x400 image.
        ImageData turned = ImageOps.rotateCounterClockwise(upright);
        assertEquals(320, turned.width());
        assertEquals(400, turned.height());
        ScanResult turnedResult = scanner.scan(turned, ScanOptions.defaults());
        assertEquals(1, turnedResult.count(), () -> "notes: " + turnedResult.notes());
        BarcodeHit hit = turnedResult.hits().get(0);
        assertEquals(BarcodeFormat.CODE_128, hit.format());
        assertEquals("STEG-128", hit.text());
        // ZXing's 1D reader rotates the bitmap itself when TRY_HARDER is set and records that it did so.
        String orientation = hit.metadata().get("orientation");
        assertTrue("90".equals(orientation) || "270".equals(orientation),
                "the reader should report the internal rotation it needed but said " + orientation
                        + " in " + hit.metadata());
        // In the scanned 320x400 image the barcode runs vertically, occupying x 100..189 and y 90..349.
        Roi symbolRegion = new Roi(100, 90, 90, 260).expand(8, 320, 400);
        assertTrue(symbolRegion.contains((int) hit.bounds().x(), (int) hit.bounds().y()),
                "bounds " + hit.bounds() + " must fall inside the symbol region " + symbolRegion);
        assertTrue(symbolRegion.contains((int) hit.bounds().maxX() - 1, (int) hit.bounds().maxY() - 1),
                "bounds " + hit.bounds() + " must fall inside the symbol region " + symbolRegion);
    }

    @Test
    @DisplayName("a symbol on a transparent background is composited over white and still decodes")
    void transparentBackground() {
        ImageData symbol = TestImages.qrCode("transparent", 4);
        int[] pixels = new int[symbol.pixelCount()];
        for (int i = 0; i < pixels.length; i++) {
            // White becomes fully transparent, black stays opaque: the naive luminance conversion would
            // turn this into black on black.
            pixels[i] = symbol.pixels()[i] == 0xffffffff ? 0x00ffffff : 0xff000000;
        }
        ImageData image = ImageData.of(symbol.width(), symbol.height(), pixels, true);
        ScanResult result = scanner.scan(image, ScanOptions.defaults());
        assertEquals(1, result.count(), () -> "notes: " + result.notes());
        assertEquals("transparent", result.hits().get(0).text());
    }

    @Test
    @DisplayName("noisy images without symbols report nothing instead of failing")
    void noSymbolFound() {
        ImageData noise = TestImages.randomRgb(160, 120, 7);
        ScanResult result = scanner.scan(noise, ScanOptions.quick());
        assertTrue(result.isEmpty());
        assertTrue(result.summary().contains("No barcode"));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("No symbol could be decoded")),
                () -> "notes: " + result.notes());
        assertEquals(0, result.count());
    }

    @Test
    @DisplayName("an empty region is reported instead of being scanned")
    void emptyRegion() {
        ImageData image = TestImages.qrCode("anything", 3);
        ScanResult result = scanner.scan(image, new Roi(0, 0, 0, 0), ScanOptions.defaults());
        assertTrue(result.isEmpty());
        assertEquals("The selected region is empty", result.notes().get(0));
    }

    @Test
    @DisplayName("a symbology filter restricts the formats that are searched for")
    void formatFilter() {
        ImageData image = TestImages.qrCode("filtered", 4);
        ScanResult onlyQr = scanner.scan(image, ScanOptions.defaultsFor(Set.of(BarcodeFormat.QR_CODE)));
        assertEquals(1, onlyQr.count());
        ScanResult onlyEan = scanner.scan(image, ScanOptions.defaultsFor(Set.of(BarcodeFormat.EAN_13)));
        assertTrue(onlyEan.isEmpty());
    }

    @Test
    @DisplayName("the same symbol found by several readers is reported once")
    void duplicatesAreRemoved() {
        ImageData image = TestImages.qrCode("once", 4);
        ScanResult result = scanner.scan(image, ScanOptions.thorough());
        assertEquals(1, result.count(), () -> "duplicates were reported: " + result.hits());
    }

    @Test
    @DisplayName("a big image with a small symbol is still scanned within the time budget")
    void largeImageWithSmallSymbol() {
        ImageData image = TestImages.pasteOnCanvas(TestImages.qrCode("needle", 2), 2000, 1400, 1500, 1000);
        ScanResult result = scanner.scan(image, ScanOptions.defaults());
        assertEquals(1, result.count(), () -> "notes: " + result.notes());
        assertEquals("needle", result.hits().get(0).text());
        assertTrue(result.elapsedMillis() < 30_000);
    }

    @Test
    @DisplayName("metadata keeps the three payload representations apart")
    void metadataExplainsTheThreeRepresentations() {
        ImageData image = TestImages.qrCodeForBytes("binary\u0000payload".getBytes(StandardCharsets.ISO_8859_1), 4);
        BarcodeHit hit = scanner.scan(image, ScanOptions.defaults()).hits().get(0);
        assertNotNull(hit.metadata().get("symbology"));
        assertTrue(hit.metadata().get("byteSegments").contains("segment"), hit.metadata().toString());
        assertTrue(hit.metadata().get("decoderRawBytes").contains("bytes"));
        assertTrue(hit.metadata().containsKey("error_correction_level"), hit.metadata().toString());
        assertFalse(hit.byteSegments().isEmpty());
        assertEquals(hit.byteSegments().get(0).length, hit.payloadSize());
    }

    @Test
    @DisplayName("an unknown symbology filter cannot make the scanner crash")
    void scanWithRandomOptions() {
        ImageData image = TestImages.randomRgb(32, 32, 3);
        ScanOptions options = ScanOptions.defaults()
                .withDeepSearch(true)
                .withTimeBudgetMillis(1000)
                .withMultipleSymbols(false);
        ScanResult result = scanner.scan(image, options);
        assertNotNull(result.hits());
        assertNotNull(result.notes());
    }

    @Test
    @DisplayName("concatenating byte segments is exact and returns null when there are none")
    void concatenation() {
        assertArrayEquals(new byte[] {1, 2, 3, 4},
                BarcodeScanner.concatenate(java.util.List.of(new byte[] {1, 2}, new byte[] {3, 4})));
        assertEquals(null, BarcodeScanner.concatenate(java.util.List.of()));
        assertEquals(null, BarcodeScanner.concatenate(null));
        assertEquals(null, BarcodeScanner.concatenate(java.util.List.of(new byte[0])));
    }

    private static byte[] createZip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("flag.txt"));
            zip.write("flag{stegsolver}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("random.bin"));
            byte[] random = new byte[64];
            new Random(1234).nextBytes(random);
            zip.write(random);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
