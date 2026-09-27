package io.github.jacek4yang.stegsolver.barcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageOps;
import io.github.jacek4yang.stegsolver.core.Roi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds and decodes barcodes and QR codes in an image region.
 *
 * <p>The scanner is deliberately greedy but honest about payloads:</p>
 * <ul>
 *   <li>the whole image or a dragged region can be scanned;</li>
 *   <li>{@code TRY_HARDER} is used together with practical fallbacks — inverted polarity, quarter turns,
 *       the alternate binarizer, a rescaled copy and pure barcode mode;</li>
 *   <li>several symbols in one image are found through the multiple symbol readers, and QR Structured
 *       Append sequences are merged by ZXing (within one bitmap) or by
 *       {@link StructuredAppendMerger} (across scans);</li>
 *   <li>the binary payload is the decoder's {@code BYTE_SEGMENTS} concatenated verbatim — it is never
 *       rebuilt from the decoded text;</li>
 *   <li>found positions are mapped back to original image coordinates so that an overlay can mark the
 *       symbols even when a rotated or rescaled copy had to be used.</li>
 * </ul>
 *
 * <p>Instances are stateless; the class is safe to call from a background thread.</p>
 */
public final class BarcodeScanner {

    /** Largest side beyond which a full scan copy is downscaled for the deep fallback pass. */
    private static final int DOWNSCALE_THRESHOLD_PX = 2600;
    /** Largest side below which a full scan copy is upscaled for the deep fallback pass. */
    private static final int UPSCALE_THRESHOLD_PX = 400;

    /** Scans the whole image. */
    public ScanResult scan(ImageData image, ScanOptions options) {
        return scan(image, Roi.whole(image.width(), image.height()), options);
    }

    /** Scans a region of the image; the region is clamped to the image bounds first. */
    public ScanResult scan(ImageData image, Roi region, ScanOptions options) {
        if (image == null) {
            throw new IllegalArgumentException("image is required");
        }
        ScanOptions effective = options == null ? ScanOptions.defaults() : options;
        long startedAt = System.nanoTime();
        Roi area = region == null ? Roi.whole(image.width(), image.height()) : region.clampTo(image.width(), image.height());
        if (area.isEmpty()) {
            return ScanResult.empty(area, "The selected region is empty", 0);
        }

        Attempt attempt = new Attempt(image, area, effective, startedAt);
        ImageData base = ImageOps.crop(image, area);

        // Phase 1: the common case, both polarities, no rotation.
        attempt.tryBitmaps(base, 0, 1.0, false, false);

        // Phase 2: quarter turns, but only when nothing was found or the user asked for a deep search.
        if (effective.tryRotated() && (attempt.hits.isEmpty() || effective.deepSearch())) {
            for (int turns = 1; turns <= 3; turns++) {
                if (attempt.budgetExhausted()) {
                    break;
                }
                if (!attempt.hits.isEmpty() && !effective.deepSearch()) {
                    break;
                }
                attempt.tryBitmaps(ImageOps.rotateCounterClockwise(base, turns), turns, 1.0, false, false);
            }
        }

        // Phase 3: rescaled copies and the alternate binarizer, for hard images.
        if (effective.deepSearch() && !attempt.budgetExhausted()) {
            deepFallbacks(attempt, base);
        } else if (attempt.hits.isEmpty() && !attempt.budgetExhausted()) {
            // A single cheap rescale often rescues tiny or very large symbols.
            int longest = Math.max(base.width(), base.height());
            if (longest < UPSCALE_THRESHOLD_PX) {
                attempt.tryBitmaps(ImageOps.scaleNearest(base, 2.0), 0, 2.0, false, false);
            } else if (longest > DOWNSCALE_THRESHOLD_PX) {
                attempt.tryBitmaps(ImageOps.scaleNearest(base, 0.5), 0, 0.5, false, false);
            }
        }

        if (attempt.budgetExhausted()) {
            attempt.notes.add("Stopped after the " + effective.timeBudgetMillis()
                    + " ms time budget: results may be incomplete. Select a smaller region or raise the budget.");
        }
        if (attempt.hits.isEmpty()) {
            attempt.notes.add("No symbol could be decoded. Practical next steps: scan a smaller region, "
                    + "enable deep search, or use a bit plane transform to increase contrast first.");
        }

        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        return new ScanResult(attempt.hits, attempt.notes, elapsed, area);
    }

