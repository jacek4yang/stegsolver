package io.github.jacek4yang.stegsolver.extract;

import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;

/**
 * Extracts a bit stream out of an image, following the exact conventions of the original StegSolve
 * extractor.
 *
 * <p>The visit order per pixel is alpha first, then the colour channels in the configured
 * {@link RgbOrder}; inside a channel the bit planes are visited from 7 down to 0 for MSB-first
 * extraction and from 0 up to 7 for LSB-first extraction. Extracted bits are appended most
 * significant bit first, so the first bit read becomes bit 7 of the first output byte, and a
 * trailing partial byte is zero padded.</p>
 */
public final class DataExtractor {

    private DataExtractor() {
    }

    /**
     * The outcome of a (possibly bounded) extraction.
     *
     * @param data       the extracted bytes, at most {@code maxBytes}
     * @param totalBytes the number of bytes the full extraction would produce
     * @param truncated  {@code true} when {@code data} is shorter than {@code totalBytes}
     */
    public record Result(byte[] data, long totalBytes, boolean truncated) {

        public boolean isEmpty() {
            return data.length == 0;
        }
    }

    /** Extracts the whole image. */
    public static byte[] extract(ImageData image, ExtractionOptions options) {
        return extract(image, Roi.whole(image.width(), image.height()), options, Integer.MAX_VALUE).data();
    }

    /** Extracts a region of the image, using the region's own origin as the traversal origin. */
    public static byte[] extract(ImageData image, Roi region, ExtractionOptions options) {
        return extract(image, region, options, Integer.MAX_VALUE).data();
    }

    /**
     * Extracts at most {@code maxBytes} bytes, which is what the preview uses so that opening a large
     * image stays instantaneous.
     */
    public static Result extract(ImageData image, Roi region, ExtractionOptions options, int maxBytes) {
        if (image == null || options == null) {
            throw new IllegalArgumentException("image and options are required");
        }
        Roi area = region == null ? Roi.whole(image.width(), image.height()) : region.clampTo(image.width(), image.height());
        if (area.isEmpty()) {
            return new Result(new byte[0], 0, false);
        }
        long totalBytes = options.outputBytesFor((long) area.width() * area.height());
        int wanted = (int) Math.min(totalBytes, Math.max(0, maxBytes));

        if (options.isEmpty() || wanted == 0) {
            return new Result(new byte[0], totalBytes, totalBytes > 0);
        }

        int[] plan = buildPlan(options);
        int[] pixels = image.pixels();
        int imageWidth = image.width();
        BitSink sink = new BitSink(wanted);
        boolean invert = options.invertBits();

        if (options.rowFirst()) {
            for (int y = area.y(); y < area.maxY(); y++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                int rowStart = y * imageWidth;
                for (int x = area.x(); x < area.maxX(); x++) {
                    if (sink.isFull()) {
                        return truncated(sink, totalBytes);
                    }
                    sink.putPixel(pixels[rowStart + x], plan, invert);
                }
            }
        } else {
            for (int x = area.x(); x < area.maxX(); x++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                for (int y = area.y(); y < area.maxY(); y++) {
                    if (sink.isFull()) {
                        return truncated(sink, totalBytes);
                    }
                    sink.putPixel(pixels[y * imageWidth + x], plan, invert);
                }
            }
        }
        return truncated(sink, totalBytes);
    }

    private static Result truncated(BitSink sink, long totalBytes) {
        byte[] data = sink.finish();
        return new Result(data, totalBytes, data.length < totalBytes);
    }

    /**
     * Builds the extraction plan: the ARGB bit positions to read per pixel, already in visit order.
     * Encoding each entry as {@code channelShift + plane} keeps the inner loop allocation free.
     */
    static int[] buildPlan(ExtractionOptions options) {
        Channel[] visitOrder = options.order().visitOrder();
        int[] plan = new int[options.selectedCount()];
        int index = 0;
        for (Channel channel : visitOrder) {
            int shift = channel.shift();
            if (options.lsbFirst()) {
                for (int plane = 0; plane < 8; plane++) {
                    if (options.isSelected(channel, plane)) {
                        plan[index++] = shift + plane;
                    }
                }
            } else {
                for (int plane = 7; plane >= 0; plane--) {
                    if (options.isSelected(channel, plane)) {
                        plan[index++] = shift + plane;
                    }
                }
            }
        }
        return plan;
    }

    /** Packs single bits into bytes, most significant bit first. */
    private static final class BitSink {

        private final byte[] out;
        private int bytePos;
        private long pendingBits;
        private int bitCount;

        BitSink(int capacity) {
            this.out = new byte[capacity];
        }

        boolean isFull() {
            return bytePos >= out.length;
        }

        void putPixel(int pixel, int[] plan, boolean invert) {
            if (invert) pixel = ~pixel;
            int packed = 0;
            for (int bit : plan) {
                packed = (packed << 1) | ((pixel >>> bit) & 1);
            }
            pendingBits = (pendingBits << plan.length) | (packed & 0xffffffffL);
            bitCount += plan.length;
            while (bitCount >= 8 && !isFull()) {
                bitCount -= 8;
                out[bytePos++] = (byte) (pendingBits >>> bitCount);
            }
            pendingBits &= (1L << bitCount) - 1;
        }

        byte[] finish() {
            if (bitCount > 0 && !isFull()) {
                out[bytePos++] = (byte) (pendingBits << (8 - bitCount));
            }
            return bytePos == out.length ? out : java.util.Arrays.copyOf(out, bytePos);
        }
    }
}
