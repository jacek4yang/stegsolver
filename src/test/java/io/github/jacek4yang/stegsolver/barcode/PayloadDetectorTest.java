package io.github.jacek4yang.stegsolver.barcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayloadDetectorTest {

    @Test
    @DisplayName("known container signatures are recognised")
    void signatures() {
        assertEquals(PayloadType.ZIP, detect(0x50, 0x4b, 0x03, 0x04, 0x14, 0x00));
        assertEquals(PayloadType.ZIP, detect(0x50, 0x4b, 0x05, 0x06, 0x00, 0x00));
        assertEquals(PayloadType.SEVEN_ZIP, detect(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c));
        assertEquals(PayloadType.GZIP, detect(0x1f, 0x8b, 0x08, 0x00));
        assertEquals(PayloadType.RAR, detect(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00));
        assertEquals(PayloadType.BZIP2, detect(0x42, 0x5a, 0x68, 0x39));
        assertEquals(PayloadType.XZ, detect(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00));
        assertEquals(PayloadType.PNG, detect(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a));
        assertEquals(PayloadType.JPEG, detect(0xff, 0xd8, 0xff, 0xe0));
        assertEquals(PayloadType.GIF, detect(0x47, 0x49, 0x46, 0x38, 0x39, 0x61));
        assertEquals(PayloadType.GIF, detect(0x47, 0x49, 0x46, 0x38, 0x37, 0x61));
        assertEquals(PayloadType.BMP, detect(0x42, 0x4d, 0x36, 0x00));
        assertEquals(PayloadType.PDF, detect(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37));
        assertEquals(PayloadType.ELF, detect(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01));
        assertEquals(PayloadType.PE, detect(0x4d, 0x5a, 0x90, 0x00));
        assertEquals(PayloadType.JAVA_CLASS, detect(0xca, 0xfe, 0xba, 0xbe));
        assertEquals(PayloadType.WEBP, detect("RIFF____WEBPVP8 "));
    }

    private PayloadType detect(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return PayloadDetector.detectType(data);
    }

    private PayloadType detect(String ascii) {
        return PayloadDetector.detectType(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("suggested extensions match the detected container")
    void suggestedExtensions() {
        assertEquals("zip", PayloadType.ZIP.suggestedExtension());
        assertEquals(".zip", PayloadType.ZIP.suggestedSuffix());
        assertEquals("7z", PayloadType.SEVEN_ZIP.suggestedExtension());
        assertEquals("gz", PayloadType.GZIP.suggestedExtension());
        assertEquals("rar", PayloadType.RAR.suggestedExtension());
        assertEquals("png", PayloadType.PNG.suggestedExtension());
        assertEquals("jpg", PayloadType.JPEG.suggestedExtension());
        assertEquals("gif", PayloadType.GIF.suggestedExtension());
        assertEquals("pdf", PayloadType.PDF.suggestedExtension());
        assertEquals("elf", PayloadType.ELF.suggestedExtension());
        assertEquals("exe", PayloadType.PE.suggestedExtension());
        assertEquals("bin", PayloadType.BINARY.suggestedExtension());
        assertEquals("txt", PayloadType.TEXT_ASCII.suggestedExtension());
        assertEquals("", PayloadType.EMPTY.suggestedSuffix());
    }

    @Test
    @DisplayName("a real ZIP archive is recognised and its entry count reported")
    void realZipArchive() throws Exception {
        byte[] archive = zipWithEntries(3);
        PayloadInfo info = PayloadDetector.detect(archive, null);
        assertEquals(PayloadType.ZIP, info.type());
        assertEquals(archive.length, info.size());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("3 entries")),
                "expected an entry count in " + info.notes());
        assertFalse(info.isText());
        assertEquals(64, info.sha256().length());
    }

    @Test
    @DisplayName("text payloads are recognised and kept as text")
    void textPayloads() {
        byte[] ascii = "hello stegsolver".getBytes(StandardCharsets.US_ASCII);
        PayloadInfo info = PayloadDetector.detect(ascii, "hello stegsolver");
        assertEquals(PayloadType.TEXT_ASCII, info.type());
        assertTrue(info.isText());
        assertEquals("hello stegsolver", info.text());
        assertEquals(ascii.length, info.size());

        byte[] utf8 = "héllo wörld ✓".getBytes(StandardCharsets.UTF_8);
        assertEquals(PayloadType.TEXT_UTF8, PayloadDetector.detect(utf8, null).type());
        assertTrue(PayloadDetector.detect(utf8, null).text().contains("héllo"));
    }

    @Test
    @DisplayName("base64 text is detected and its decoded content is described, never decoded into the payload")
    void base64Detection() {
        String base64 = java.util.Base64.getEncoder().encodeToString(
                "PK\u0003\u0004fake zip".getBytes(StandardCharsets.ISO_8859_1));
        byte[] payload = base64.getBytes(StandardCharsets.US_ASCII);
        PayloadInfo info = PayloadDetector.detect(payload, null);
        assertEquals(PayloadType.BASE64_TEXT, info.type());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("base64")),
                "expected base64 notes but got " + info.notes());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("ZIP archive")),
                "expected the decoded content to be classified: " + info.notes());
        assertTrue(PayloadDetector.looksLikeBase64(payload));
        assertFalse(PayloadDetector.looksLikeBase64("not base64!!".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("a binary payload is reported as binary, not silently treated as text")
    void binaryPayload() {
        byte[] random = new byte[256];
        new Random(1).nextBytes(random);
        PayloadInfo info = PayloadDetector.detect(random, null);
        assertEquals(PayloadType.BINARY, info.type());
        assertTrue(info.entropy() > 7.0, "random data should have high entropy but was " + info.entropy());
        assertTrue(info.distinctBytes() > 100, "expected many distinct byte values");
        assertTrue(info.looksCompressedOrEncrypted(),
                "random data must look compressed, normalised entropy was " + info.normalisedEntropy());
        assertFalse(info.isText());
    }

    @Test
    @DisplayName("an empty payload with no text is reported as empty")
    void emptyPayload() {
        PayloadInfo info = PayloadDetector.detect(new byte[0], null);
        assertEquals(PayloadType.EMPTY, info.type());
        assertEquals(0, info.size());
        assertTrue(info.isEmpty());
        assertEquals(0.0, info.normalisedEntropy(), 1e-9);
    }

    @Test
    @DisplayName("a symbol that carries text but no byte segments is reported as text only")
    void textOnlySymbol() {
        PayloadInfo info = PayloadDetector.detect(new byte[0], "just text");
        assertEquals(PayloadType.TEXT_ASCII, info.type());
        assertEquals(0, info.size());
        assertEquals("just text", info.text());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("no payload bytes")),
                "expected an explicit note: " + info.notes());
    }

    @Test
    @DisplayName("entropy of a constant payload is zero and of a two symbol payload is one")
    void entropy() {
        assertEquals(0.0, PayloadDetector.entropy(new byte[64]), 1e-9);
        byte[] checker = new byte[64];
        for (int i = 0; i < checker.length; i++) {
            checker[i] = (byte) (i % 2);
        }
        assertEquals(1.0, PayloadDetector.entropy(checker), 1e-9);
    }

    @Test
    @DisplayName("the SHA-256 of a payload is stable and hex encoded")
    void sha256() {
        byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PayloadDetector.sha256(data));
        assertEquals(PayloadDetector.sha256(data), PayloadDetector.sha256(data.clone()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "hello", "line\nbreak", "tab\there"})
    @DisplayName("text detection never throws on short inputs")
    void textDetectionIsRobust(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        PayloadInfo info = PayloadDetector.detect(data, text);
        assertNotNull(info.type());
        assertNotNull(info.notes());
    }

    @Test
    @DisplayName("a PNG payload reports its dimensions without decoding it")
    void pngDescription() {
        byte[] pngHeader = new byte[40];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, pngHeader, 0, 8);
        pngHeader[16] = 0;
        pngHeader[17] = 0;
        pngHeader[18] = 0x01;
        pngHeader[19] = 0x40; // width 320
        pngHeader[20] = 0;
        pngHeader[21] = 0;
        pngHeader[22] = 0;
        pngHeader[23] = (byte) 0xf0; // height 240
        pngHeader[24] = 8;
        pngHeader[25] = 6;
        PayloadInfo info = PayloadDetector.detect(pngHeader, null);
        assertEquals(PayloadType.PNG, info.type());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("320x240")),
                "expected the dimensions in " + info.notes());
    }

    @Test
    @DisplayName("cleanup: gzip notes mention extra fields, which are a hiding place")
    void gzipDescription() {
        byte[] gzip = {(byte) 0x1f, (byte) 0x8b, 8, 0x04, 0, 0, 0, 0, 0, 0};
        PayloadInfo info = PayloadDetector.detect(gzip, null);
        assertEquals(PayloadType.GZIP, info.type());
        assertTrue(info.notes().stream().anyMatch(note -> note.contains("Extra fields")),
                "expected the extra field warning: " + info.notes());
    }

    private static byte[] zipWithEntries(int entries) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (int i = 0; i < entries; i++) {
                zip.putNextEntry(new java.util.zip.ZipEntry("file" + i + ".txt"));
                zip.write(("content " + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("payload types are grouped so the user interface can warn before opening anything")
    void categories() {
        assertTrue(PayloadType.ZIP.isArchive());
        assertTrue(PayloadType.ELF.isExecutable());
        assertTrue(PayloadType.PE.isPotentiallyUnsafe());
        assertTrue(PayloadType.PDF.isPotentiallyUnsafe());
        assertFalse(PayloadType.PNG.isPotentiallyUnsafe());
        assertTrue(PayloadType.TEXT_UTF8.isText());
        assertEquals("SEVEN ZIP", PayloadType.SEVEN_ZIP.badge());
        assertEquals(Arrays.asList(PayloadType.values()).size(),
                (int) Arrays.stream(PayloadType.values()).map(PayloadType::description).distinct().count());
    }
}
