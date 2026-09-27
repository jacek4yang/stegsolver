package io.github.jacek4yang.stegsolver.barcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Options of a barcode scan.
 *
 * @param tryHarder        enables ZXing's {@code TRY_HARDER} hint (slower, much better on noisy images)
 * @param tryInverted      also scans an inverted copy, for light symbols on a dark background
 * @param tryRotated       also tries quarter turns of the image (90/180/270 degrees)
 * @param multipleSymbols  looks for several symbols in one image instead of stopping at the first
 * @param deepSearch       adds the expensive fallbacks (rescaled copies, alternate binarizer, pure
 *                         barcode mode) and keeps trying even after a symbol was found
 * @param formats          symbologies to consider; empty means all of them
 * @param timeBudgetMillis wall clock budget after which the remaining fallbacks are skipped
 */
public record ScanOptions(boolean tryHarder, boolean tryInverted, boolean tryRotated, boolean multipleSymbols,
        boolean deepSearch, Set<BarcodeFormat> formats, long timeBudgetMillis) {

    public ScanOptions {
        formats = formats == null || formats.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(formats));
        if (timeBudgetMillis <= 0) {
            throw new IllegalArgumentException("timeBudgetMillis must be positive");
        }
    }

    /**
     * The everyday defaults: hard working single pass over the whole image in both polarities, looking
     * for every symbology, including several symbols in the same image.
     */
    public static ScanOptions defaults() {
        return new ScanOptions(true, true, true, true, false, Set.of(), 15_000);
    }

    /** A quick scan used for the automatic background detection after opening an image. */
    public static ScanOptions quick() {
        return new ScanOptions(true, true, false, true, false, Set.of(), 4_000);
    }

    /** Everything, including the expensive fallbacks. */
    public static ScanOptions thorough() {
        return new ScanOptions(true, true, true, true, true, Set.of(), 45_000);
    }

    /** A scan limited to the symbologies the user selected. */
    public static ScanOptions defaultsFor(Set<BarcodeFormat> formats) {
        return new ScanOptions(true, true, true, true, false, formats, 15_000);
    }

    public boolean allFormats() {
        return formats.isEmpty();
    }

    public ScanOptions withTryHarder(boolean value) {
        return new ScanOptions(value, tryInverted, tryRotated, multipleSymbols, deepSearch, formats,
                timeBudgetMillis);
    }

    public ScanOptions withTryInverted(boolean value) {
        return new ScanOptions(tryHarder, value, tryRotated, multipleSymbols, deepSearch, formats,
                timeBudgetMillis);
    }

    public ScanOptions withTryRotated(boolean value) {
        return new ScanOptions(tryHarder, tryInverted, value, multipleSymbols, deepSearch, formats,
                timeBudgetMillis);
    }

    public ScanOptions withMultipleSymbols(boolean value) {
        return new ScanOptions(tryHarder, tryInverted, tryRotated, value, deepSearch, formats,
                timeBudgetMillis);
    }

    public ScanOptions withDeepSearch(boolean value) {
        return new ScanOptions(tryHarder, tryInverted, tryRotated, multipleSymbols, value, formats,
                timeBudgetMillis);
    }

    public ScanOptions withFormats(Set<BarcodeFormat> newFormats) {
        return new ScanOptions(tryHarder, tryInverted, tryRotated, multipleSymbols, deepSearch, newFormats,
                timeBudgetMillis);
    }

    public ScanOptions withTimeBudgetMillis(long value) {
        return new ScanOptions(tryHarder, tryInverted, tryRotated, multipleSymbols, deepSearch, formats, value);
    }

    /**
     * Builds the ZXing hint map for one attempt.
     *
     * <p>No {@code CHARACTER_SET} hint is set: the original tool forced UTF-8, which silently mangles
     * the decoded text of symbols that carry ISO-8859-1 bytes without an ECI designator. Leaving the
     * hint unset lets ZXing distinguish ISO-8859-1, UTF-8 and Shift_JIS. The payload bytes are unaffected
     * either way because they are taken from {@code BYTE_SEGMENTS} rather than from the text.</p>
     */
    Map<DecodeHintType, Object> toHints(boolean pureBarcode) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        if (tryHarder) {
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        }
        if (!formats.isEmpty()) {
            hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.copyOf(formats));
        }
        // DecodeHintType.ALSO_INVERTED is deliberately not used: ZXing would silently try the inverted
        // image inside the reader, and StegSolver would no longer know whether the symbol it found was
        // light on dark. The inverted copy is scanned explicitly instead, which keeps the report exact.
        if (pureBarcode) {
            hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
        }
        return hints;
    }
}