    private void deepFallbacks(Attempt attempt, ImageData base) {
        int longest = Math.max(base.width(), base.height());
        if (longest < UPSCALE_THRESHOLD_PX) {
            attempt.notes.add("Deep search: retrying with an enlarged copy");
            attempt.tryBitmaps(ImageOps.scaleNearest(base, 2.0), 0, 2.0, false, false);
            if (!attempt.budgetExhausted()) {
                attempt.tryBitmaps(ImageOps.scaleNearest(base, 3.0), 0, 3.0, false, true);
            }
        } else if (longest > DOWNSCALE_THRESHOLD_PX) {
            attempt.notes.add("Deep search: retrying with a downscaled copy");
            attempt.tryBitmaps(ImageOps.scaleNearest(base, 0.5), 0, 0.5, false, true);
        }
        if (!attempt.budgetExhausted()) {
            attempt.notes.add("Deep search: retrying with the global histogram binarizer and pure barcode mode");
            attempt.tryBitmaps(base, 0, 1.0, true, true);
        }
        if (!attempt.budgetExhausted() && attempt.hits.isEmpty()) {
            // A 45 degree rotation occasionally rescues a symbol photographed at an angle; only for
            // small images, because the copy is expensive.
            if (longest <= 1200) {
                attempt.tryBitmaps(ImageOps.scaleNearest(ImageOps.rotateCounterClockwise(base), 1.0), 1, 1.0,
                        true, true);
            }
        }
    }

    /** One scanned bitmap plus the information needed to map coordinates back. */
    private static final class BitmapAttempt {

        private final ImageData pixels;
        private final int quarterTurns;
        private final double scale;
        private final boolean pureBarcode;
        private final boolean globalHistogram;

        private BitmapAttempt(ImageData pixels, int quarterTurns, double scale, boolean pureBarcode,
                boolean globalHistogram) {
            this.pixels = pixels;
            this.quarterTurns = quarterTurns;
            this.scale = scale;
            this.pureBarcode = pureBarcode;
            this.globalHistogram = globalHistogram;
        }
    }

    /** Per scan state: collected hits, notes and the time budget. */
    private final class Attempt {

        private final ImageData image;
        private final Roi area;
        private final ScanOptions options;
        private final long startedAtNanos;
        private final long budgetNanos;
        private final List<BarcodeHit> hits = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        private Attempt(ImageData image, Roi area, ScanOptions options, long startedAtNanos) {
            this.image = image;
            this.area = area;
            this.options = options;
            this.startedAtNanos = startedAtNanos;
            this.budgetNanos = options.timeBudgetMillis() * 1_000_000L;
        }

        private boolean budgetExhausted() {
            return System.nanoTime() - startedAtNanos > budgetNanos;
        }

        private void tryBitmaps(ImageData pixels, int quarterTurns, double scale, boolean pureBarcode,
                boolean globalHistogram) {
            BitmapAttempt bitmap = new BitmapAttempt(pixels, quarterTurns, scale, pureBarcode,
                    globalHistogram);
            List<String> label = new ArrayList<>();
            if (quarterTurns != 0) {
                label.add(quarterTurns * 90 + "°");
            }
            if (scale != 1.0) {
                label.add(String.format(Locale.ROOT, "%.1fx", scale));
            }
            if (pureBarcode) {
                label.add("pure barcode");
            }
            if (globalHistogram) {
                label.add("global histogram");
            }
            String description = label.isEmpty() ? "original" : String.join(", ", label);

            LuminanceSource source = new RgbLuminanceSource(pixels.pixels(), pixels.width(), pixels.height());
            runOn(bitmapFor(source, globalHistogram), bitmap, description, false);
            if (options.tryInverted()) {
                if (budgetExhausted()) {
                    return;
                }
                runOn(bitmapFor(source.invert(), globalHistogram), bitmap,
                        description + ", inverted", true);
            }
        }

