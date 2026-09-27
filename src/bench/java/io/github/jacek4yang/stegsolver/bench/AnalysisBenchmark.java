package io.github.jacek4yang.stegsolver.bench;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.barcode.BarcodeScanner;
import io.github.jacek4yang.stegsolver.barcode.ScanOptions;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.extract.DataExtractor;
import io.github.jacek4yang.stegsolver.extract.ExtractionOptions;
import io.github.jacek4yang.stegsolver.transform.CombineMode;
import io.github.jacek4yang.stegsolver.transform.StereoTransform;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The other expensive paths: data extraction, image combination, the stereogram solver and a full
 * barcode scan, which is the slowest single operation the application offers.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class AnalysisBenchmark {

    @Param({"1024"})
    public int size;

    private ImageData image;
    private ImageData second;
    private ImageData qrCode;
    private ExtractionOptions lsbOptions;
    private ExtractionOptions allPlanesOptions;

    @Setup(Level.Trial)
    public void setUp() {
        image = TestImages.randomRgb(size, size, 7);
        second = TestImages.randomRgb(size, size, 8);
        qrCode = TestImages.pasteOnCanvas(TestImages.qrCode("benchmark payload", 6), 640, 480, 180, 120);
        lsbOptions = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true)
                .withLsbFirst(true);
        allPlanesOptions = ExtractionOptions.allPlanes();
    }

    /** The everyday case: the LSBs of red, green and blue. */
    @Benchmark
    public byte[] extractLsb() {
        return DataExtractor.extract(image, lsbOptions);
    }

    /** The worst case: all 32 bit planes of every pixel. */
    @Benchmark
    public byte[] extractAllPlanes() {
        return DataExtractor.extract(image, allPlanesOptions);
    }

    @Benchmark
    public ImageData combineXor() {
        return CombineMode.XOR.combine(image, second);
    }

    @Benchmark
    public ImageData combineInterlaceRows() {
        return CombineMode.INTERLACE_ROWS.combine(image, second);
    }

    @Benchmark
    public int[] stereoOffset() {
        return StereoTransform.shiftedXor(image, 37);
    }

    /** Looking for the repeating pattern width, which is the solver's "Find offset" button. */
    @Benchmark
    public int stereoSearch() {
        return StereoTransform.bestOffset(image, 4);
    }

    /** One complete scan of a small image holding a single QR code. */
    @Benchmark
    public int scanSingleQrCode() {
        return new BarcodeScanner().scan(qrCode, ScanOptions.quick()).count();
    }
}
