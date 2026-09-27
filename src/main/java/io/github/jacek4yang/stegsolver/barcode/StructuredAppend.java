package io.github.jacek4yang.stegsolver.barcode;

/**
 * Structured Append information of a QR code: a long message can be split over up to 16 symbols, each
 * carrying its position and a parity byte that identifies the sequence.
 *
 * @param index    0 based position of the symbol in the sequence
 * @param total    number of symbols in the sequence (1..16)
 * @param parity   the parity byte shared by all symbols of the sequence
 * @param rawValue the raw 8 bit sequence value from the symbol (index in the high nibble)
 */
public record StructuredAppend(int index, int total, int parity, int rawValue) {

    public static final int MAX_SYMBOLS = 16;

    /** Builds the record from the raw byte ZXing exposes as {@code STRUCTURED_APPEND_SEQUENCE}. */
    public static StructuredAppend fromRawSequence(int rawSequence, int parity) {
        int index = (rawSequence >> 4) & 0x0f;
        int total = (rawSequence & 0x0f) + 1;
        return new StructuredAppend(index, total, parity, rawSequence & 0xff);
    }

    /** The value ZXing stores in the result metadata. */
    public int sequenceValue() {
        return (index << 4) | ((total - 1) & 0x0f);
    }

    public String describe() {
        return "Structured Append symbol " + (index + 1) + " of " + total
                + " (parity 0x" + Integer.toHexString(parity) + ")";
    }
}
