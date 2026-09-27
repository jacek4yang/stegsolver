package io.github.jacek4yang.stegsolver.core;

import java.util.Locale;

/**
 * Canonical hex/ASCII dump used by the extraction preview, the barcode payload preview and the file
 * analysers, so that everything the user looks at is formatted identically.
 */
public final class HexDump {

    public static final int BYTES_PER_LINE = 16;

    private HexDump() {
    }

    /**
     * Formats up to {@code maxBytes} bytes of {@code data} starting at {@code offset}.
     *
     * <p>Every line contains the byte offset, the hex bytes (with a gap after the eighth byte) and
     * the printable ASCII rendering, matching the layout of the original StegSolve preview.</p>
     */
    public static String format(byte[] data, int offset, int length, int maxBytes, boolean includeHex) {
        if (data == null || data.length == 0) {
            return "(empty)";
        }
        int from = Math.max(0, Math.min(offset, data.length));
        int available = Math.min(length, data.length - from);
        if (available <= 0) {
            return "(empty)";
        }
        int shown = Math.min(available, Math.max(0, maxBytes));
        if (shown <= 0) {
            return "… " + available + " more bytes not shown (offset " + from + ")";
        }
        StringBuilder out = new StringBuilder(shown * 5 + 128);
        for (int i = 0; i < shown; i += BYTES_PER_LINE) {
            if (i > 0) {
                out.append('\n');
            }
            appendLine(out, data, from + i, Math.min(BYTES_PER_LINE, shown - i), from + i, includeHex);
        }
        if (shown < available) {
            out.append('\n').append("… ").append(available - shown)
                    .append(" more bytes not shown (offset ").append(from + shown).append(')');
        }
        return out.toString();
    }

    /** Streams an entire export without constructing a string proportional to payload size. */
    public static void write(java.io.Writer writer, byte[] data, boolean includeHex) throws java.io.IOException {
        for (int offset = 0; offset < data.length; offset += 4096) {
            if (offset > 0) writer.write('\n');
            int length = Math.min(4096, data.length - offset);
            writer.write(format(data, offset, length, length, includeHex));
        }
    }

    private static void appendLine(StringBuilder out, byte[] data, int start, int count, int address,
            boolean includeHex) {
        out.append(String.format(Locale.ROOT, "%08x  ", address));
        if (includeHex) {
            for (int j = 0; j < BYTES_PER_LINE; j++) {
                if (j < count) {
                    out.append(String.format(Locale.ROOT, "%02x", data[start + j] & 0xff));
                } else {
                    out.append("  ");
                }
                if (j == 7) {
                    out.append(' ');
                }
            }
            out.append("  ");
        }
        for (int j = 0; j < count; j++) {
            out.append(printable(data[start + j]));
            if (j == 7) {
                out.append(' ');
            }
        }
    }

    /** The ASCII rendering used in dumps: printable characters stay, everything else becomes '.'. */
    public static char printable(byte value) {
        int c = value & 0xff;
        return c >= 32 && c < 127 ? (char) c : '.';
    }

    /** Plain ASCII rendering of a byte range, one character per byte. */
    public static String ascii(byte[] data, int offset, int length) {
        int from = Math.max(0, Math.min(offset, data.length));
        int count = Math.max(0, Math.min(length, data.length - from));
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(printable(data[from + i]));
        }
        return out.toString();
    }

    /** Compact, space separated hex, optionally truncated, used by "Copy Hex". */
    public static String hex(byte[] data, int offset, int length, int maxBytes) {
        if (data == null || data.length == 0) {
            return "";
        }
        int from = Math.max(0, Math.min(offset, data.length));
        int count = Math.min(length, data.length - from);
        int shown = Math.min(count, Math.max(0, maxBytes));
        StringBuilder out = new StringBuilder(shown * 3);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%02x", data[from + i] & 0xff));
        }
        if (shown < count) {
            out.append(" … (").append(count - shown).append(" more bytes)");
        }
        return out.toString();
    }

    /** Contiguous lower case hex, one byte per two characters, no separators. */
    public static String hexCompact(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
