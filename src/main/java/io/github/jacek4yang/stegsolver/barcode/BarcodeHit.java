package io.github.jacek4yang.stegsolver.barcode;

import com.google.zxing.BarcodeFormat;
import io.github.jacek4yang.stegsolver.core.Roi;
import java.util.List;
import java.util.Map;

/**
 * One decoded symbol.
 *
 * <p>Three different things are kept apart on purpose, because conflating them is how binary payloads
 * get corrupted:</p>
 * <ul>
 *   <li>{@link #payload()} — the exact bytes the symbol carried, taken verbatim from the decoder's
 *       {@code BYTE_SEGMENTS}. This is the only authoritative binary content, and it is never
 *       reconstructed by encoding {@link #text()}.</li>
 *   <li>{@link #text()} — the decoded text, which may be absent for a binary symbol.</li>
 *   <li>{@link #decoderRawBytes()} — the raw bytes ZXing exposes for the symbol (for QR these are the
 *       error corrected data codewords, including mode and padding bits, <em>not</em> the payload).</li>
 * </ul>
 *
 * @param format           detected symbology
 * @param text             decoded text, or {@code null}
 * @param payload          concatenation of {@code BYTE_SEGMENTS}, or {@code null} when the symbol has none
 * @param byteSegments     the {@code BYTE_SEGMENTS} exactly as the decoder produced them
 * @param decoderRawBytes  ZXing's raw bytes for the symbol, or {@code null}
 * @param points           symbol corner points in original image coordinates
 * @param bounds           bounding box of {@code points} in original image coordinates
 * @param rotationDegrees  counter clockwise rotation that had to be applied to find the symbol
 * @param inverted         whether the bitmap had to be inverted to find the symbol
 * @param metadata         printable ZXing metadata (error correction level, symbology name, ...)
 * @param structuredAppend structured append information, or {@code null}
 * @param payloadInfo      classification of {@link #payload()}
 */
public record BarcodeHit(BarcodeFormat format, String text, byte[] payload, List<byte[]> byteSegments,
        byte[] decoderRawBytes, List<RotationMapper.Point> points, Roi bounds, int rotationDegrees,
        boolean inverted, Map<String, String> metadata, StructuredAppend structuredAppend,
        PayloadInfo payloadInfo) {

    public BarcodeHit {
        byteSegments = byteSegments == null ? List.of() : List.copyOf(byteSegments);
        points = points == null ? List.of() : List.copyOf(points);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** True when the symbol carried real binary payload bytes. */
    public boolean hasBinaryPayload() {
        return payload != null && payload.length > 0;
    }

    /** Payload bytes when available, otherwise an empty array; never a re-encoding of the text. */
    public byte[] payloadOrEmpty() {
        return payload == null ? new byte[0] : payload;
    }

    public int payloadSize() {
        return payload == null ? 0 : payload.length;
    }

    /** True when the symbol decoded to text but carried no byte segments. */
    public boolean isTextOnly() {
        return !hasBinaryPayload() && text != null && !text.isEmpty();
    }

    /** Short label such as {@code QR_CODE (ZIP)} or {@code CODE_128 (text only)}. */
    public String label() {
        String suffix;
        if (hasBinaryPayload()) {
            suffix = payloadInfo.type().description();
        } else if (isTextOnly()) {
            suffix = "text only";
        } else {
            suffix = "no content";
        }
        return format.name() + " — " + suffix;
    }

    /** Description used in list cells. */
    public String listLabel() {
        StringBuilder out = new StringBuilder();
        out.append(format.name());
        if (hasBinaryPayload()) {
            out.append(" · ").append(payloadSize()).append(" bytes · ")
                    .append(payloadInfo.type().description());
        } else if (isTextOnly()) {
            out.append(" · text · ").append(text.length() > 40 ? text.substring(0, 40) + "…" : text);
        }
        if (structuredAppend != null) {
            out.append(" · SA ").append(structuredAppend.index() + 1).append('/')
                    .append(structuredAppend.total());
        }
        if (rotationDegrees != 0) {
            out.append(" · rotated ").append(rotationDegrees).append("°");
        }
        if (inverted) {
            out.append(" · inverted");
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "BarcodeHit[" + listLabel() + ", bounds=" + bounds + "]";
    }
}
