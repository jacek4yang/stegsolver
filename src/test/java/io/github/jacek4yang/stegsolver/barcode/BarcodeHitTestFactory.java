package io.github.jacek4yang.stegsolver.barcode;

import com.google.zxing.BarcodeFormat;
import io.github.jacek4yang.stegsolver.core.Roi;
import java.util.List;
import java.util.Map;

/**
 * Small factory for {@link BarcodeHit} instances in tests, so that the structured append tests can build
 * hits without a real decoder.
 */
final class BarcodeHitTestFactory {

    private BarcodeHitTestFactory() {
    }

    static BarcodeHit binary(byte[] payload) {
        return new BarcodeHit(BarcodeFormat.QR_CODE, null, payload, List.of(payload), null, List.of(),
                Roi.EMPTY, 0, false, Map.of(), null, PayloadDetector.detect(payload, null));
    }

    static BarcodeHit textOnly(String text) {
        return new BarcodeHit(BarcodeFormat.QR_CODE, text, null, List.of(), null, List.of(), Roi.EMPTY, 0,
                false, Map.of(), null, PayloadDetector.detect(new byte[0], text));
    }
}
