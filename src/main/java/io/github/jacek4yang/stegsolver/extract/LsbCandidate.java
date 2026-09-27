package io.github.jacek4yang.stegsolver.extract;

import io.github.jacek4yang.stegsolver.barcode.PayloadDetector;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.barcode.PayloadType;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.HexDump;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A candidate result from an automatic LSB scan.
 *
 * @param options      the exact extraction configuration that produced this result
 * @param score        ranking score from 0 (noise/empty) to 100 (confirmed file format)
 * @param reason       human-readable deterministic reason for this score
 * @param payloadInfo  detailed classification from {@link PayloadDetector}
 * @param prefixData   bounded extracted bytes (Phase 1 prefix, e.g. up to 64 KiB)
 * @param totalBytes   estimated total output bytes for the full image or selection
 * @param truncated    {@code true} if {@code prefixData} is smaller than {@code totalBytes}
 */
public record LsbCandidate(
        ExtractionOptions options,
        int score,
        String reason,
        PayloadInfo payloadInfo,
        byte[] prefixData,
        long totalBytes,
        boolean truncated) {

    public LsbCandidate {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(payloadInfo, "payloadInfo");
        prefixData = prefixData == null ? new byte[0] : prefixData;
    }

    /** Formats the configuration cleanly, e.g. {@code "RGB · bit 0 · Row-major · MSB first"}. */
    public String formattedConfig() {
        return formatConfig(options);
    }

    /** Short type name for table display, e.g. "ZIP", "Text", "PNG", "Base64", "Binary". */
    public String typeName() {
        if (payloadInfo.type() == PayloadType.TEXT_ASCII || payloadInfo.type() == PayloadType.TEXT_UTF8) {
            return "Text";
        }
        if (payloadInfo.type() == PayloadType.BASE64_TEXT) {
            return "Base64";
        }
        if (payloadInfo.type() == PayloadType.SEVEN_ZIP) {
            return "7z";
        }
        if (payloadInfo.type() == PayloadType.JAVA_CLASS) {
            return "Class";
        }
        return payloadInfo.type().badge();
    }

    /** Compact preview snippet for the table column, e.g. {@code "flag{...}"} or {@code "PK\x03\x04..."}. */
    public String previewSnippet() {
        if (prefixData.length == 0) {
            return "(empty)";
        }
        // If CTF flag is detected in reason, show it
        if (reason.startsWith("CTF flag: ")) {
            return reason.substring("CTF flag: ".length());
        }
        if (payloadInfo.type().isText() && payloadInfo.text() != null && !payloadInfo.text().isBlank()) {
            String text = payloadInfo.text().replaceAll("[\\r\\n\\t]+", " ").trim();
            return text.length() <= 32 ? text : text.substring(0, 30) + "\u2026";
        }
        // Readable ASCII prefix if any
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < Math.min(prefixData.length, 32); i++) {
            int c = prefixData[i] & 0xff;
            if (c >= 32 && c <= 126) {
                ascii.append((char) c);
            } else {
                ascii.append('.');
            }
        }
        String s = ascii.toString().trim();
        return s.length() <= 32 ? s : s.substring(0, 30) + "\u2026";
    }

    /** Formatted preview (HexDump or text) for the detail panel. */
    public String previewText(boolean includeHexDump, int limit) {
        if (prefixData.length == 0) {
            return "No data extracted.";
        }
        int effectiveLimit = limit <= 0 ? prefixData.length : Math.min(limit, prefixData.length);
        if (!includeHexDump && payloadInfo.type().isText() && payloadInfo.text() != null) {
            return payloadInfo.text();
        }
        return HexDump.format(prefixData, 0, prefixData.length, effectiveLimit, includeHexDump);
    }

    /** Stable fingerprint of the scanned prefix bytes for deduplication. */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prefixData);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(prefixData)) + "-" + prefixData.length;
        }
    }

    /** Human-readable configuration description adhering to project conventions. */
    public static String formatConfig(ExtractionOptions options) {
        if (options == null || options.isEmpty()) {
            return "(none)";
        }
        List<String> parts = new ArrayList<>();

        // 1. Channel component
        boolean hasA = false, hasR = false, hasG = false, hasB = false;
        for (int p = 0; p < 8; p++) {
            if (options.isSelected(Channel.ALPHA, p)) hasA = true;
            if (options.isSelected(Channel.RED, p)) hasR = true;
            if (options.isSelected(Channel.GREEN, p)) hasG = true;
            if (options.isSelected(Channel.BLUE, p)) hasB = true;
        }

        if (hasR && hasG && hasB && !hasA) {
            parts.add(options.order().label());
        } else if (hasR && hasG && hasB && hasA) {
            parts.add("A+" + options.order().label());
        } else if (hasR && !hasG && !hasB && !hasA) {
            parts.add("R");
        } else if (!hasR && hasG && !hasB && !hasA) {
            parts.add("G");
        } else if (!hasR && !hasG && hasB && !hasA) {
            parts.add("B");
        } else if (!hasR && !hasG && !hasB && hasA) {
            parts.add("A");
        } else {
            StringBuilder ch = new StringBuilder();
            if (hasA) ch.append("A");
            for (Channel c : options.order().colours()) {
                if (c == Channel.RED && hasR) ch.append("R");
                if (c == Channel.GREEN && hasG) ch.append("G");
                if (c == Channel.BLUE && hasB) ch.append("B");
            }
            parts.add(ch.isEmpty() ? options.order().label() : ch.toString());
        }

        // 2. Bit planes component
        List<Integer> selectedPlanes = new ArrayList<>();
        for (int p = 0; p < 8; p++) {
            boolean inAny = options.isSelected(Channel.RED, p) || options.isSelected(Channel.GREEN, p)
                    || options.isSelected(Channel.BLUE, p) || options.isSelected(Channel.ALPHA, p);
            if (inAny) {
                selectedPlanes.add(p);
            }
        }
        if (selectedPlanes.size() == 1) {
            parts.add("b" + selectedPlanes.get(0));
        } else if (!selectedPlanes.isEmpty()) {
            parts.add("b" + selectedPlanes.stream().map(String::valueOf).collect(Collectors.joining(",")));
        }

        // 3. Traversal component
        parts.add(options.rowFirst() ? "Row" : "Col");

        // 4. Bit order component
        parts.add(options.lsbFirst() ? "LSB" : "MSB");

        // 5. Inversion component
        if (options.invertBits()) {
            parts.add("Inverted");
        }

        return String.join(" \u00b7 ", parts);
    }
}
