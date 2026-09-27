package io.github.jacek4yang.stegsolver.selfcheck;

import io.github.jacek4yang.stegsolver.barcode.BarcodeScanner;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.barcode.ScanOptions;
import io.github.jacek4yang.stegsolver.barcode.ScanResult;
import io.github.jacek4yang.stegsolver.core.FrameSource;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.extract.DataExtractor;
import io.github.jacek4yang.stegsolver.extract.ExtractionOptions;
import io.github.jacek4yang.stegsolver.parser.FileAnalyzer;
import io.github.jacek4yang.stegsolver.parser.FileReport;
import io.github.jacek4yang.stegsolver.transform.CombineMode;
import io.github.jacek4yang.stegsolver.transform.ImageTransforms;
import io.github.jacek4yang.stegsolver.transform.StereoTransform;
import io.github.jacek4yang.stegsolver.transform.TransformCatalog;
import io.github.jacek4yang.stegsolver.transform.TransformEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs every engine layer once and reports what happened.
 *
 * <p>This is the integration check that a build is usable: it exercises the transform catalog, the
 * extraction, the stereogram solver, the combiner, the file analysers, the barcode scanner and the
 * lazy frame reader on the same image, without needing a display. It is used by
 * {@code Launcher --self-test}, by the Help menu, and by the tests, which is why it lives in its own
 * package: it deliberately depends on every other layer.</p>
 */
public final class SelfTest {

    /** A step of the self test. */
    public record Step(String name, boolean ok, String detail, long millis) {

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-34s %-4s %6d ms  %s", name, ok ? "ok" : "FAIL", millis,
                    detail);
        }
    }

    /** The collected result. */
    public record Report(List<Step> steps, long totalMillis) {

        public boolean ok() {
            return steps.stream().allMatch(Step::ok);
        }

        public String toText() {
            StringBuilder text = new StringBuilder();
            text.append("StegSolver self test\n");
            text.append(String.format(Locale.ROOT, "%-34s %-4s %9s  %s%n", "step", "res", "time",
                    "details"));
            for (Step step : steps) {
                text.append(step).append('\n');
            }
            text.append(String.format(Locale.ROOT, "%d of %d step(s) succeeded in %d ms%n",
                    steps.stream().filter(Step::ok).count(), steps.size(), totalMillis));
            return text.toString();
        }
    }

    private SelfTest() {
    }

    /** Runs the self test with the default settings, including a barcode scan. */
    public static Report run(ImageData image, Path file) {
        return run(image, file, true);
    }

    /**
     * Runs the self test.
     *
     * @param image                the image to work on
     * @param file                 the file it came from, or {@code null} for an in-memory image
     * @param includeBarcodeScan   {@code false} keeps the self test fast by skipping the scan
     */
    public static Report run(ImageData image, Path file, boolean includeBarcodeScan) {
        if (image == null) {
            throw new IllegalArgumentException("An image is required for the self test");
        }
        long startedAt = System.nanoTime();
        List<Step> steps = new ArrayList<>();

        steps.add(measure("transform catalog", () -> {
            TransformEngine engine = new TransformEngine(image);
            int rendered = 0;
            for (var def : TransformCatalog.definitions()) {
                int[] pixels = engine.pixelsFor(def);
                if (pixels.length != image.pixelCount()) {
                    throw new IllegalStateException(def.label() + " produced " + pixels.length + " pixels");
                }
                rendered++;
            }
            return rendered + " transforms rendered, " + engine.cachedTransformCount() + " cached";
        }));

        steps.add(measure("bulk pixel operations", () -> {
            ImageData inverted = ImageData.opaque(image.width(), image.height(), ImageTransforms.invert(image));
            ImageData half = ImageData.opaque(image.width(), image.height(),
                    ImageTransforms.bitPlane(image, io.github.jacek4yang.stegsolver.core.Channel.RED, 4));
            return "invert and bit plane on " + inverted.width() + "x" + inverted.height()
                    + ", sample value " + Integer.toHexString(half.pixelAt(0));
        }));

        steps.add(measure("data extraction", () -> {
            ExtractionOptions lsb = ExtractionOptions.defaults().withLsbFirst(true);
            DataExtractor.Result result = DataExtractor.extract(image,
                    io.github.jacek4yang.stegsolver.core.Roi.whole(image.width(), image.height()), lsb, 1 << 20);
            long allPlanes = DataExtractor.extract(image,
                    io.github.jacek4yang.stegsolver.core.Roi.whole(image.width(), image.height()),
                    ExtractionOptions.allPlanes(), 1).totalBytes();
            PayloadInfo info = io.github.jacek4yang.stegsolver.barcode.PayloadDetector.detect(result.data(),
                    null);
            return "LSB extract " + result.data().length + " bytes (" + info.type().description()
                    + "), all planes would be " + allPlanes + " bytes";
        }));

        steps.add(measure("auto LSB scan", () -> {
            var options = io.github.jacek4yang.stegsolver.extract.AutoLsbScanner.fastScanOptions(image.hasAlpha());
            var candidate = io.github.jacek4yang.stegsolver.extract.AutoLsbScanner.evaluateCandidate(image,
                    io.github.jacek4yang.stegsolver.core.Roi.whole(image.width(), image.height()), options.get(0), 1024);
            return options.size() + " fast scan configurations, sample candidate score "
                    + candidate.score() + " (" + candidate.formattedConfig() + ")";
        }));

        steps.add(measure("stereogram solver", () -> {
            int offset = Math.min(37, Math.max(1, image.width() / 3));
            int[] solved = StereoTransform.shiftedXor(image, offset);
            int suggested = StereoTransform.bestOffset(image, 8);
            return "offset " + offset + " solved into " + solved.length + " pixels, suggested offset "
                    + suggested;
        }));

        steps.add(measure("image combine", () -> {
            int modes = 0;
            for (CombineMode mode : CombineMode.values()) {
                ImageData combined = mode.combine(image, image);
                if (combined.width() <= 0 || combined.height() <= 0) {
                    throw new IllegalStateException(mode + " produced an empty image");
                }
                modes++;
            }
            return modes + " combination modes produced an image";
        }));

        steps.add(measure("barcode scan", () -> {
            if (!includeBarcodeScan) {
                return "skipped";
            }
            ScanResult result = new BarcodeScanner().scan(image, ScanOptions.quick());
            return result.summary();
        }));

        if (file != null) {
            steps.add(measure("file analysis", () -> {
                FileReport report = FileAnalyzer.analyze(file);
                return report.summary() + (report.hasWarnings()
                        ? " (" + String.join(" | ", report.warnings()) + ")" : "");
            }));

            steps.add(measure("frame source", () -> {
                try (FrameSource source = FrameSource.open(file)) {
                    ImageData frame = source.frame(0);
                    return "frame 0 decoded: " + frame.width() + "x" + frame.height()
                            + (source.hasKnownFrameCount() ? ", " + source.frameCount() + " frame(s)" : "");
                }
            }));
        }

        long totalMillis = (System.nanoTime() - startedAt) / 1_000_000;
        return new Report(List.copyOf(steps), totalMillis);
    }

    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    /** Runs one step, measuring it and turning any failure into a reported step. */
    private static Step measure(String name, ThrowingSupplier body) {
        long startedAt = System.nanoTime();
        try {
            String detail = body.get();
            return new Step(name, true, detail, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (Exception | Error e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            return new Step(name, false, message, (System.nanoTime() - startedAt) / 1_000_000);
        }
    }
}
