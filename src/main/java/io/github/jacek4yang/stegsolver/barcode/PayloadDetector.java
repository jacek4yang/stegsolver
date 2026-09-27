package io.github.jacek4yang.stegsolver.barcode;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Classifies the exact bytes recovered from a barcode and describes them.
 *
 * <p>The detector never modifies, decodes further or executes anything: it only inspects. Its job is
 * to answer "what is this?" with a signature match, a suggested file extension and a few facts
 * (archive entry count, image dimensions, PDF version, entropy) so that the analyst can decide what to
 * do next.</p>
 */
public final class PayloadDetector {

    /** Bytes inspected when looking for a signature. */
    private static final int SIGNATURE_WINDOW = 512;
    /** Maximum size of the textual preview embedded in the info. */
    private static final int TEXT_PREVIEW_LIMIT = 512;

    private PayloadDetector() {
    }

    /** Classifies the payload; {@code decodedText} may be {@code null}. */
    public static PayloadInfo detect(byte[] payload, String decodedText) {
        byte[] data = payload == null ? new byte[0] : payload;
        List<String> notes = new ArrayList<>();
        double entropy = entropy(data);
        int distinct = distinctByteValues(data);
        String sha256 = sha256(data);

        if (data.length == 0) {
            if (decodedText != null && !decodedText.isEmpty()) {
                // Text only symbol: there is no binary payload at all. StegSolver reports the text and
                // refuses to invent payload bytes by encoding it, which would silently corrupt binary
                // data the moment the charset is not the expected one.
                notes.add("The symbol carries text only: the decoder returned no BYTE_SEGMENTS, so there are "
                        + "no payload bytes to save");
                boolean ascii = decodedText.chars().allMatch(c -> c >= 32 && c < 127);
                return new PayloadInfo(ascii ? PayloadType.TEXT_ASCII : PayloadType.TEXT_UTF8, 0, sha256,
                        0, 0, decodedText, notes);
            }
            notes.add("The symbol decoded to nothing at all");
            return new PayloadInfo(PayloadType.EMPTY, 0, sha256, 0, 0, null, notes);
        }

        PayloadType type = detectType(data);
        String text = null;
        String textPreview = null;

        switch (type) {
            case ZIP -> {
                int entries = countZipEntries(data);
                notes.add("Local file header signature PK\\x03\\x04 found at offset 0");
                if (entries > 0) {
                    notes.add("Central directory lists " + entries + " entr" + (entries == 1 ? "y" : "ies"));
                } else {
                    notes.add("No central directory found, the stream may be truncated or it is a single "
                            + "deflate stream");
                }
                if (containsAscii(data, "META-INF/")) {
                    notes.add("Contains META-INF/, this looks like a JAR or APK style archive");
                }
                if (containsAscii(data, "AndroidManifest.xml")) {
                    notes.add("Contains AndroidManifest.xml, this looks like an APK");
                }
            }
            case SEVEN_ZIP -> notes.add("7-Zip signature 37 7A BC AF 27 1C found at offset 0");
            case RAR -> notes.add("RAR signature found at offset 0");
            case GZIP -> describeGzip(data, notes);
            case BZIP2 -> notes.add("bzip2 signature 42 5A 68 found at offset 0");
            case XZ -> notes.add("xz signature FD 37 7A 58 5A 00 found at offset 0");
            case TAR -> notes.add("ustar archive magic found at offset 257");
            case PNG -> describePng(data, notes);
            case JPEG -> describeJpeg(data, notes);
            case GIF -> describeGif(data, notes);
            case BMP -> {
                if (data.length >= 14) {
                    long size = le32u(data, 2);
                    notes.add("bitmap header declares " + size + " bytes");
                }
                notes.add("Signature 'BM' is only two bytes long, so this could be a false positive");
            }
            case WEBP -> notes.add("RIFF/WEBP container");
            case PDF -> describePdf(data, notes);
            case ELF -> describeElf(data, notes);
            case PE -> describePe(data, notes);
            case JAVA_CLASS -> {
                if (data.length >= 8) {
                    int major = (data[6] & 0xff) << 8 | (data[7] & 0xff);
                    notes.add("Class file major version " + major + " (Java "
                            + Math.max(0, major - 44) + " or later)");
                }
            }
            case TEXT_ASCII, TEXT_UTF8, BASE64_TEXT -> {
                text = new String(data, StandardCharsets.UTF_8);
                textPreview = truncate(text, TEXT_PREVIEW_LIMIT);
                if (type == PayloadType.BASE64_TEXT) {
                    notes.add("Payload is base64 encoded text");
                    describeBase64(data, notes);
                }
                if (decodedText != null && !decodedText.equals(text)) {
                    notes.add("Decoded text differs from the payload text, the symbol uses a different "
                            + "character encoding");
                }
            }
            case BINARY -> {
                notes.add("No known signature; the payload is not valid UTF-8 text either");
                notes.add("Leading bytes: " + HexFormat.of().formatHex(head(data, 16)));
            }
            default -> notes.add("No further details available");
        }

        if (type != PayloadType.TEXT_ASCII && type != PayloadType.TEXT_UTF8
                && type != PayloadType.BASE64_TEXT) {
            String ascii = printablePrefix(data, 64);
            if (!ascii.isEmpty()) {
                notes.add("Starts with printable ASCII: \"" + ascii + "\"");
            }
            if (looksLikeBase64(data)) {
                notes.add("The whole payload also looks like base64 encoded text");
                describeBase64(data, notes);
            }
        }

        if (entropy > 0 && !type.isText()) {
            notes.add(String.format(Locale.ROOT,
                    "Entropy %.2f bits per byte over %d distinct byte values (%d bytes)",
                    entropy, distinct, data.length));
        }

        PayloadInfo info = new PayloadInfo(type, data.length, sha256, entropy, distinct,
                text == null ? textPreview : text, notes);
        if (info.looksCompressedOrEncrypted()) {
            notes.add(String.format(Locale.ROOT,
                    "Normalised entropy %.2f: the data is compressed or encrypted", info.normalisedEntropy()));
        }
        return info;
    }

