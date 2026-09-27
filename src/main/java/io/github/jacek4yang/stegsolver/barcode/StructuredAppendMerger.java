package io.github.jacek4yang.stegsolver.barcode;

import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the parts of a QR Structured Append sequence.
 *
 * <p>ZXing merges the parts that are found inside a single scan, but a sequence can also be spread over
 * several images, several regions of one image or several scans of a screen. This utility merges the
 * parts of the results collected so far, in the right order, keeping the payload bytes exact: the
 * payload of the merged symbol is the concatenation of the parts' {@code BYTE_SEGMENTS} and is never
 * rebuilt from the decoded text.</p>
 */
public final class StructuredAppendMerger {

    /**
     * @param merged   one hit per complete or partial sequence, in symbol order
     * @param unmerged hits that do not carry structured append information
     * @param notes    diagnostics about what was merged and what is missing
     */
    public record MergeOutcome(List<BarcodeHit> merged, List<BarcodeHit> unmerged, List<String> notes) {
    }

    private StructuredAppendMerger() {
    }

    public static MergeOutcome merge(List<BarcodeHit> hits) {
        List<BarcodeHit> unmerged = new ArrayList<>();
        Map<Integer, List<BarcodeHit>> byParity = new LinkedHashMap<>();
        for (BarcodeHit hit : hits) {
            if (hit.structuredAppend() == null || !hit.hasBinaryPayload() && !hit.isTextOnly()) {
                unmerged.add(hit);
                continue;
            }
            byParity.computeIfAbsent(hit.structuredAppend().parity(), key -> new ArrayList<>()).add(hit);
        }

        List<BarcodeHit> merged = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Map.Entry<Integer, List<BarcodeHit>> entry : byParity.entrySet()) {
            List<BarcodeHit> parts = entry.getValue();
            if (parts.size() == 1 && parts.get(0).structuredAppend().total() == 1) {
                unmerged.add(parts.get(0));
                continue;
            }
            merged.add(mergeSequence(parts, notes));
        }
        return new MergeOutcome(merged, unmerged, notes);
    }

    /** Merges one sequence, which may be partial. */
    public static BarcodeHit mergeSequence(List<BarcodeHit> parts, List<String> notes) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("At least one part is required");
        }
        List<BarcodeHit> ordered = new ArrayList<>(parts);
        ordered.sort((a, b) -> Integer.compare(a.structuredAppend().index(), b.structuredAppend().index()));

        int total = ordered.stream().mapToInt(hit -> hit.structuredAppend().total()).max().orElse(ordered.size());
        int parity = ordered.get(0).structuredAppend().parity();
        boolean[] seen = new boolean[StructuredAppend.MAX_SYMBOLS];
        List<String> duplicates = new ArrayList<>();

        StringBuilder text = new StringBuilder();
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream rawBytes = new java.io.ByteArrayOutputStream();
        List<byte[]> segments = new ArrayList<>();
        List<RotationMapper.Point> points = new ArrayList<>();
        boolean anyPayload = false;
        int lastIndex = -1;
        for (BarcodeHit part : ordered) {
            int index = part.structuredAppend().index();
            if (index < seen.length) {
                if (seen[index]) {
                    duplicates.add(String.valueOf(index + 1));
                    continue;
                }
                seen[index] = true;
            }
            if (part.text() != null) {
                text.append(part.text());
            }
            if (part.payload() != null) {
                payload.writeBytes(part.payload());
                anyPayload = true;
            }
            if (part.decoderRawBytes() != null) {
                rawBytes.writeBytes(part.decoderRawBytes());
            }
            segments.addAll(part.byteSegments());
            points.addAll(part.points());
            lastIndex = index;
        }

        List<Integer> missing = new ArrayList<>();
        for (int index = 0; index < Math.min(total, StructuredAppend.MAX_SYMBOLS); index++) {
            if (!seen[index]) {
                missing.add(index + 1);
            }
        }

        if (!duplicates.isEmpty()) {
            notes.add("Ignored duplicate structured append part(s): " + String.join(", ", duplicates));
        }
        if (missing.isEmpty() && total == lastIndex + 1) {
            notes.add("Merged a complete structured append sequence of " + total + " symbols");
        } else {
            String missingText = missing.isEmpty()
                    ? (total - (lastIndex + 1)) + " unknown"
                    : String.join(", ", missing.stream().map(String::valueOf).toList());
            notes.add("Merged structured append parts; symbol(s) " + missingText + " of " + total
                    + " are still missing");
        }

        byte[] mergedPayload = anyPayload ? payload.toByteArray() : null;
        byte[] mergedRaw = rawBytes.size() > 0 ? rawBytes.toByteArray() : null;
        String mergedText = text.length() > 0 ? text.toString() : null;
        BarcodeHit first = ordered.get(0);
        return new BarcodeHit(first.format(), mergedText, mergedPayload, segments, mergedRaw, points,
                first.bounds(), 0, false,
                Map.of("structuredAppend", "merged from " + ordered.size() + " symbols",
                        "structuredAppendTotal", String.valueOf(total),
                        "structuredAppendParity", "0x" + Integer.toHexString(parity)),
                new StructuredAppend(0, total, parity, (total - 1) & 0x0f),
                PayloadDetector.detect(mergedPayload, mergedText));
    }

    /** Extracts the structured append information from a raw ZXing result. */
    public static StructuredAppend fromResult(Result result) {
        Map<ResultMetadataType, Object> metadata = result.getResultMetadata();
        if (metadata == null) {
            return null;
        }
        Object sequence = metadata.get(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE);
        if (!(sequence instanceof Integer value)) {
            return null;
        }
        Object parity = metadata.get(ResultMetadataType.STRUCTURED_APPEND_PARITY);
        int parityValue = parity instanceof Integer integer ? integer : 0;
        return StructuredAppend.fromRawSequence(value, parityValue);
    }
}
