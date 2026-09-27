package io.github.jacek4yang.stegsolver.extract;

import io.github.jacek4yang.stegsolver.barcode.PayloadDetector;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.barcode.PayloadType;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance, deterministic automatic LSB scanning engine.
 *
 * <p>Implements two-phase scanning: Phase 1 evaluates a bounded prefix (up to 64 KiB) across common
 * CTF steganography configurations in the background, scores and ranks them using deterministic
 * evidence, and deduplicates equivalent configurations. Phase 2 (full extraction) is performed
 * on-demand when the user previews, applies, or saves a candidate.</p>
 */
public final class AutoLsbScanner {

    /** Default prefix extraction limit for Phase 1 scanning: 64 KiB. */
    public static final int SCAN_PREFIX_LIMIT = 65_536;

    /** Pattern detecting CTF flags in extracted byte streams. */
    private static final Pattern CTF_FLAG_PATTERN = Pattern.compile(
            "(?i)(?:flag|ctf|steg|key|secret|pico|htb|thm)\\{[\\x20-\\x7e]{1,128}\\}");

    /** One active scan and at most one pending scan. Cancelled work is removed from the queue. */
    private static final ThreadPoolExecutor SCAN_EXECUTOR = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
        Thread thread = new Thread(r, "stegsolver-auto-lsb-scan");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private AutoLsbScanner() {
    }

    /** Listener for scan progress and incremental results. */
    public interface ScanListener {
        void onProgress(int completed, int total, int candidateCount);
        void onBatchResults(List<LsbCandidate> batch);
        void onFinished(List<LsbCandidate> allRanked);
        void onError(Throwable error);
    }

    /** Handle to a running scan task that allows cancellation. */
    public interface ScanTask {
        void cancel();
        boolean isCancelled();
    }

