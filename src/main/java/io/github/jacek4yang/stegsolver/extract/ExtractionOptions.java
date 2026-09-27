package io.github.jacek4yang.stegsolver.extract;

import io.github.jacek4yang.stegsolver.core.Channel;

/**
 * The options of a data extraction run.
 *
 * <p>The selection is a 32 bit mask in "plane space": bit {@code channel.ordinal() * 8 + plane} is
 * set when that bit plane is extracted. Keeping it as a single {@code int} makes the options compact,
 * comparable and cheap to test, while {@link #argbMask()} reproduces the ARGB mask the original
 * StegSolve showed on screen.</p>
 *
 * @param planeMask  selection in plane space, see above
 * @param order      order in which the colour channels are visited
 * @param lsbFirst   {@code true} extracts bit 0 before bit 7 of each channel
 * @param rowFirst   {@code true} traverses the image row by row, {@code false} column by column
 * @param invertBits {@code true} inverts every extracted bit (a legacy TODO that is now supported)
 */
public record ExtractionOptions(int planeMask, RgbOrder order, boolean lsbFirst, boolean rowFirst,
        boolean invertBits) {

    public static final int ALL_PLANES = -1;

    public ExtractionOptions {
        if (order == null) {
            throw new IllegalArgumentException("order is required");
        }
        planeMask &= ALL_PLANES;
    }

    /** Nothing selected, which produces an empty extract. */
    public static ExtractionOptions none() {
        return new ExtractionOptions(0, RgbOrder.RGB, false, true, false);
    }

    /**
     * The default selection: the least significant bit of red, green and blue, which is where LSB
     * steganography normally lives. The legacy tool started with nothing selected.
     */
    public static ExtractionOptions defaults() {
        return none()
                .with(Channel.RED, 0, true)
                .with(Channel.GREEN, 0, true)
                .with(Channel.BLUE, 0, true);
    }

    /** Every bit plane of every channel. */
    public static ExtractionOptions allPlanes() {
        return new ExtractionOptions(ALL_PLANES, RgbOrder.RGB, false, true, false);
    }

    /** Number of selected bit planes, 0..32. */
    public int selectedCount() {
        return Integer.bitCount(planeMask);
    }

    public boolean isEmpty() {
        return planeMask == 0;
    }

    public boolean isSelected(Channel channel, int plane) {
        if (plane < 0 || plane > 7) {
            throw new IllegalArgumentException("plane must be within 0..7 but was " + plane);
        }
        return (planeMask & bitFor(channel, plane)) != 0;
    }

    public static int bitFor(Channel channel, int plane) {
        return 1 << (channel.ordinal() * 8 + plane);
    }

    /** Selects or clears a single bit plane. */
    public ExtractionOptions with(Channel channel, int plane, boolean selected) {
        int bit = bitFor(channel, plane);
        int mask = selected ? (planeMask | bit) : (planeMask & ~bit);
        return new ExtractionOptions(mask, order, lsbFirst, rowFirst, invertBits);
    }

    /** Selects or clears all eight bit planes of a channel. */
    public ExtractionOptions withChannel(Channel channel, boolean selected) {
        int mask = 0xff << (channel.ordinal() * 8);
        return new ExtractionOptions(selected ? (planeMask | mask) : (planeMask & ~mask), order,
                lsbFirst, rowFirst, invertBits);
    }

    /** Selects or clears one bit plane across all four channels. */
    public ExtractionOptions withPlaneAcrossChannels(int plane, boolean selected) {
        int mask = 0;
        for (Channel channel : Channel.values()) {
            mask |= bitFor(channel, plane);
        }
        return new ExtractionOptions(selected ? (planeMask | mask) : (planeMask & ~mask), order,
                lsbFirst, rowFirst, invertBits);
    }

    public ExtractionOptions withOrder(RgbOrder newOrder) {
        return new ExtractionOptions(planeMask, newOrder, lsbFirst, rowFirst, invertBits);
    }

    public ExtractionOptions withLsbFirst(boolean value) {
        return new ExtractionOptions(planeMask, order, value, rowFirst, invertBits);
    }

    public ExtractionOptions withRowFirst(boolean value) {
        return new ExtractionOptions(planeMask, order, lsbFirst, value, invertBits);
    }

    public ExtractionOptions withInvertBits(boolean value) {
        return new ExtractionOptions(planeMask, order, lsbFirst, rowFirst, value);
    }

    /**
     * The mask the legacy user interface displayed: one ARGB bit per selected plane, alpha occupying
     * bits 24..31 (alpha plane 7 is bit 31), exactly as the original built it.
     */
    public int argbMask() {
        int mask = 0;
        for (Channel channel : Channel.values()) {
            for (int plane = 0; plane < 8; plane++) {
                if (isSelected(channel, plane)) {
                    mask |= 1 << (channel.shift() + plane);
                }
            }
        }
        return mask;
    }

    /** Handy for status text such as {@code "a7 r0 g0 b0"}. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (Channel channel : Channel.values()) {
            for (int plane = 7; plane >= 0; plane--) {
                if (isSelected(channel, plane)) {
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                    text.append(channel.symbol()).append(plane);
                }
            }
        }
        return text.isEmpty() ? "(no bit planes selected)" : text.toString();
    }

    /** Number of bytes that {@code pixelCount} pixels produce with this selection. */
    public long outputBytesFor(long pixelCount) {
        long bits = pixelCount * selectedCount();
        return (bits + 7) / 8;
    }
}
