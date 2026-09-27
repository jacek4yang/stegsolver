package io.github.jacek4yang.stegsolver.barcode;

import static org.junit.jupiter.api.Assertions.*;
import com.google.zxing.common.BitArray;
import com.google.zxing.qrcode.decoder.*;
import com.google.zxing.qrcode.encoder.*;
import io.github.jacek4yang.stegsolver.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;

class StructuredAppendScanTest {
    @TempDir Path dir;

    @Test void scannerPreservesUnrelatedAppendHeadersInOneImage() throws Exception {
        ImageData first = symbol(0, 2, 7, new byte[] {0, 1, (byte) 255});
        ImageData second = symbol(1, 2, 9, new byte[] {2, 3, (byte) 254});
        var result = new BarcodeScanner().scan(pair(first, second), ScanOptions.defaults());
        assertEquals(2, result.count());
        assertTrue(result.hits().stream().allMatch(hit -> hit.structuredAppend() != null));
        var merged = StructuredAppendMerger.merge(result.hits());
        assertTrue(merged.merged().isEmpty());
        assertEquals(2, merged.unmerged().size());
    }

    @Test void scanMergeAndSaveKeepsEachByteSegmentExact() throws Exception {
        byte[] a = {0, 1, (byte) 128, (byte) 255};
        byte[] b = {0x50, 0x4b, 3, 4};
        var result = new BarcodeScanner().scan(pair(symbol(1, 2, 17, b), symbol(0, 2, 17, a)), ScanOptions.defaults());
        assertEquals(2, result.count());
        var merged = StructuredAppendMerger.merge(result.hits()).merged().getFirst();
        byte[] expected = {0, 1, (byte) 128, (byte) 255, 0x50, 0x4b, 3, 4};
        assertArrayEquals(expected, merged.payload());
        assertEquals(2, merged.byteSegments().size());
        assertFalse(Arrays.equals(merged.payload(), merged.decoderRawBytes()));
        Path saved = dir.resolve("payload.bin");
        Files.write(saved, merged.payload());
        assertArrayEquals(expected, Files.readAllBytes(saved));
    }

    @Test void multipleByteSegmentsWithinOneSymbolStaySeparate() throws Exception {
        byte[] a = {0, 1, (byte) 255};
        byte[] b = {7, 8, 9};
        var hit = new BarcodeScanner().scan(symbol(-1, 0, 0, a, b), ScanOptions.defaults()).hits().getFirst();
        assertEquals(2, hit.byteSegments().size());
        assertArrayEquals(a, hit.byteSegments().get(0));
        assertArrayEquals(b, hit.byteSegments().get(1));
        assertArrayEquals(new byte[] {0, 1, (byte) 255, 7, 8, 9}, hit.payload());
    }

    private static ImageData pair(ImageData a, ImageData b) {
        int width = a.width() + b.width() + 64;
        int height = Math.max(a.height(), b.height()) + 64;
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, 0xffffffff);
        for (int y = 0; y < a.height(); y++) System.arraycopy(a.pixels(), y * a.width(), pixels, (y + 32) * width + 16, a.width());
        for (int y = 0; y < b.height(); y++) System.arraycopy(b.pixels(), y * b.width(), pixels, (y + 32) * width + a.width() + 48, b.width());
        return ImageData.opaque(width, height, pixels);
    }

    /** Test-only construction: ZXing's public encoder cannot emit Structured Append headers. */
    private static ImageData symbol(int index, int total, int parity, byte[]... segments) throws Exception {
        Version version = Version.getVersionForNumber(3);
        ErrorCorrectionLevel level = ErrorCorrectionLevel.L;
        BitArray bits = new BitArray();
        if (index >= 0) {
            bits.appendBits(Mode.STRUCTURED_APPEND.getBits(), 4);
            bits.appendBits((index << 4) | (total - 1), 8);
            bits.appendBits(parity, 8);
        }
        for (byte[] segment : segments) {
            bits.appendBits(Mode.BYTE.getBits(), 4);
            bits.appendBits(segment.length, 8);
            for (byte value : segment) bits.appendBits(value & 255, 8);
        }
        var ec = version.getECBlocksForLevel(level);
        int dataBytes = version.getTotalCodewords() - ec.getTotalECCodewords();
        var terminate = Encoder.class.getDeclaredMethod("terminateBits", int.class, BitArray.class);
        terminate.setAccessible(true);
        terminate.invoke(null, dataBytes, bits);
        var interleave = Encoder.class.getDeclaredMethod("interleaveWithECBytes", BitArray.class, int.class, int.class, int.class);
        interleave.setAccessible(true);
        bits = (BitArray) interleave.invoke(null, bits, version.getTotalCodewords(), dataBytes, ec.getNumBlocks());
        int size = version.getDimensionForVersion();
        ByteMatrix matrix = new ByteMatrix(size, size);
        var build = Class.forName("com.google.zxing.qrcode.encoder.MatrixUtil").getDeclaredMethod(
                "buildMatrix", BitArray.class, ErrorCorrectionLevel.class, Version.class, int.class, ByteMatrix.class);
        build.setAccessible(true);
        build.invoke(null, bits, level, version, 0, matrix);
        int width = (size + 8) * 6;
        int[] pixels = new int[width * width];
        Arrays.fill(pixels, 0xffffffff);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            if (matrix.get(x, y) == 1) for (int dy = 0; dy < 6; dy++) for (int dx = 0; dx < 6; dx++)
                pixels[((y + 4) * 6 + dy) * width + (x + 4) * 6 + dx] = 0xff000000;
        return ImageData.opaque(width, width, pixels);
    }
}
