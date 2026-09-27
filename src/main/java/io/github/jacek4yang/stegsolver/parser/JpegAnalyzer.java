package io.github.jacek4yang.stegsolver.parser;

import java.util.Locale;

/**
 * Structural analysis of a JPEG file: walks the marker segments, reports the frame geometry and every
 * application/comment segment (the usual hiding places) and measures the entropy coded scan data.
 *
 * <p>Malformed input is expected: a marker with an impossible length, a scan that never terminates or
 * a file without EOI all produce a warning instead of an exception.</p>
 */
final class JpegAnalyzer {

    private JpegAnalyzer() {
    }

    static void analyze(ByteReader reader, FileReport report) {
        report.openSection("JPEG structure");
        int pos = 0;
        if (reader.u8(0) == 0xff && reader.u8(1) == 0xd8) {
            report.add("Start of image (SOI) at offset 0");
            pos = 2;
        } else {
            report.warn("File does not start with the SOI marker");
        }

        int segmentCount = 0;
        boolean sawEoi = false;
        while (pos < reader.size()) {
            // Skip padding: markers may be preceded by any number of 0xFF fill bytes.
            int markerStart = pos;
            while (markerStart < reader.size() && reader.u8(markerStart) == 0xff) {
                markerStart++;
            }
            if (markerStart >= reader.size()) {
                break;
            }
            int marker = reader.u8(markerStart);
            if (marker == 0x00) {
                report.warn("Stray 0xFF00 sequence outside scan data at offset " + pos);
                pos = markerStart + 1;
                continue;
            }
            if (marker == 0xd9) {
                report.openSection("End of image");
                report.field("Offset", "0x" + Integer.toHexString(markerStart) + " (" + markerStart + ")");
                sawEoi = true;
                pos = markerStart + 1;
                break;
            }
            if (marker == 0xd8) {
                report.add("Unexpected second SOI marker at offset " + markerStart);
                pos = markerStart + 1;
                continue;
            }
            if (marker >= 0xd0 && marker <= 0xd7) {
                report.add("Restart marker RST" + (marker - 0xd0) + " at offset " + markerStart);
                pos = markerStart + 1;
                continue;
            }

            segmentCount++;
            report.openSection(String.format(Locale.ROOT, "Segment #%d FF%02X at 0x%x",
                    segmentCount, marker, markerStart));
            String name = markerName(marker);
            report.add("Marker: " + name);
            if (!reader.has(markerStart + 2, 2)) {
                report.warn("Segment FF" + String.format(Locale.ROOT, "%02X", marker)
                        + " at offset " + markerStart + " is truncated (no length field)");
                break;
            }
            int length = reader.be16(markerStart + 2);
            report.field("Declared length", length + " bytes (including these two)");
            if (length < 2) {
                report.warn("Segment FF" + String.format(Locale.ROOT, "%02X", marker)
                        + " declares an impossible length of " + length);
                break;
            }
            int dataStart = markerStart + 4;
            int dataLength = length - 2;
            if (!reader.has(dataStart, dataLength)) {
                report.warn("Segment FF" + String.format(Locale.ROOT, "%02X", marker)
                        + " declares " + dataLength + " bytes of data but only "
                        + Math.max(0, reader.size() - dataStart) + " are available");
                break;
            }

            switch (marker) {
                case 0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf ->
                    describeFrameHeader(reader, report, marker, dataStart, dataLength);
                case 0xc4 -> report.add("Huffman table");
                case 0xcc -> report.add("Arithmetic coding conditioning");
                case 0xdb -> report.add("Quantisation table");
                case 0xdc -> {
                    if (dataLength >= 2) {
                        report.field("Number of lines", reader.be16(dataStart));
                    }
                }
                case 0xdd -> {
                    if (dataLength >= 2) {
                        report.field("Restart interval", reader.be16(dataStart));
                    }
                }
                case 0xde -> report.add("Define hierarchical progression");
                case 0xdf -> report.add("Expand reference components");
                case 0xda -> {
                    report.add("Start of scan");
                    report.field("Samples per line", reader.be16(markerStart + 2));
                    int scanStart = dataStart + dataLength;
                    int scanEnd = findScanEnd(reader, scanStart);
                    int scanBytes = scanEnd - scanStart;
                    report.field("Entropy coded scan data", scanBytes + " bytes");
                    if (scanEnd >= reader.size()) {
                        report.warn("Scan data runs to the end of the file without a terminating marker");
                    } else {
                        report.field("Next marker", "0x" + Integer.toHexString(scanEnd)
                                + " (FF" + String.format(Locale.ROOT, "%02X", reader.u8(scanEnd + 1)) + ")");
                    }
                    pos = scanEnd;
                    continue;
                }
                case 0xe0, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xeb, 0xec, 0xed,
                        0xee, 0xef -> {
                    report.add("Application data " + applicationName(marker) + " (" + dataLength + " bytes)");
                    report.dump("Data", reader.array(), dataStart, dataLength);
                }
                case 0xfe -> {
                    report.add("Comment (" + dataLength + " bytes)");
                    report.field("Text", reader.ascii(dataStart, Math.min(dataLength, 200)));
                    report.dump("Data", reader.array(), dataStart, dataLength);
                }
                default -> report.add("Unrecognised segment (" + dataLength + " bytes of data)");
            }

            pos = dataStart + dataLength;
        }

        report.openSection("JPEG summary");
        report.field("Segments visited", segmentCount);
        report.field("End of image marker", sawEoi ? "present" : "MISSING");
        report.field("Bytes consumed", Math.min(pos, reader.size()));
        if (!sawEoi) {
            report.warn("No EOI marker found: the file is truncated or padded with foreign data");
        }
        if (pos < reader.size()) {
            int trailing = reader.size() - pos;
            report.warn(trailing + " bytes appended after the end of the JPEG stream");
            report.dump("Data after EOI", reader.array(), pos, trailing);
        }
    }

