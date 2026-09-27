package io.github.jacek4yang.stegsolver.barcode;

import java.util.List;

/**
 * Everything that could be determined about a decoded payload without interpreting it.
 *
 * @param type       detected payload type
 * @param size       payload size in bytes
 * @param sha256     lower case hex SHA-256 of the payload
 * @param entropy    Shannon entropy in bits per byte (8.0 for random/compressed data)
 * @param text       textual rendering when the payload is text, otherwise {@code null}
 * @param notes      human readable findings, in presentation order
 */
public record PayloadInfo(PayloadType type, int size, String sha256, double entropy, String text,
        List<String> notes) {

    public PayloadInfo {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isText() {
        return type.isText();
    }

    /** True when the payload is incompressible, which suggests compression or encryption. */
    public boolean looksCompressedOrEncrypted() {
        return size >= 64 && entropy > 7.5;
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
