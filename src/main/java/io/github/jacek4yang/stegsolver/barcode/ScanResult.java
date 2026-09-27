package io.github.jacek4yang.stegsolver.barcode;

import io.github.jacek4yang.stegsolver.core.Roi;
import java.util.List;

/**
 * The outcome of a scan: the symbols that were found, the notes describing what was tried and what was
 * merged, and how long it took.
 *
 * @param hits          decoded symbols, duplicates removed
 * @param notes         diagnostics, in presentation order
 * @param elapsedMillis wall clock duration of the scan
 * @param scannedRegion the region that was scanned, in original image coordinates
 */
public record ScanResult(List<BarcodeHit> hits, List<String> notes, long elapsedMillis, Roi scannedRegion) {

    public ScanResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static ScanResult empty(Roi region, String note, long elapsedMillis) {
        return new ScanResult(List.of(), List.of(note), elapsedMillis, region);
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public int count() {
        return hits.size();
    }

    /** One line summary for the status bar. */
    public String summary() {
        if (hits.isEmpty()) {
            return "No barcode or QR code found (" + elapsedMillis + " ms)";
        }
        long binary = hits.stream().filter(BarcodeHit::hasBinaryPayload).count();
        StringBuilder text = new StringBuilder();
        text.append(hits.size()).append(hits.size() == 1 ? " symbol" : " symbols")
                .append(" found (").append(elapsedMillis).append(" ms)");
        if (binary > 0) {
            text.append(", ").append(binary).append(" with binary payload");
        }
        long structuredAppend = hits.stream().filter(hit -> hit.structuredAppend() != null).count();
        if (structuredAppend > 0) {
            text.append(", ").append(structuredAppend).append(" structured append part(s)");
        }
        return text.toString();
    }
}
