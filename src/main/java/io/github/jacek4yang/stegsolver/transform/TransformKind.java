package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.Channel;

/**
 * The kind of operation a transform performs.
 */
public enum TransformKind {

    /** The untouched source image. */
    ORIGINAL("Original"),
    /** Inverted colours ({@code pixel XOR 0xFFFFFF}), also known as the "XOR 0xFFFFFF" view. */
    INVERT("Invert"),
    /** A single bit plane of one channel, shown as black/white. */
    PLANE("Bit plane"),
    /** One channel isolated, other channels masked away. */
    FULL_CHANNEL("Channel"),
    /** A pseudo random colour map, as a hint for structure hidden in a colour mapped image. */
    RANDOM_MAP("Random colour map"),
    /** Only the pixels where {@code r == g == b} are highlighted. */
    GRAY_BITS("Gray pixels");

    private final String label;

    TransformKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True when the kind only makes sense together with a specific channel. */
    public boolean isChannelSpecific() {
        return this == PLANE || this == FULL_CHANNEL;
    }
}
