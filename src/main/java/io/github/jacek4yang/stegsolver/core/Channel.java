package io.github.jacek4yang.stegsolver.core;

/**
 * A colour channel of a 32 bit ARGB pixel.
 *
 * <p>The {@link #shift()} is the distance from the least significant bit, i.e. an 8 bit channel
 * value can be read with {@code (argb >>> channel.shift()) & 0xff} and the bit plane {@code p} of a
 * channel is {@code (argb >>> (channel.shift() + p)) & 1}.</p>
 *
 * <p>The declaration order is the visual order used by the user interface (alpha, red, green,
 * blue); it is <em>not</em> the extraction order, which is configurable, see
 * {@link io.github.jacek4yang.stegsolver.extract.RgbOrder}.</p>
 */
public enum Channel {

    ALPHA("Alpha", 24),
    RED("Red", 16),
    GREEN("Green", 8),
    BLUE("Blue", 0);

    private final String label;
    private final int shift;

    Channel(String label, int shift) {
        this.label = label;
        this.shift = shift;
    }

    public String label() {
        return label;
    }

    public int shift() {
        return shift;
    }

    /** Single character name used in compact status text such as {@code a=..,r=..,g=..,b=..}. */
    public char symbol() {
        return Character.toLowerCase(label.charAt(0));
    }

    /** Extracts the 8 bit channel value of an ARGB pixel. */
    public int value(int argb) {
        return (argb >>> shift) & 0xff;
    }

    /** Extracts bit plane {@code plane} (0 = least significant) of an ARGB pixel. */
    public int planeBit(int argb, int plane) {
        return (argb >>> (shift + plane)) & 1;
    }

    /** The mask that selects this channel inside an ARGB int. */
    public int mask() {
        return 0xff << shift;
    }
}
