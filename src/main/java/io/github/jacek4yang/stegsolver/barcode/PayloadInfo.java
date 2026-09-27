package io.github.jacek4yang.stegsolver.barcode;

import java.util.List;

/**
 * Everything that could be determined about a decoded payload without interpreting it.
 *
 * @param type       detected payload type
 * @param size       payload size in bytes
 * @param sha256     lower case hex SHA-256 of the payload
 * @param entropy    Shannon entropy in bits per byte (8.0 for random/compressed data)
 * @param distinctBytes how many of the 256 possible byte values occur in the payload
 * @param text       textual rendering when the payload is text, otherwise {@code null}
 * @param notes      human readable findings, in presentation order
 */
public record PayloadInfo(PayloadType type, int size, String sha256, double entropy, int distinctBytes,
        String text, List<String> notes) {

    public PayloadInfo {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isText() {
        return type.isText();
    }

    /**
     * The entropy corrected for the sample size (Miller-Madow) and normalised to the number of byte
     * values that could occur. Small payloads never reach an observed entropy of 8 bits even when the
     * data is perfectly random, so a plain threshold on {@link #entropy()} would misjudge them.
     */
    public double normalisedEntropy() {
        if (size <= 0) {
            return 0;
        }
        int symbols = Math.min(256, size);
        double correction = symbols <= 1 ? 0
                : (distinctBytes - 1) / (2.0 * size * Math.log(2));
        double corrected = Math.max(0, entropy + correction);
        double maximum = Math.log(symbols) / Math.log(2);
        return maximum <= 0 ? 0 : Math.min(1.0, corrected / maximum);
    }

    /**
     * True when the payload looks incompressible, which is the signature of compressed or encrypted
     * content (and therefore of a wrapped or nested payload).
     *
     * <p>Two safeguards keep this honest: very short payloads cannot produce enough evidence, so they
     * never claim compression, and the threshold is applied to the {@link #normalisedEntropy()} value, which corrects
     * for the sample size rather than to the raw entropy, because a few hundred random bytes never reach an
     * observed entropy of 8 bits.</p>
     */
    public boolean looksCompressedOrEncrypted() {
        return size >= 96 && normalisedEntropy() > 0.9;
    }

    public String suggestedExtension() {
        return type.suggestedExtension();
    }

    public String description() {
        return type.description();
    }

    /** One line summary for lists: {@code ZIP archive, 4096 bytes, entropy 7.98}. */
    public String summary() {
        return String.format(java.util.Locale.ROOT, "%s, %d bytes, entropy %.2f",
                type.description(), size, entropy);
    }
}
