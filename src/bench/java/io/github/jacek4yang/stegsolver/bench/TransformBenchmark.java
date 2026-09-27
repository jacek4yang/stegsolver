package io.github.jacek4yang.stegsolver.bench;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.transform.ImageTransforms;
import io.github.jacek4yang.stegsolver.transform.TransformCatalog;
import io.github.jacek4yang.stegsolver.transform.TransformEngine;
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
 * The image transform hot paths: every one of these runs on each key press while stepping through
 * transforms, so their cost is what decides whether navigation feels instant on a large image.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TransformBenchmark {

    @Param({"1024", "4096"})
    public int size;

    private ImageData image;
    private TransformEngine engine;

    @Setup(Level.Trial)
    public void setUp() {
        image = TestImages.randomArgb(size, size, 20250101L);
        engine = new TransformEngine(image);
        // Warm the cache so that the cached lookups below measure a cache hit.
        engine.pixelsFor(35);
    }

    @Benchmark
    public int[] bitPlane() {
        return ImageTransforms.bitPlane(image, io.github.jacek4yang.stegsolver.core.Channel.GREEN, 2);
    }

    @Benchmark
    public int[] invert() {
        return ImageTransforms.invert(image);
    }

    @Benchmark
    public int[] fullChannelMask() {
        return ImageTransforms.mask(image, 0x00ff0000);
    }

    @Benchmark
    public int[] randomComponentMap() {
        return ImageTransforms.randomComponentMap(image, ImageTransforms.seedFor(1));
    }

    @Benchmark
    public int[] grayPixels() {
        return ImageTransforms.grayPixels(image);
    }

    /** A cache hit, which is what stepping back to a plane that was just viewed costs. */
    @Benchmark
    public int[] cachedLookup() {
        return engine.pixelsFor(35);
    }

    /** The full path of one transform step: catalog lookup plus cache miss computation. */
    @Benchmark
    public int[] transformStepCacheMiss() {
        TransformEngine fresh = new TransformEngine(image, TransformEngine.MIN_CACHE_BYTES);
        return fresh.pixelsFor(TransformCatalog.byIndex(20));
    }
}