        private BinaryBitmap bitmapFor(LuminanceSource source, boolean globalHistogram) {
            return new BinaryBitmap(globalHistogram ? new GlobalHistogramBinarizer(source)
                    : new HybridBinarizer(source));
        }

        private void runOn(BinaryBitmap bitmap, BitmapAttempt attempt, String description, boolean inverted) {
            Map<DecodeHintType, Object> hints = options.toHints(attempt.pureBarcode);
            if (options.multipleSymbols()) {
                // The QR multiple reader ignores POSSIBLE_FORMATS, so it must be skipped when the user
                // restricted the scan to other symbologies.
                if (options.allFormats() || options.formats().contains(BarcodeFormat.QR_CODE)) {
                    decodeMultiple(new QRCodeMultiReader(), bitmap, hints, attempt, description, inverted);
                }
                if (!hitAlmostFull()) {
                    decodeMultiple(new GenericMultipleBarcodeReader(new MultiFormatReader()), bitmap, hints,
                            attempt, description, inverted);
                }
            }
            if (hits.isEmpty() || options.deepSearch()) {
                // The single symbol reader is both faster and more forgiving on very small images.
                decodeSingle(new MultiFormatReader(), bitmap, hints, attempt, description, inverted);
            }
        }

        /** Cheap guard against pathological images that yield dozens of noisy symbols. */
        private boolean hitAlmostFull() {
            return hits.size() >= 32;
        }

        private void decodeMultiple(Object reader, BinaryBitmap bitmap, Map<DecodeHintType, Object> hints,
                BitmapAttempt attempt, String description, boolean inverted) {
            if (budgetExhausted()) {
                return;
            }
            try {
                Result[] results;
                if (reader instanceof QRCodeMultiReader qrReader) {
                    results = qrReader.decodeMultiple(bitmap, hints);
                } else if (reader instanceof GenericMultipleBarcodeReader generic) {
                    results = generic.decodeMultiple(bitmap, hints);
                } else {
                    return;
                }
                for (Result result : results) {
                    add(result, attempt, description, inverted);
                }
            } catch (NotFoundException | RuntimeException e) {
                // Not finding anything is the normal case for most attempts.
            }
        }

        private void decodeSingle(MultiFormatReader reader, BinaryBitmap bitmap,
                Map<DecodeHintType, Object> hints, BitmapAttempt attempt, String description,
                boolean inverted) {
            if (budgetExhausted()) {
                return;
            }
            try {
                Result result = reader.decode(bitmap, hints);
                add(result, attempt, description, inverted);
            } catch (NotFoundException | RuntimeException e) {
                // Expected.
            }
        }

        private void add(Result result, BitmapAttempt attempt, String description, boolean inverted) {
            // Duplicate handling (the same symbol found by several passes) lives in HitMerge so that the
            // user interface can apply exactly the same rule when results from several scans are combined.
            HitMerge.add(hits, toHit(result, attempt, description, inverted));
        }

        private BarcodeHit toHit(Result result, BitmapAttempt attempt, String description, boolean inverted) {
            List<byte[]> segments = extractByteSegments(result);
            byte[] payload = concatenate(segments);
            StructuredAppend structuredAppend = StructuredAppendMerger.fromResult(result);

            List<RotationMapper.Point> points = new ArrayList<>();
            if (result.getResultPoints() != null) {
                for (ResultPoint point : result.getResultPoints()) {
                    if (point == null) {
                        continue;
                    }
                    points.add(mapBack(point.getX(), point.getY(), attempt));
                }
            }
            Roi bounds = boundsOf(points);

            Map<String, String> metadata = describeMetadata(result, segments);
            PayloadInfo payloadInfo = PayloadDetector.detect(payload, result.getText());

            BarcodeHit hit = new BarcodeHit(result.getBarcodeFormat(), result.getText(), payload, segments,
                    result.getRawBytes(), points, bounds, attempt.quarterTurns * 90, inverted, metadata,
                    structuredAppend, payloadInfo);

            if (structuredAppend != null) {
                notes.add(structuredAppend.describe());
            }
            notes.add("Decoded " + result.getBarcodeFormat() + " (" + description + "): " + hit.listLabel());
            return hit;
        }

