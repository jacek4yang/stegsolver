package io.github.jacek4yang.stegsolver.parser;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Bounds checked accessor for a byte array.
 *
 * <p>Every numeric reader returns {@code 0} (or an empty string) when asked for bytes outside the
 * buffer. Parsers therefore cannot crash on malformed input, while {@link #has(int, int)} lets them
 * report the problem explicitly instead of silently reading zeros.</p>
 */
public final class ByteReader {

    private final byte[] data;

    public ByteReader(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        this.data = data;
    }

    public byte[] array() {
        return data;
    }

    public int size() {
        return data.length;
    }

    public boolean has(int offset) {
        return offset >= 0 && offset < data.length;
    }

    /** True when {@code length} bytes starting at {@code offset} are inside the buffer. */
    public boolean has(int offset, int length) {
        return offset >= 0 && length >= 0 && (long) offset + length <= data.length;
    }

    public int u8(int offset) {
        return has(offset) ? data[offset] & 0xff : 0;
    }

    /** Little endian unsigned 16 bit value. */
    public int le16(int offset) {
        return u8(offset) | (u8(offset + 1) << 8);
    }

    /** Big endian unsigned 16 bit value. */
    public int be16(int offset) {
        return (u8(offset) << 8) | u8(offset + 1);
    }

    /** Little endian 32 bit value, sign extended (use {@link #le32u} for sizes). */
    public int le32(int offset) {
        return u8(offset) | (u8(offset + 1) << 8) | (u8(offset + 2) << 16) | (u8(offset + 3) << 24);
    }

    /** Little endian unsigned 32 bit value as a long. */
    public long le32u(int offset) {
        return le32(offset) & 0xffffffffL;
    }

    /** Big endian 32 bit value, sign extended. */
    public int be32(int offset) {
        return (u8(offset) << 24) | (u8(offset + 1) << 16) | (u8(offset + 2) << 8) | u8(offset + 3);
    }

    /** Big endian unsigned 32 bit value as a long. */
    public long be32u(int offset) {
        return be32(offset) & 0xffffffffL;
    }

    /** Four character chunk type or marker, for example {@code IHDR}. */
    public String fourCcBe(int offset) {
        if (!has(offset, 4)) {
            return "";
        }
        return new String(data, offset, 4, StandardCharsets.ISO_8859_1);
    }

    /** Printable ASCII rendering of a range; unprintable bytes become '.'. */
    public String ascii(int offset, int length) {
        int from = Math.max(0, offset);
        int count = Math.max(0, Math.min(4096, Math.min(length, data.length - from)));
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            int c = data[from + i] & 0xff;
            out.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return out.toString();
    }

    /** Printable ASCII rendering, stopping at the first NUL byte (PNG/GIF keywords, comments). */
    public String asciiUntilNul(int offset, int length) {
        int from = Math.max(0, offset);
        int count = Math.max(0, Math.min(4096, Math.min(length, data.length - from)));
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            int c = data[from + i] & 0xff;
            if (c == 0) {
                break;
            }
            out.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return out.toString();
    }

    /** Copy of a range; the caller must have checked the range with {@link #has(int, int)}. */
    public byte[] slice(int offset, int length) {
        if (!has(offset, length)) {
            throw new IndexOutOfBoundsException(
                    "Requested " + length + " bytes at " + offset + " of " + data.length);
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        return copy;
    }

    /** Lower case hex of a range, truncated for readability. */
    public String hex(int offset, int length, int maxBytes) {
        int shown = Math.min(Math.max(0, length), Math.max(0, maxBytes));
        StringBuilder out = new StringBuilder(shown * 3);
        for (int i = 0; i < shown; i++) {
            if (!has(offset + i)) {
                break;
            }
            out.append(String.format(Locale.ROOT, "%02x", data[offset + i] & 0xff));
        }
        if (length > shown) {
            out.append(" …");
        }
        return out.toString();
    }
}