    /** Signature only; no notes. Returns {@link PayloadType#BINARY} when nothing matches. */
    public static PayloadType detectType(byte[] data) {
        if (data == null || data.length == 0) {
            return PayloadType.EMPTY;
        }
        if (startsWith(data, 0x50, 0x4b, 0x03, 0x04) || startsWith(data, 0x50, 0x4b, 0x05, 0x06)
                || startsWith(data, 0x50, 0x4b, 0x07, 0x08)) {
            return PayloadType.ZIP;
        }
        if (startsWith(data, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)) {
            return PayloadType.SEVEN_ZIP;
        }
        if (startsWith(data, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) {
            return PayloadType.RAR;
        }
        if (startsWith(data, 0x1f, 0x8b)) {
            return PayloadType.GZIP;
        }
        if (startsWith(data, 0x42, 0x5a, 0x68)) {
            return PayloadType.BZIP2;
        }
        if (startsWith(data, 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00)) {
            return PayloadType.XZ;
        }
        if (data.length > 262 && matchesAscii(data, 257, "ustar")) {
            return PayloadType.TAR;
        }
        if (startsWith(data, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return PayloadType.PNG;
        }
        if (startsWith(data, 0xff, 0xd8, 0xff)) {
            return PayloadType.JPEG;
        }
        if (startsWith(data, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
                || startsWith(data, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) {
            return PayloadType.GIF;
        }
        if (startsWith(data, 0x42, 0x4d)) {
            return PayloadType.BMP;
        }
        if (startsWith(data, 0x52, 0x49, 0x46, 0x46) && matchesAscii(data, 8, "WEBP")) {
            return PayloadType.WEBP;
        }
        if (startsWith(data, 0x25, 0x50, 0x44, 0x46)) {
            return PayloadType.PDF;
        }
        if (startsWith(data, 0x7f, 0x45, 0x4c, 0x46)) {
            return PayloadType.ELF;
        }
        if (startsWith(data, 0x4d, 0x5a)) {
            return PayloadType.PE;
        }
        if (startsWith(data, 0xca, 0xfe, 0xba, 0xbe)) {
            return PayloadType.JAVA_CLASS;
        }
        if (isAsciiText(data)) {
            return looksLikeBase64(data) ? PayloadType.BASE64_TEXT : PayloadType.TEXT_ASCII;
        }
        if (isUtf8Text(data)) {
            return PayloadType.TEXT_UTF8;
        }
        return PayloadType.BINARY;
    }

    /** Number of distinct byte values present in the payload. */
    public static int distinctByteValues(byte[] data) {
        boolean[] seen = new boolean[256];
        int distinct = 0;
        for (byte value : data) {
            if (!seen[value & 0xff]) {
                seen[value & 0xff] = true;
                distinct++;
            }
        }
        return distinct;
    }

    /** Shannon entropy of the payload in bits per byte. */
    public static double entropy(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        int[] histogram = new int[256];
        for (byte value : data) {
            histogram[value & 0xff]++;
        }
        double total = data.length;
        double entropy = 0;
        for (int count : histogram) {
            if (count == 0) {
                continue;
            }
            double probability = count / total;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }

    public static String sha256(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "(unavailable)";
        }
    }

    /** True when every byte is printable ASCII (with tab, newline and carriage return allowed). */
    public static boolean isAsciiText(byte[] data) {
        if (data.length == 0) {
            return false;
        }
        for (byte value : data) {
            int c = value & 0xff;
            if (c == 9 || c == 10 || c == 13) {
                continue;
            }
            if (c < 32 || c > 126) {
                return false;
            }
        }
        return true;
    }

    /** True when the payload decodes as UTF-8 and contains no control characters. */
    public static boolean isUtf8Text(byte[] data) {
        if (data.length == 0) {
            return false;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data))
                    .toString();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                    return false;
                }
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Heuristic: a base64 blob with a length that is a multiple of four. */
    public static boolean looksLikeBase64(byte[] data) {
        if (data.length < 8 || data.length % 4 != 0 || data.length > 64 * 1024 * 1024) {
            return false;
        }
        int padding = 0;
        for (int i = 0; i < data.length; i++) {
            int c = data[i] & 0xff;
            boolean valid = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r';
            if (!valid) {
                return false;
            }
            if (c == '=') {
                padding++;
            } else if (padding > 0) {
                return false;
            }
        }
        return padding <= 2;
    }

    private static void describeBase64(byte[] data, List<String> notes) {
        try {
            byte[] decoded = Base64.getDecoder().decode(new String(data, StandardCharsets.US_ASCII)
                    .replaceAll("\\s", ""));
            PayloadType inner = detectType(decoded);
            notes.add("Base64 decodes to " + decoded.length + " bytes of "
                    + (inner == PayloadType.BINARY ? "unrecognised binary data" : inner.description()));
            if (inner != PayloadType.BINARY && inner != PayloadType.EMPTY && !inner.isText()) {
                notes.add("Look at the decoded content as " + inner.description()
                        + " (StegSolver only reports this, it never opens or extracts payloads)");
            }
        } catch (RuntimeException e) {
            notes.add("Looked like base64 but could not be decoded: " + e.getClass().getSimpleName());
        }
    }

    private static void describeGzip(byte[] data, List<String> notes) {
        notes.add("gzip signature 1F 8B found at offset 0");
        if (data.length >= 4) {
            int method = data[2] & 0xff;
            int flags = data[3] & 0xff;
            notes.add("Compression method " + method + (method == 8 ? " (deflate)" : ""));
            notes.add("Flags 0x" + Integer.toHexString(flags)
                    + ((flags & 0x08) != 0 ? " (contains the original file name)" : "")
                    + ((flags & 0x10) != 0 ? " (contains a comment)" : ""));
            if ((flags & 0x04) != 0) {
                notes.add("Extra fields are present, which is a common place to hide data");
            }
        }
    }

    private static void describePng(byte[] data, List<String> notes) {
        notes.add("PNG signature found at offset 0");
        if (data.length >= 24) {
            long width = be32u(data, 16);
            long height = be32u(data, 20);
            notes.add("IHDR declares " + width + "x" + height + " pixels, bit depth "
                    + (data[24] & 0xff) + ", colour type " + (data.length > 25 ? data[25] & 0xff : 0));
        }
    }

    private static void describeJpeg(byte[] data, List<String> notes) {
        notes.add("JPEG signature FF D8 FF found at offset 0");
        if (data.length >= 6 && (data[3] & 0xff) == 0xe0) {
            notes.add("Starts with a JFIF APP0 segment");
        }
    }

    private static void describeGif(byte[] data, List<String> notes) {
        if (data.length >= 10) {
            notes.add("GIF " + new String(data, 0, 6, StandardCharsets.US_ASCII) + ", "
                    + le16(data, 6) + "x" + le16(data, 8));
        }
    }

    private static void describePdf(byte[] data, List<String> notes) {
        notes.add("PDF signature found at offset 0");
        String header = new String(head(data, 16), StandardCharsets.ISO_8859_1);
        int newline = header.indexOf('\n');
        notes.add("Header: " + (newline > 0 ? header.substring(0, newline) : header).trim());
    }

    private static void describeElf(byte[] data, List<String> notes) {
        notes.add("ELF signature 7F 45 4C 46 found at offset 0");
        if (data.length >= 18) {
            int elfClass = data[4] & 0xff;
            int endian = data[5] & 0xff;
            int type = le16(data, 16);
            notes.add("Class " + (elfClass == 1 ? "32 bit" : elfClass == 2 ? "64 bit" : "unknown")
                    + ", " + (endian == 1 ? "little endian" : "big endian")
                    + ", type " + switch (type) {
                        case 1 -> "relocatable object";
                        case 2 -> "executable";
                        case 3 -> "shared object";
                        case 4 -> "core dump";
                        default -> "unknown (" + type + ")";
                    });
        }
    }

    private static void describePe(byte[] data, List<String> notes) {
        notes.add("DOS MZ header found at offset 0");
        if (data.length >= 0x40) {
            long peOffset = le32u(data, 0x3c);
            if (peOffset > 0 && peOffset + 4 <= data.length
                    && matchesAscii(data, (int) peOffset, "PE\u0000\u0000")) {
                int machine = le16(data, (int) peOffset + 4);
                notes.add("PE header at 0x" + Long.toHexString(peOffset) + ", machine 0x"
                        + Integer.toHexString(machine) + " ("
                        + switch (machine) {
                            case 0x014c -> "x86";
                            case 0x8664 -> "x86-64";
                            case 0x01c0, 0x01c4 -> "ARM";
                            case 0xaa64 -> "ARM64";
                            default -> "unknown";
                        } + ")");
            } else if (peOffset == 0) {
                notes.add("No PE header offset, this may be a DOS stub or a false positive");
            } else {
                notes.add("PE header offset 0x" + Long.toHexString(peOffset)
                        + " is outside the payload, the file is truncated");
            }
        }
    }

    private static int countZipEntries(byte[] data) {
        int from = Math.max(0, data.length - 66000);
        int count = 0;
        for (int i = from; i + 3 < data.length; i++) {
            if (data[i] == 0x50 && data[i + 1] == 0x4b && data[i + 2] == 0x01 && data[i + 3] == 0x02) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsAscii(byte[] data, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        int limit = Math.min(data.length, SIGNATURE_WINDOW);
        for (int i = 0; i + target.length <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static String printablePrefix(byte[] data, int maxBytes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, maxBytes); i++) {
            int c = data[i] & 0xff;
            if (c < 32 || c > 126) {
                break;
            }
            out.append((char) c);
        }
        return out.length() >= 4 ? out.toString() : "";
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static byte[] head(byte[] data, int count) {
        return java.util.Arrays.copyOf(data, Math.min(count, data.length));
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAscii(byte[] data, int offset, String text) {
        if (offset < 0 || offset + text.length() > data.length) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((data[offset + i] & 0xff) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long le32u(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24);
    }

    private static long be32u(byte[] data, int offset) {
        return ((data[offset] & 0xffL) << 24) | ((data[offset + 1] & 0xffL) << 16)
                | ((data[offset + 2] & 0xffL) << 8) | (data[offset + 3] & 0xffL);
    }
}