        private RotationMapper.Point mapBack(double x, double y, BitmapAttempt attempt) {
            // The bitmap was produced as rotate(scale(base)), so undo the rotation first, then the scale,
            // then move from region coordinates into image coordinates. The rotated dimensions are read
            // from the bitmap itself so that rounding in the scaler cannot introduce an offset.
            int scaledWidth = attempt.quarterTurns % 2 == 0 ? attempt.pixels.width() : attempt.pixels.height();
            int scaledHeight = attempt.quarterTurns % 2 == 0 ? attempt.pixels.height() : attempt.pixels.width();
            RotationMapper.Point unrotated = RotationMapper.toOriginal(x, y, attempt.quarterTurns,
                    scaledWidth, scaledHeight);
            RotationMapper.Point unscaled = RotationMapper.unscale(unrotated.x(), unrotated.y(), attempt.scale);
            return new RotationMapper.Point(unscaled.x() + area.x(), unscaled.y() + area.y());
        }

        /** Bounding box of the symbol's corner points, in original image coordinates. */
        private Roi boundsOf(List<RotationMapper.Point> points) {
            if (points.isEmpty()) {
                return area;
            }
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (RotationMapper.Point point : points) {
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
            int x0 = (int) Math.floor(minX) - 3;
            int y0 = (int) Math.floor(minY) - 3;
            int x1 = (int) Math.ceil(maxX) + 3;
            int y1 = (int) Math.ceil(maxY) + 3;
            return Roi.clamp(x0, y0, x1 - x0, y1 - y0, image.width(), image.height());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<byte[]> extractByteSegments(Result result) {
        Map<ResultMetadataType, Object> metadata = result.getResultMetadata();
        if (metadata == null) {
            return List.of();
        }
        Object segments = metadata.get(ResultMetadataType.BYTE_SEGMENTS);
        if (!(segments instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<byte[]> copy = new ArrayList<>();
        for (Object element : iterable) {
            if (element instanceof byte[] bytes) {
                copy.add(bytes.clone());
            }
        }
        return copy;
    }

    /** Concatenates the byte segments; {@code null} when the symbol had none. */
    static byte[] concatenate(List<byte[]> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        int length = 0;
        for (byte[] segment : segments) {
            length += segment.length;
        }
        if (length == 0) {
            return null;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] segment : segments) {
            System.arraycopy(segment, 0, result, offset, segment.length);
            offset += segment.length;
        }
        return result;
    }

    private Map<String, String> describeMetadata(Result result, List<byte[]> segments) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("symbology", result.getBarcodeFormat().name());
        metadata.put("byteSegments", segments.isEmpty()
                ? "none (the symbol carries no binary byte segments)"
                : segments.size() + " segment(s), " + segments.stream().mapToInt(s -> s.length).sum() + " bytes");
        metadata.put("decoderRawBytes", result.getRawBytes() == null
                ? "none" : result.getRawBytes().length + " bytes");
        if (result.getResultMetadata() != null) {
            for (Map.Entry<ResultMetadataType, Object> entry : result.getResultMetadata().entrySet()) {
                if (entry.getKey() == ResultMetadataType.BYTE_SEGMENTS) {
                    continue;
                }
                metadata.put(entry.getKey().name().toLowerCase(Locale.ROOT), describeValue(entry.getValue()));
            }
        }
        return metadata;
    }

    private String describeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[] bytes) {
            return bytes.length + " bytes: " + io.github.jacek4yang.stegsolver.core.HexDump.hex(bytes, 0,
                    bytes.length, 32);
        }
        if (value instanceof ResultPoint[] points) {
            return points.length + " point(s)";
        }
        if (value instanceof Object[] array) {
            return String.valueOf(array.length) + " value(s)";
        }
        return String.valueOf(value);
    }
}
