package io.github.jacek4yang.stegsolver.extract;

import io.github.jacek4yang.stegsolver.core.Channel;
import java.util.Arrays;

/**
 * The order in which the red, green and blue channels are visited while extracting, matching the six
 * permutations offered by the original StegSolve.
 *
 * <p>Alpha is always visited first: the legacy extractor unconditionally started with the alpha byte
 * and only then walked the colour channels in the selected permutation.</p>
 */
public enum RgbOrder {

    RGB("RGB", 1, Channel.RED, Channel.GREEN, Channel.BLUE),
    RBG("RBG", 2, Channel.RED, Channel.BLUE, Channel.GREEN),
    GRB("GRB", 3, Channel.GREEN, Channel.RED, Channel.BLUE),
    GBR("GBR", 4, Channel.GREEN, Channel.BLUE, Channel.RED),
    BRG("BRG", 5, Channel.BLUE, Channel.RED, Channel.GREEN),
    BGR("BGR", 6, Channel.BLUE, Channel.GREEN, Channel.RED);

    private final String label;
    private final int legacyCode;
    private final Channel[] colours;

    RgbOrder(String label, int legacyCode, Channel... colours) {
        this.label = label;
        this.legacyCode = legacyCode;
        this.colours = colours;
    }

    public String label() {
        return label;
    }

    /** The numbering used by the legacy {@code rgbOrder} field (1 = RGB .. 6 = BGR). */
    public int legacyCode() {
        return legacyCode;
    }

    /** The colour channels in visit order, excluding alpha. */
    public Channel[] colours() {
        return colours.clone();
    }

    /** Alpha followed by the colour channels, i.e. the full visit order of the extractor. */
    public Channel[] visitOrder() {
        Channel[] order = new Channel[4];
        order[0] = Channel.ALPHA;
        System.arraycopy(colours, 0, order, 1, 3);
        return order;
    }

    public static RgbOrder byLegacyCode(int code) {
        return Arrays.stream(values())
                .filter(order -> order.legacyCode == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown RGB order code: " + code));
    }

    @Override
    public String toString() {
        return label;
    }
}