    /**
     * Generates the bounded list of extraction configurations for Fast Scan.
     *
     * <p>Covers essential CTF combinations: row/col traversal, MSB/LSB bit order, all 6 RGB permutations,
     * common channels (RGB, R, G, B, RGBA/A if alpha present), low bit planes (0, 1, 2, 0+1, 0+1+2), and
     * inversion.</p>
     */
    public static List<ExtractionOptions> fastScanOptions(boolean hasAlpha) {
        List<ExtractionOptions> list = new ArrayList<>();

        // 1. RGB (all 3 channels)
        // bit 0: all 6 orders, both traversals, normal and inverted
        for (RgbOrder order : RgbOrder.values()) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                for (boolean invert : new boolean[] {false, true}) {
                    list.add(ExtractionOptions.none()
                            .with(Channel.RED, 0, true)
                            .with(Channel.GREEN, 0, true)
                            .with(Channel.BLUE, 0, true)
                            .withOrder(order)
                            .withRowFirst(rowFirst)
                            .withLsbFirst(true)
                            .withInvertBits(invert));
                }
            }
        }

        // bit 1: RGB and BGR, row and col
        for (RgbOrder order : new RgbOrder[] {RgbOrder.RGB, RgbOrder.BGR}) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                list.add(ExtractionOptions.none()
                        .with(Channel.RED, 1, true)
                        .with(Channel.GREEN, 1, true)
                        .with(Channel.BLUE, 1, true)
                        .withOrder(order)
                        .withRowFirst(rowFirst)
                        .withLsbFirst(true));
            }
        }

        // bit 2: RGB and BGR, row and col
        for (RgbOrder order : new RgbOrder[] {RgbOrder.RGB, RgbOrder.BGR}) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                list.add(ExtractionOptions.none()
                        .with(Channel.RED, 2, true)
                        .with(Channel.GREEN, 2, true)
                        .with(Channel.BLUE, 2, true)
                        .withOrder(order)
                        .withRowFirst(rowFirst)
                        .withLsbFirst(true));
            }
        }

        // bits 0+1 (2-bit LSB): all 6 orders, row and col, MSB and LSB bit order, normal and inverted
        for (RgbOrder order : RgbOrder.values()) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                for (boolean lsbFirst : new boolean[] {true, false}) {
                    for (boolean invert : new boolean[] {false, true}) {
                        list.add(ExtractionOptions.none()
                                .with(Channel.RED, 0, true).with(Channel.RED, 1, true)
                                .with(Channel.GREEN, 0, true).with(Channel.GREEN, 1, true)
                                .with(Channel.BLUE, 0, true).with(Channel.BLUE, 1, true)
                                .withOrder(order)
                                .withRowFirst(rowFirst)
                                .withLsbFirst(lsbFirst)
                                .withInvertBits(invert));
                    }
                }
            }
        }

        // bits 0+1+2 (3-bit LSB): RGB and BGR, row and col, MSB and LSB
        for (RgbOrder order : new RgbOrder[] {RgbOrder.RGB, RgbOrder.BGR}) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                for (boolean lsbFirst : new boolean[] {true, false}) {
                    list.add(ExtractionOptions.none()
                            .with(Channel.RED, 0, true).with(Channel.RED, 1, true).with(Channel.RED, 2, true)
                            .with(Channel.GREEN, 0, true).with(Channel.GREEN, 1, true).with(Channel.GREEN, 2, true)
                            .with(Channel.BLUE, 0, true).with(Channel.BLUE, 1, true).with(Channel.BLUE, 2, true)
                            .withOrder(order)
                            .withRowFirst(rowFirst)
                            .withLsbFirst(lsbFirst));
                }
            }
        }

        // 2. Single channels: Red, Green, Blue
        for (Channel channel : new Channel[] {Channel.RED, Channel.GREEN, Channel.BLUE}) {
            // bit 0: row and col, normal and inverted
            for (boolean rowFirst : new boolean[] {true, false}) {
                for (boolean invert : new boolean[] {false, true}) {
                    list.add(ExtractionOptions.none()
                            .with(channel, 0, true)
                            .withRowFirst(rowFirst)
                            .withLsbFirst(true)
                            .withInvertBits(invert));
                }
                // bit 1: row and col
                list.add(ExtractionOptions.none()
                        .with(channel, 1, true)
                        .withRowFirst(rowFirst)
                        .withLsbFirst(true));
                // bits 0+1: row and col, MSB and LSB
                for (boolean lsbFirst : new boolean[] {true, false}) {
                    list.add(ExtractionOptions.none()
                            .with(channel, 0, true).with(channel, 1, true)
                            .withRowFirst(rowFirst)
                            .withLsbFirst(lsbFirst));
                }
            }
        }

        // 3. Alpha channel (when image has alpha)
        if (hasAlpha) {
            // Alpha bit 0
            for (boolean rowFirst : new boolean[] {true, false}) {
                for (boolean invert : new boolean[] {false, true}) {
                    list.add(ExtractionOptions.none()
                            .with(Channel.ALPHA, 0, true)
                            .withRowFirst(rowFirst)
                            .withLsbFirst(true)
                            .withInvertBits(invert));
                }
            }
            // RGBA bit 0 (order RGB and BGR)
            for (RgbOrder order : new RgbOrder[] {RgbOrder.RGB, RgbOrder.BGR}) {
                for (boolean rowFirst : new boolean[] {true, false}) {
                    for (boolean invert : new boolean[] {false, true}) {
                        list.add(ExtractionOptions.none()
                                .with(Channel.ALPHA, 0, true)
                                .with(Channel.RED, 0, true)
                                .with(Channel.GREEN, 0, true)
                                .with(Channel.BLUE, 0, true)
                                .withOrder(order)
                                .withRowFirst(rowFirst)
                                .withLsbFirst(true)
                                .withInvertBits(invert));
                    }
                }
            }
        }

        return list;
    }

    /**
     * Generates a broader enumerated search space for Deep Scan.
     * Still bounded and completely cancellable.
     */
    public static List<ExtractionOptions> deepScanOptions(boolean hasAlpha) {
        List<ExtractionOptions> list = new ArrayList<>(fastScanOptions(hasAlpha));

        // Add all 6 permutations for 3-bit LSB (bits 0,1,2)
        for (RgbOrder order : new RgbOrder[] {RgbOrder.RBG, RgbOrder.GRB, RgbOrder.GBR, RgbOrder.BRG}) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                for (boolean lsbFirst : new boolean[] {true, false}) {
                    list.add(ExtractionOptions.none()
                            .with(Channel.RED, 0, true).with(Channel.RED, 1, true).with(Channel.RED, 2, true)
                            .with(Channel.GREEN, 0, true).with(Channel.GREEN, 1, true).with(Channel.GREEN, 2, true)
                            .with(Channel.BLUE, 0, true).with(Channel.BLUE, 1, true).with(Channel.BLUE, 2, true)
                            .withOrder(order)
                            .withRowFirst(rowFirst)
                            .withLsbFirst(lsbFirst));
                }
            }
        }

        // Add 4-bit LSB (bits 0,1,2,3)
        for (RgbOrder order : new RgbOrder[] {RgbOrder.RGB, RgbOrder.BGR}) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                for (boolean lsbFirst : new boolean[] {true, false}) {
                    list.add(ExtractionOptions.none()
                            .with(Channel.RED, 0, true).with(Channel.RED, 1, true)
                            .with(Channel.RED, 2, true).with(Channel.RED, 3, true)
                            .with(Channel.GREEN, 0, true).with(Channel.GREEN, 1, true)
                            .with(Channel.GREEN, 2, true).with(Channel.GREEN, 3, true)
                            .with(Channel.BLUE, 0, true).with(Channel.BLUE, 1, true)
                            .with(Channel.BLUE, 2, true).with(Channel.BLUE, 3, true)
                            .withOrder(order)
                            .withRowFirst(rowFirst)
                            .withLsbFirst(lsbFirst));
                }
            }
        }

        // Add MSB (bit 7)
        for (RgbOrder order : new RgbOrder[] {RgbOrder.RGB, RgbOrder.BGR}) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                list.add(ExtractionOptions.none()
                        .with(Channel.RED, 7, true)
                        .with(Channel.GREEN, 7, true)
                        .with(Channel.BLUE, 7, true)
                        .withOrder(order)
                        .withRowFirst(rowFirst)
                        .withLsbFirst(false));
            }
        }

        // Single channel bit 2, bit 7
        for (Channel channel : new Channel[] {Channel.RED, Channel.GREEN, Channel.BLUE}) {
            for (boolean rowFirst : new boolean[] {true, false}) {
                list.add(ExtractionOptions.none()
                        .with(channel, 2, true)
                        .withRowFirst(rowFirst)
                        .withLsbFirst(true));
                list.add(ExtractionOptions.none()
                        .with(channel, 7, true)
                        .withRowFirst(rowFirst)
                        .withLsbFirst(false));
            }
        }

        return list;
    }

    /**
     * Executes a candidate Phase 1 scan synchronously and scores it.
     */
    public static LsbCandidate evaluateCandidate(ImageData image, Roi region, ExtractionOptions options, int maxBytes) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        DataExtractor.Result result = DataExtractor.extract(image, region, options, maxBytes);
        byte[] data = result.data();
        PayloadInfo info = PayloadDetector.detect(data, null);
        Evaluation evaluation = scorePayload(data, info);
        return new LsbCandidate(options, evaluation.score(), evaluation.reason(), info, data,
                result.totalBytes(), result.truncated());
    }

    record Evaluation(int score, String reason) {}

    /**
     * Deterministically scores a candidate payload.
     * High-entropy random bytes never rank above valid file signatures or readable text.
     */
    static Evaluation scorePayload(byte[] data, PayloadInfo info) {
        if (data == null || data.length == 0) {
            return new Evaluation(0, "Empty extract");
        }

        // Check if all bytes are identical (e.g. all 0x00 or all 0xFF)
        if (isAllSameByte(data)) {
            return new Evaluation(1, "Uniform byte sequence (all 0x" + Integer.toHexString(data[0] & 0xff) + ")");
        }

        // 1. Check for common CTF flag patterns
        String flagMatch = findCtfFlag(data);
        if (flagMatch != null) {
            return new Evaluation(98, "CTF flag: " + flagMatch);
        }

        // 2. Strong magic signatures (Archives, Images, Documents, Executables)
        PayloadType type = info.type();
        switch (type) {
            case ZIP -> {
                return new Evaluation(100, "ZIP archive signature (PK\\x03\\x04)");
            }
            case SEVEN_ZIP -> {
                return new Evaluation(100, "7-Zip archive signature");
            }
            case RAR -> {
                return new Evaluation(100, "RAR archive signature");
            }
            case GZIP -> {
                return new Evaluation(100, "gzip compressed stream signature");
            }
            case BZIP2 -> {
                return new Evaluation(100, "bzip2 compressed stream signature");
            }
            case XZ -> {
                return new Evaluation(100, "xz compressed stream signature");
            }
            case TAR -> {
                return new Evaluation(100, "tar archive magic (ustar)");
            }
            case PNG -> {
                return new Evaluation(100, "PNG image signature");
            }
            case JPEG -> {
                return new Evaluation(100, "JPEG image signature");
            }
            case GIF -> {
                return new Evaluation(100, "GIF image signature");
            }
            case BMP -> {
                if (data.length >= 14) {
                    return new Evaluation(95, "BMP bitmap signature");
                }
                return new Evaluation(80, "Possible BM header");
            }
            case WEBP -> {
                return new Evaluation(100, "WebP image container signature");
            }
            case PDF -> {
                return new Evaluation(100, "PDF document signature");
            }
            case ELF -> {
                return new Evaluation(100, "ELF executable / shared object header");
            }
            case PE -> {
                if (hasPeHeader(data)) {
                    return new Evaluation(100, "Windows PE executable header");
                }
                return new Evaluation(65, "Possible MZ header (PE signature not verified)");
            }
            case JAVA_CLASS -> {
                return new Evaluation(100, "Java class bytecode (0xCAFEBABE)");
            }
            case BASE64_TEXT -> {
                return data.length >= 24
                        ? new Evaluation(72, "Base64 alphabet and length match")
                        : new Evaluation(45, "Short possible Base64 text");
            }
            case TEXT_ASCII, TEXT_UTF8 -> {
                return evaluateText(data, type == PayloadType.TEXT_ASCII);
            }
            case BINARY -> {
                // Check if it starts with structured markup or script
                if (isScriptHeader(data)) {
                    return new Evaluation(94, "Script executable header (#!)");
                }
                if (isJsonStructure(data)) {
                    return new Evaluation(92, "JSON structured text");
                }
                if (isXmlOrHtml(data)) {
                    return new Evaluation(92, "XML/HTML markup");
                }
                return evaluateBinary(data, info);
            }
            default -> {
                return new Evaluation(10, "Unclassified data");
            }
        }
    }

    private static Evaluation evaluateText(byte[] data, boolean ascii) {
        int spaces = 0;
        int printable = 0;
        for (byte b : data) {
            int c = b & 0xff;
            if (c == ' ' || c == '\t') spaces++;
            if (c >= 32 && c <= 126) printable++;
        }
        double printableRatio = (double) printable / Math.max(1, data.length);
        if (spaces > 0 && printableRatio > 0.90) {
            return new Evaluation(90, ascii ? "Readable ASCII text" : "Readable UTF-8 text");
        }
        if (printableRatio > 0.95) {
            return new Evaluation(86, ascii ? "Valid ASCII text" : "Valid UTF-8 text");
        }
        return new Evaluation(80, "Text sequence");
    }

    private static Evaluation evaluateBinary(byte[] data, PayloadInfo info) {
        int printable = 0;
        for (byte b : data) {
            int c = b & 0xff;
            if (c >= 32 && c <= 126) printable++;
        }
        double printableRatio = (double) printable / Math.max(1, data.length);
        if (printableRatio > 0.70) {
            return new Evaluation(65, "Partially printable text fragments");
        }
        if (info.distinctBytes() < 5) {
            return new Evaluation(5, "Low byte variation");
        }
        if (info.entropy() > 7.6) {
            // High entropy noise: random bytes, do NOT rank above file signatures
            return new Evaluation(15, "High-entropy unrecognised binary noise");
        }
        if (info.distinctBytes() >= 16 && info.entropy() >= 3.0 && info.entropy() <= 7.0) {
            return new Evaluation(30, "Structured binary data");
        }
        return new Evaluation(20, "Unrecognised binary data");
    }

    private static String findCtfFlag(byte[] data) {
        if (data.length < 5) return null;
        int searchLimit = Math.min(data.length, 16384);
        String sample = new String(data, 0, searchLimit, StandardCharsets.ISO_8859_1);
        Matcher matcher = CTF_FLAG_PATTERN.matcher(sample);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static boolean isAllSameByte(byte[] data) {
        if (data == null || data.length == 0) return true;
        byte first = data[0];
        for (int i = 1; i < data.length; i++) {
            if (data[i] != first) return false;
        }
        return true;
    }

    private static boolean isScriptHeader(byte[] data) {
        return data.length >= 2 && data[0] == '#' && data[1] == '!';
    }

    private static boolean hasPeHeader(byte[] data) {
        if (data.length < 64) return false;
        long offset = (data[0x3c] & 0xffL) | ((data[0x3d] & 0xffL) << 8)
                | ((data[0x3e] & 0xffL) << 16) | ((data[0x3f] & 0xffL) << 24);
        return offset <= data.length - 4 && data[(int) offset] == 'P'
                && data[(int) offset + 1] == 'E' && data[(int) offset + 2] == 0
                && data[(int) offset + 3] == 0;
    }

    private static boolean isJsonStructure(byte[] data) {
        if (data.length < 2) return false;
        int idx = 0;
        while (idx < data.length && (data[idx] == ' ' || data[idx] == '\t' || data[idx] == '\n' || data[idx] == '\r')) {
            idx++;
        }
        if (idx < data.length && (data[idx] == '{' || data[idx] == '[')) {
            // Check if mostly printable
            int check = Math.min(data.length, idx + 64);
            for (int i = idx; i < check; i++) {
                int c = data[i] & 0xff;
                if (c < 32 && c != '\t' && c != '\n' && c != '\r') return false;
            }
            return true;
        }
        return false;
    }

    private static boolean isXmlOrHtml(byte[] data) {
        if (data.length < 5) return false;
        int idx = 0;
        while (idx < data.length && (data[idx] == ' ' || data[idx] == '\t' || data[idx] == '\n' || data[idx] == '\r')) {
            idx++;
        }
        if (idx + 4 <= data.length && data[idx] == '<') {
            String tag = new String(data, idx, Math.min(10, data.length - idx), StandardCharsets.US_ASCII)
                    .toLowerCase(java.util.Locale.ROOT);
            return tag.startsWith("<?xml") || tag.startsWith("<!doctype") || tag.startsWith("<html")
                    || tag.startsWith("<svg");
        }
        return false;
    }

    /**
     * Starts an asynchronous scan. Bounded, cancellable, outside the JavaFX UI thread.
     */
    public static ScanTask startScan(ImageData image, Roi region, boolean deep, ScanListener listener) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(listener, "listener");

        List<ExtractionOptions> optionsList = deep ? deepScanOptions(image.hasAlpha()) : fastScanOptions(image.hasAlpha());
        final int total = optionsList.size();

        class Task implements ScanTask, Runnable {
            private volatile boolean cancelled;
            private Future<?> future;

            @Override
            public void cancel() {
                cancelled = true;
                if (future != null) {
                    future.cancel(true);
                    SCAN_EXECUTOR.remove((Runnable) future);
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public void run() {
                Map<String, LsbCandidate> deduplicated = new LinkedHashMap<>();
                List<LsbCandidate> batch = new ArrayList<>();
                int completed = 0;

                try {
                    for (ExtractionOptions opt : optionsList) {
                        if (cancelled || Thread.currentThread().isInterrupted()) {
                            return;
                        }

                        LsbCandidate candidate = evaluateCandidate(image, region, opt, SCAN_PREFIX_LIMIT);
                        completed++;

                        // A matching bounded prefix does not prove matching full payloads. Only
                        // collapse byte-identical *complete* extracts of the same length.
                        String fp = candidate.truncated()
                                ? candidate.fingerprint() + ":" + candidate.totalBytes() + ":" + opt
                                : candidate.fingerprint() + ":" + candidate.totalBytes();
                        LsbCandidate existing = deduplicated.get(fp);
                        boolean accepted = false;

                        if (existing == null) {
                            deduplicated.put(fp, candidate);
                            accepted = true;
                        } else if (candidate.score() > existing.score()) {
                            deduplicated.put(fp, candidate);
                            accepted = true;
                        }

                        if (accepted) {
                            batch.add(candidate);
                        }

                        // Send progress update
                        if (completed % 8 == 0 || completed == total) {
                            final int currentCompleted = completed;
                            final int matches = deduplicated.size();
                            listener.onProgress(currentCompleted, total, matches);
                        }

                        // Send batch results
                        if (batch.size() >= 10 || (completed == total && !batch.isEmpty())) {
                            final List<LsbCandidate> currentBatch = new ArrayList<>(batch);
                            batch.clear();
                            listener.onBatchResults(currentBatch);
                        }
                    }

                    if (!cancelled) {
                        List<LsbCandidate> all = new ArrayList<>(deduplicated.values());
                        all.sort(Comparator.comparingInt(LsbCandidate::score).reversed());
                        listener.onFinished(Collections.unmodifiableList(all));
                    }
                } catch (Throwable error) {
                    if (!cancelled) {
                        listener.onError(error);
                    }
                }
            }
        }

        Task task = new Task();
        task.future = SCAN_EXECUTOR.submit(task);
        return task;
    }

    /** Stop the scan worker when the application closes. */
    public static void shutdown() {
        SCAN_EXECUTOR.shutdownNow();
    }
}
