package io.github.jacek4yang.stegsolver.barcode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import io.github.jacek4yang.stegsolver.core.Roi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StructuredAppendMergerTest {

    @Test
    @DisplayName("the raw sequence value is decoded into position, count and parity")
    void decoding() {
        StructuredAppend append = StructuredAppend.fromRawSequence(0x12, 0xab);
        assertEquals(1, append.index());
        assertEquals(3, append.total());
        assertEquals(0xab, append.parity());
        assertEquals(0x12, append.sequenceValue());
        assertEquals("Structured Append symbol 2 of 3 (parity 0xab)", append.describe());
        assertEquals(16, StructuredAppend.fromRawSequence(0x0f, 0).total());
    }

    @Test
    @DisplayName("ZXing metadata is turned into structured append information")
    void fromResult() {
        Result result = new Result("part", null, null, BarcodeFormat.QR_CODE);
        assertNull(StructuredAppendMerger.fromResult(result));
        result.putMetadata(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE, 0x21);
        result.putMetadata(ResultMetadataType.STRUCTURED_APPEND_PARITY, 0x7f);
        StructuredAppend append = StructuredAppendMerger.fromResult(result);
        assertEquals(2, append.index());
        assertEquals(2, append.total());
        assertEquals(0x7f, append.parity());
    }

    @Test
    @DisplayName("parts of one sequence are merged in the right order, whatever order they arrive in")
    void mergingOutOfOrder() {
        byte[] first = "first ".getBytes(StandardCharsets.US_ASCII);
        byte[] second = "second ".getBytes(StandardCharsets.US_ASCII);
        byte[] third = "third".getBytes(StandardCharsets.US_ASCII);
        BarcodeHit part3 = hit(2, 3, 0x5a, third, "third");
        BarcodeHit part1 = hit(0, 3, 0x5a, first, "first ");
        BarcodeHit part2 = hit(1, 3, 0x5a, second, "second ");

        List<String> notes = new java.util.ArrayList<>();
        BarcodeHit merged = StructuredAppendMerger.mergeSequence(List.of(part3, part1, part2), notes);
        assertArrayEquals("first second third".getBytes(StandardCharsets.US_ASCII), merged.payload());
        assertEquals("first second third", merged.text());
        assertEquals(1, notes.size(), () -> "notes: " + notes);
        assertTrue(notes.get(0).contains("complete structured append sequence of 3"), notes.toString());
        assertEquals("3", merged.metadata().get("structuredAppendTotal"));
        assertNull(merged.structuredAppend());
    }

    @Test
    @DisplayName("an incomplete sequence is merged and the missing parts are reported")
    void mergingIncompleteSequence() {
        BarcodeHit part1 = hit(0, 4, 0x11, "A".getBytes(StandardCharsets.US_ASCII), "A");
        BarcodeHit part3 = hit(2, 4, 0x11, "C".getBytes(StandardCharsets.US_ASCII), "C");
        List<String> notes = new java.util.ArrayList<>();
        BarcodeHit merged = StructuredAppendMerger.mergeSequence(List.of(part1, part3), notes);
        assertEquals("AC", merged.text());
        assertEquals(2, merged.payloadSize());
        assertTrue(notes.get(0).contains("missing"), notes.toString());
        assertTrue(notes.get(0).contains("2"), notes.toString());
    }

    @Test
    @DisplayName("duplicate parts are ignored instead of being concatenated twice")
    void duplicatesAreIgnored() {
        BarcodeHit part1 = hit(0, 2, 0x22, "A".getBytes(StandardCharsets.US_ASCII), "A");
        BarcodeHit part1Again = hit(0, 2, 0x22, "A".getBytes(StandardCharsets.US_ASCII), "A");
        BarcodeHit part2 = hit(1, 2, 0x22, "B".getBytes(StandardCharsets.US_ASCII), "B");
        List<String> notes = new java.util.ArrayList<>();
        BarcodeHit merged = StructuredAppendMerger.mergeSequence(List.of(part1, part1Again, part2), notes);
        assertEquals("AB", merged.text());
        assertTrue(notes.stream().anyMatch(note -> note.contains("duplicate")), notes.toString());
    }

    @Test
    @DisplayName("hits of different sequences are merged separately and plain hits are left alone")
    void separateSequences() {
        BarcodeHit plain = BarcodeHitTestFactory.textOnly("standalone");
        BarcodeHit sequenceA1 = hit(0, 2, 0x01, "x".getBytes(StandardCharsets.US_ASCII), "x");
        BarcodeHit sequenceA2 = hit(1, 2, 0x01, "y".getBytes(StandardCharsets.US_ASCII), "y");
        BarcodeHit sequenceB1 = hit(0, 2, 0x02, "p".getBytes(StandardCharsets.US_ASCII), "p");
        BarcodeHit sequenceB2 = hit(1, 2, 0x02, "q".getBytes(StandardCharsets.US_ASCII), "q");

        StructuredAppendMerger.MergeOutcome outcome = StructuredAppendMerger.merge(
                List.of(sequenceA1, sequenceB2, plain, sequenceA2, sequenceB1));
        assertEquals(1, outcome.unmerged().size());
        assertEquals("standalone", outcome.unmerged().get(0).text());
        assertEquals(2, outcome.merged().size());
        List<String> texts = outcome.merged().stream().map(BarcodeHit::text).sorted().toList();
        assertEquals(List.of("pq", "xy"), texts);
    }

    @Test
    @DisplayName("a single part sequence without structured append information is not merged")
    void singleOrdinaryHit() {
        BarcodeHit plain = BarcodeHitTestFactory.binary(new byte[] {1, 2, 3});
        StructuredAppendMerger.MergeOutcome outcome = StructuredAppendMerger.merge(List.of(plain));
        assertEquals(1, outcome.unmerged().size());
        assertTrue(outcome.merged().isEmpty());
    }

    @Test
    @DisplayName("the merged payload is the concatenation of the parts' byte segments, byte exact")
    void payloadIsByteExact() {
        byte[] first = new byte[128];
        byte[] second = new byte[128];
        for (int i = 0; i < 128; i++) {
            first[i] = (byte) i;
            second[i] = (byte) (255 - i);
        }
        BarcodeHit part1 = hit(0, 2, 0x33, first, null);
        BarcodeHit part2 = hit(1, 2, 0x33, second, null);
        List<String> notes = new java.util.ArrayList<>();
        BarcodeHit merged = StructuredAppendMerger.mergeSequence(List.of(part1, part2), notes);
        byte[] expected = new byte[256];
        System.arraycopy(first, 0, expected, 0, 128);
        System.arraycopy(second, 0, expected, 128, 128);
        assertArrayEquals(expected, merged.payload());
        assertEquals(256, merged.payloadSize());
    }

    @Test void incompletePartsRemainAvailableForLaterScans() {
        var first = hit(0, 2, 7, new byte[] {1}, "a");
        var outcome = StructuredAppendMerger.merge(List.of(first));
        assertTrue(outcome.merged().isEmpty());
        assertEquals(List.of(first), outcome.unmerged());
    }

    @Test void conflictingPartsAreNeverSilentlyDiscarded() {
        var outcome = StructuredAppendMerger.merge(List.of(
                hit(0, 2, 7, new byte[] {1}, "a"),
                hit(0, 2, 7, new byte[] {2}, "b"),
                hit(1, 2, 7, new byte[] {3}, "c")));
        assertTrue(outcome.merged().isEmpty());
        assertEquals(3, outcome.unmerged().size());
    }

    @Test void completeMergeIsIdempotent() {
        var first = StructuredAppendMerger.merge(List.of(
                hit(0, 2, 7, new byte[] {1}, "a"), hit(1, 2, 7, new byte[] {2}, "b")));
        assertNull(first.merged().getFirst().structuredAppend());
        var second = StructuredAppendMerger.merge(first.merged());
        assertTrue(second.merged().isEmpty());
        assertArrayEquals(new byte[] {1, 2}, second.unmerged().getFirst().payload());
    }

    private static BarcodeHit hit(int index, int total, int parity, byte[] payload, String text) {
        return new BarcodeHit(BarcodeFormat.QR_CODE, text, payload, List.of(payload),
                null, List.of(), Roi.EMPTY, 0, false, Map.of(),
                new StructuredAppend(index, total, parity, (index << 4) | ((total - 1) & 0x0f)),
                PayloadDetector.detect(payload, text));
    }
}
