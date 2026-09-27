package io.github.jacek4yang.stegsolver.barcode;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides when two decoded symbols are really the same one, and removes the duplicates.
 *
 * <p>A single symbol is often reported more than once: the multiple symbol readers and the single
 * symbol reader both find it, the inverted pass finds it again, and pure barcode mode may report it
 * without any position. Only the most useful report is kept — the located one, and among two located
 * reports the one with more payload bytes (which matters for Structured Append, where the merged symbol
 * carries more than a single part).</p>
 */
public final class HitMerge {

    /** Distance below which two hits with the same content are considered the same symbol. */
    public static final double DUPLICATE_DISTANCE_PX = 12;

    private HitMerge() {
    }

    /** True when two hits describe the same symbol: same content and the same place. */
    public static boolean describesSameSymbol(BarcodeHit existing, BarcodeHit candidate) {
        if (existing.format() != candidate.format()) {
            return false;
        }
        String existingContent = contentKey(existing);
        String candidateContent = contentKey(candidate);
        if (existingContent.isEmpty() || !existingContent.equals(candidateContent)) {
            return false;
        }
        // A decode without result points (pure barcode mode, some readers) covers the whole scanned
        // area, so it cannot be located and has to be treated as overlapping everything with the same
        // content.
        if (existing.points().isEmpty() || candidate.points().isEmpty()) {
            return true;
        }
        double dx = existing.bounds().x() - candidate.bounds().x();
        double dy = existing.bounds().y() - candidate.bounds().y();
        return Math.hypot(dx, dy) <= DUPLICATE_DISTANCE_PX;
    }

    /** Content identity: the payload bytes when there are any, the decoded text otherwise. */
    public static String contentKey(BarcodeHit hit) {
        if (hit.hasBinaryPayload()) {
            return PayloadDetector.sha256(hit.payload());
        }
        return hit.text() == null ? "" : hit.text();
    }

    /**
     * Adds a hit to a list, replacing an existing report of the same symbol when the new report is more
     * useful.
     *
     * @return true when the list was modified
     */
    public static boolean add(List<BarcodeHit> hits, BarcodeHit candidate) {
        for (int i = 0; i < hits.size(); i++) {
            BarcodeHit existing = hits.get(i);
            if (!describesSameSymbol(existing, candidate)) {
                continue;
            }
            boolean existingLocated = !existing.points().isEmpty();
            boolean candidateLocated = !candidate.points().isEmpty();
            if (candidateLocated && !existingLocated) {
                hits.set(i, candidate);
            } else if (!candidateLocated && existingLocated) {
                // Keep the located one.
            } else if (candidate.payloadSize() > existing.payloadSize()) {
                hits.set(i, candidate);
            }
            return false;
        }
        hits.add(candidate);
        return true;
    }

    /** Removes duplicates from a result list, keeping the most useful report of each symbol. */
    public static List<BarcodeHit> deduplicate(List<BarcodeHit> hits) {
        List<BarcodeHit> unique = new ArrayList<>(hits.size());
        for (BarcodeHit hit : hits) {
            add(unique, hit);
        }
        return unique;
    }
}