    private static void describeFrameHeader(ByteReader reader, FileReport report, int marker, int dataStart,
            int dataLength) {
        report.add("Start of frame: " + frameType(marker) + " (" + (marker < 0xc8 ? "Huffman" : "arithmetic")
                + " coding)");
        if (dataLength < 6) {
            report.warn("Frame header is too short to contain the image geometry");
            return;
        }
        int precision = reader.u8(dataStart);
        int height = reader.be16(dataStart + 1);
        int width = reader.be16(dataStart + 3);
        int components = reader.u8(dataStart + 5);
        report.field("Precision", precision + " bits per sample");
        report.field("Height", height);
        report.field("Width", width);
        report.field("Components", components);
        if (width <= 0 || height <= 0) {
            report.warn("Frame declares an empty image: " + width + "x" + height);
        }
        for (int i = 0; i < components; i++) {
            int offset = dataStart + 6 + i * 3;
            if (offset + 2 >= dataStart + dataLength) {
                report.warn("Component descriptor " + (i + 1) + " is missing");
                break;
            }
            report.add(String.format(Locale.ROOT, "  component %d: id=%d sampling=%dx%d quantisation table=%d",
                    i + 1, reader.u8(offset), reader.u8(offset + 1) >> 4, reader.u8(offset + 1) & 0x0f,
                    reader.u8(offset + 2)));
        }
        int expectedMinimum = 6 + components * 3;
        if (dataLength < expectedMinimum) {
            report.warn("Frame header declares " + components + " components but only contains "
                    + dataLength + " bytes of data (expected at least " + expectedMinimum + ")");
        }
    }

    /**
     * Finds the next real marker after the entropy coded data, honouring {@code FF00} byte stuffing and
     * restart markers. Returns the file size when no marker follows.
     */
    private static int findScanEnd(ByteReader reader, int start) {
        int index = Math.max(0, start);
        while (index + 1 < reader.size()) {
            if (reader.u8(index) == 0xff) {
                int next = reader.u8(index + 1);
                if (next == 0x00 || (next >= 0xd0 && next <= 0xd7)) {
                    index += 2;
                    continue;
                }
                if (next == 0xff) {
                    index++;
                    continue;
                }
                return index;
            }
            index++;
        }
        return reader.size();
    }

    private static String applicationName(int marker) {
        return switch (marker) {
            case 0xe0 -> "APP0 (JFIF)";
            case 0xe1 -> "APP1 (Exif/XMP)";
            case 0xe2 -> "APP2 (ICC profile or FlashPix)";
            case 0xe3 -> "APP3";
            case 0xe4 -> "APP4";
            case 0xe5 -> "APP5";
            case 0xe6 -> "APP6";
            case 0xe7 -> "APP7";
            case 0xe8 -> "APP8";
            case 0xe9 -> "APP9";
            case 0xea -> "APP10";
            case 0xeb -> "APP11";
            case 0xec -> "APP12";
            case 0xed -> "APP13 (Photoshop IRB)";
            case 0xee -> "APP14 (Adobe)";
            default -> "APP15";
        };
    }

    private static String frameType(int marker) {
        return switch (marker) {
            case 0xc0 -> "baseline DCT";
            case 0xc1 -> "extended sequential DCT";
            case 0xc2 -> "progressive DCT";
            case 0xc3 -> "lossless (sequential)";
            case 0xc5 -> "differential sequential DCT";
            case 0xc6 -> "differential progressive DCT";
            case 0xc7 -> "differential lossless";
            case 0xc9 -> "extended sequential DCT (arithmetic)";
            case 0xca -> "progressive DCT (arithmetic)";
            case 0xcb -> "lossless (arithmetic)";
            case 0xcd -> "differential sequential DCT (arithmetic)";
            case 0xce -> "differential progressive DCT (arithmetic)";
            case 0xcf -> "differential lossless (arithmetic)";
            default -> "unknown frame type";
        };
    }

    private static String markerName(int marker) {
        return switch (marker) {
            case 0xd8 -> "SOI (start of image)";
            case 0xd9 -> "EOI (end of image)";
            case 0xda -> "SOS (start of scan)";
            case 0xdb -> "DQT (quantisation table)";
            case 0xc4 -> "DHT (Huffman table)";
            case 0xcc -> "DAC (arithmetic coding conditioning)";
            case 0xdd -> "DRI (restart interval)";
            case 0xdc -> "DNL (number of lines)";
            case 0xde -> "DHP (hierarchical progression)";
            case 0xdf -> "EXP (expand reference components)";
            case 0xfe -> "COM (comment)";
            default -> marker >= 0xc0 && marker <= 0xcf ? "SOF (start of frame)"
                    : marker >= 0xe0 && marker <= 0xef ? "APP" + (marker - 0xe0)
                    : String.format(Locale.ROOT, "unknown marker FF%02X", marker);
        };
    }
}
