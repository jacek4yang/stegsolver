package io.github.jacek4yang.stegsolver.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.barcode.PayloadDetector;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.barcode.PayloadType;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AutoLsbScannerTest {

    @Test
    @DisplayName("Fast Scan candidate count is bounded between 50 and 200")
    void fastScanBoundedCandidateCount() {
        List<ExtractionOptions> rgbOnly = AutoLsbScanner.fastScanOptions(false);
        List<ExtractionOptions> withAlpha = AutoLsbScanner.fastScanOptions(true);

        assertTrue(rgbOnly.size() >= 50 && rgbOnly.size() <= 200,
                () -> "Fast Scan (RGB) must be bounded: was " + rgbOnly.size());
        assertTrue(withAlpha.size() > rgbOnly.size(),
                "Fast Scan with Alpha should include alpha-specific combinations");
        assertTrue(withAlpha.size() <= 200,
                () -> "Fast Scan with Alpha must remain bounded: was " + withAlpha.size());
    }

    @Test
    @DisplayName("Deep Scan candidate count is broader than Fast Scan but bounded")
    void deepScanBoundedCandidateCount() {
        List<ExtractionOptions> deep = AutoLsbScanner.deepScanOptions(false);
        List<ExtractionOptions> fast = AutoLsbScanner.fastScanOptions(false);

        assertTrue(deep.size() > fast.size(), "Deep Scan must cover more combinations than Fast Scan");
        assertTrue(deep.size() <= 500, () -> "Deep Scan must remain bounded: was " + deep.size());
    }

    @Test
    @DisplayName("Discovers common RGB LSB hidden flag at rank 1")
    void discoverCommonRgbLsb() throws Exception {
        byte[] flag = "flag{common_rgb_lsb_found}".getBytes(StandardCharsets.UTF_8);
        ImageData cover = TestImages.randomRgb(100, 50, 101);
        ExtractionOptions target = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true)
                .withOrder(RgbOrder.RGB)
                .withRowFirst(true)
                .withLsbFirst(true);

        ImageData stego = embed(cover, target, flag);
        List<LsbCandidate> results = runScan(stego, false);

        assertFalse(results.isEmpty(), "Scan should produce candidates");
        LsbCandidate top = results.get(0);
        assertEquals(98, top.score(), "Flag should score 98");
        assertTrue(top.reason().contains("flag{common_rgb_lsb_found}"), "Reason should mention detected flag");
        assertEquals(RgbOrder.RGB, top.options().order());
        assertTrue(top.options().rowFirst());
        assertTrue(top.options().isSelected(Channel.RED, 0));
        assertTrue(top.options().isSelected(Channel.GREEN, 0));
        assertTrue(top.options().isSelected(Channel.BLUE, 0));
    }

    @Test
    @DisplayName("Discovers BGR channel order LSB extraction at rank 1")
    void discoverBgrLsb() throws Exception {
        byte[] flag = "flag{bgr_order_discovered}".getBytes(StandardCharsets.UTF_8);
        ImageData cover = TestImages.randomRgb(100, 50, 202);
        ExtractionOptions target = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true)
                .withOrder(RgbOrder.BGR)
                .withRowFirst(true)
                .withLsbFirst(true);

        ImageData stego = embed(cover, target, flag);
        List<LsbCandidate> results = runScan(stego, false);

        assertFalse(results.isEmpty());
        LsbCandidate top = results.get(0);
        assertEquals(98, top.score());
        assertEquals(RgbOrder.BGR, top.options().order(), "Discovered order must be BGR");
    }

    @Test
    @DisplayName("Discovers Column-major traversal LSB extraction at rank 1")
    void discoverColumnTraversalLsb() throws Exception {
        byte[] flag = "flag{column_major_traversal_hit}".getBytes(StandardCharsets.UTF_8);
        ImageData cover = TestImages.randomRgb(100, 50, 303);
        ExtractionOptions target = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true)
                .withOrder(RgbOrder.RGB)
                .withRowFirst(false)
                .withLsbFirst(true);

        ImageData stego = embed(cover, target, flag);
        List<LsbCandidate> results = runScan(stego, false);

        assertFalse(results.isEmpty());
        LsbCandidate top = results.get(0);
        assertEquals(98, top.score());
        assertFalse(top.options().rowFirst(), "Traversal must be column-major (rowFirst=false)");
    }

    @Test
    @DisplayName("Discovers multi-bit plane extraction (bits 0 and 1)")
    void discoverMultiBitPlanes() throws Exception {
        byte[] flag = "flag{multi_bit_plane_0_and_1}".getBytes(StandardCharsets.UTF_8);
        ImageData cover = TestImages.randomRgb(100, 50, 404);
        ExtractionOptions target = ExtractionOptions.none()
                .with(Channel.RED, 0, true).with(Channel.RED, 1, true)
                .with(Channel.GREEN, 0, true).with(Channel.GREEN, 1, true)
                .with(Channel.BLUE, 0, true).with(Channel.BLUE, 1, true)
                .withOrder(RgbOrder.RGB)
                .withRowFirst(true)
                .withLsbFirst(true);

        ImageData stego = embed(cover, target, flag);
        List<LsbCandidate> results = runScan(stego, false);

        assertFalse(results.isEmpty());
        LsbCandidate top = results.get(0);
        assertEquals(98, top.score());
        assertTrue(top.options().isSelected(Channel.RED, 0) && top.options().isSelected(Channel.RED, 1));
        assertTrue(top.options().isSelected(Channel.GREEN, 0) && top.options().isSelected(Channel.GREEN, 1));
        assertTrue(top.options().isSelected(Channel.BLUE, 0) && top.options().isSelected(Channel.BLUE, 1));
    }

    @Test
    @DisplayName("Discovers inverted bit extraction")
    void discoverInvertedBits() throws Exception {
        byte[] flag = "flag{inverted_bit_extraction}".getBytes(StandardCharsets.UTF_8);
        ImageData cover = TestImages.randomRgb(100, 50, 505);
        ExtractionOptions target = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true)
                .withOrder(RgbOrder.RGB)
                .withRowFirst(true)
                .withLsbFirst(true)
                .withInvertBits(true);

        ImageData stego = embed(cover, target, flag);
        List<LsbCandidate> results = runScan(stego, false);

        assertFalse(results.isEmpty());
        LsbCandidate top = results.get(0);
        assertEquals(98, top.score());
        assertTrue(top.options().invertBits(), "Inverted bits must be true");
    }

    @Test
    @DisplayName("Candidate scoring strictly ranks valid files over high-entropy noise")
    void candidateScoringRanksFilesAboveNoise() throws Exception {
        byte[] zipData = zipPayload();
        PayloadInfo zipInfo = PayloadDetector.detect(zipData, null);
        var zipScore = AutoLsbScanner.scorePayload(zipData, zipInfo);
        assertEquals(100, zipScore.score());
        assertTrue(zipScore.reason().contains("ZIP archive"));

        byte[] flagData = "flag{test_flag_scoring}".getBytes(StandardCharsets.UTF_8);
        PayloadInfo flagInfo = PayloadDetector.detect(flagData, null);
        var flagScore = AutoLsbScanner.scorePayload(flagData, flagInfo);
        assertEquals(98, flagScore.score());

        byte[] textData = "The quick brown fox jumps over the lazy dog. A readable sentence.".getBytes(StandardCharsets.UTF_8);
        PayloadInfo textInfo = PayloadDetector.detect(textData, null);
        var textScore = AutoLsbScanner.scorePayload(textData, textInfo);
        assertTrue(textScore.score() >= 85 && textScore.score() <= 92, "Text should score ~90");

        byte[] randomNoise = new byte[1024];
        new java.util.Random(12345).nextBytes(randomNoise);
        PayloadInfo noiseInfo = PayloadDetector.detect(randomNoise, null);
        var noiseScore = AutoLsbScanner.scorePayload(randomNoise, noiseInfo);
        assertTrue(noiseScore.score() <= 20, "High-entropy random noise must never score above 20: was " + noiseScore.score());

        assertTrue(zipScore.score() > flagScore.score());
        assertTrue(flagScore.score() > textScore.score());
        assertTrue(textScore.score() > noiseScore.score());
    }

    @Test
    @DisplayName("Deduplicates identical byte extract prefixes across equivalent candidates")
    void candidateDeduplication() throws Exception {
        // A solid black image produces all 0x00 for any plane without inversion
        ImageData solidBlack = TestImages.solid(50, 50, 0xff000000);
        List<LsbCandidate> results = runScan(solidBlack, false);

        // Multiple configs extract all zeroes, deduplication ensures each unique byte stream prefix
        // is retained only once
        long distinctFingerprints = results.stream().map(LsbCandidate::fingerprint).distinct().count();
        assertEquals(results.size(), distinctFingerprints, "Candidate list must have unique fingerprints");
    }

    @Test
    @DisplayName("Cancellation promptly terminates background scanning")
    void cancellationBehavior() throws Exception {
        ImageData largeImage = TestImages.randomRgb(300, 300, 999);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        AutoLsbScanner.ScanTask task = AutoLsbScanner.startScan(largeImage, null, true,
                new AutoLsbScanner.ScanListener() {
                    @Override
                    public void onProgress(int comp, int tot, int matches) {
                        started.countDown();
                    }

                    @Override
                    public void onBatchResults(List<LsbCandidate> batch) {}

                    @Override
                    public void onFinished(List<LsbCandidate> allRanked) {
                        completed.set(true);
                        finished.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        finished.countDown();
                    }
                });

        assertTrue(started.await(5, TimeUnit.SECONDS), "Scan should start and report progress");
        task.cancel();
        assertTrue(task.isCancelled(), "Task must report cancelled");
        assertFalse(finished.await(300, TimeUnit.MILLISECONDS),
                "Cancelled task should not call onFinished");
        assertFalse(completed.get());
    }

    @Test
    @DisplayName("LsbCandidate formatting produces human-readable configuration description")
    void candidateFormatting() {
        ExtractionOptions opt1 = ExtractionOptions.none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true)
                .withOrder(RgbOrder.RGB)
                .withRowFirst(true)
                .withLsbFirst(true);
        assertEquals("RGB \u00b7 bit 0 \u00b7 Row-major \u00b7 LSB first", LsbCandidate.formatConfig(opt1));

        ExtractionOptions opt2 = ExtractionOptions.none()
                .with(Channel.BLUE, 0, true)
                .with(Channel.BLUE, 1, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.GREEN, 1, true)
                .with(Channel.RED, 0, true)
                .with(Channel.RED, 1, true)
                .withOrder(RgbOrder.BGR)
                .withRowFirst(false)
                .withLsbFirst(false)
                .withInvertBits(true);
        assertEquals("BGR \u00b7 bits 0,1 \u00b7 Column-major \u00b7 MSB first \u00b7 inverted",
                LsbCandidate.formatConfig(opt2));
    }

    // =============================================================== Test Helpers

    private static List<LsbCandidate> runScan(ImageData image, boolean deep) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<LsbCandidate>> ref = new AtomicReference<>(List.of());
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        AutoLsbScanner.startScan(image, null, deep, new AutoLsbScanner.ScanListener() {
            @Override
            public void onProgress(int completed, int total, int candidateCount) {}

            @Override
            public void onBatchResults(List<LsbCandidate> batch) {}

            @Override
            public void onFinished(List<LsbCandidate> allRanked) {
                ref.set(allRanked);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Scan timed out");
        if (errorRef.get() != null) {
            throw new RuntimeException("Scan failed", errorRef.get());
        }
        return ref.get();
    }

    private static ImageData embed(ImageData cover, ExtractionOptions options, byte[] payload) {
        int[] pixels = cover.pixels().clone();
        int width = cover.width();
        int height = cover.height();
        int[] plan = DataExtractor.buildPlan(options);
        if (plan.length == 0) return cover;

        int byteIndex = 0;
        int bitOffset = 7;

        if (options.rowFirst()) {
            outer:
            for (int y = 0; y < height; y++) {
                int rowStart = y * width;
                for (int x = 0; x < width; x++) {
                    int pIdx = rowStart + x;
                    int pixel = pixels[pIdx];
                    if (options.invertBits()) pixel = ~pixel;
                    for (int bitPos : plan) {
                        if (byteIndex < payload.length) {
                            int bit = (payload[byteIndex] >> bitOffset) & 1;
                            pixel = (pixel & ~(1 << bitPos)) | (bit << bitPos);
                            bitOffset--;
                            if (bitOffset < 0) {
                                bitOffset = 7;
                                byteIndex++;
                            }
                        }
                    }
                    if (options.invertBits()) pixel = ~pixel;
                    pixels[pIdx] = pixel;
                    if (byteIndex >= payload.length) break outer;
                }
            }
        } else {
            outer:
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int pIdx = y * width + x;
                    int pixel = pixels[pIdx];
                    if (options.invertBits()) pixel = ~pixel;
                    for (int bitPos : plan) {
                        if (byteIndex < payload.length) {
                            int bit = (payload[byteIndex] >> bitOffset) & 1;
                            pixel = (pixel & ~(1 << bitPos)) | (bit << bitPos);
                            bitOffset--;
                            if (bitOffset < 0) {
                                bitOffset = 7;
                                byteIndex++;
                            }
                        }
                    }
                    if (options.invertBits()) pixel = ~pixel;
                    pixels[pIdx] = pixel;
                    if (byteIndex >= payload.length) break outer;
                }
            }
        }
        return ImageData.opaque(width, height, pixels);
    }

    private static byte[] zipPayload() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("secret.txt"));
            zip.write("Top secret content inside zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
