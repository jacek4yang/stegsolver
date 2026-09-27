package io.github.jacek4yang.stegsolver.parser;

import java.util.Locale;

/**
 * Structural analysis of a GIF file: header, logical screen descriptor, global and local colour
 * tables, every extension block and every image descriptor, plus the trailing bytes after the
 * trailer.
 *
 * <p>Colour tables and comments are dumped because a palette with unused or duplicated entries, or a
 * comment block, are among the most common places for hidden data in a GIF.</p>
 */
final class GifAnalyzer {

    private static final int MAX_PALETTE_ENTRIES_LISTED = 64;

    private GifAnalyzer() {
    }

    static void analyze(ByteReader reader, FileReport report) {
        report.openSection("GIF structure");
        if (!reader.has(0, 13)) {
            report.warn("File is too short to contain a GIF logical screen descriptor");
            return;
        }
        report.field("Version", reader.ascii(0, 6));
        int width = reader.le16(6);
        int height = reader.le16(8);
        int flags = reader.u8(10);
        report.field("Logical screen", width + "x" + height);
        report.field("Flags", "0x" + Integer.toHexString(flags) + " (" + String.format(Locale.ROOT, "%8s",
                Integer.toBinaryString(flags)).replace(' ', '0') + ")");
        report.field("Global colour table", (flags & 0x80) != 0 ? "present" : "absent");
        int gctSize = 0;
        if ((flags & 0x80) != 0) {
            gctSize = 1 << ((flags & 0x07) + 1);
            report.field("Global colour table entries", gctSize);
            if ((flags & 0x10) != 0) {
                report.note("Global colour table is marked as sorted");
            }
        }
        report.field("Colour resolution", ((flags >> 4) & 0x07) + 1 + " bits per primary");
        report.field("Background colour index", reader.u8(11));
        report.field("Pixel aspect ratio", reader.u8(12));

        int pos = 13;
        if (gctSize > 0) {
            if (!reader.has(pos, gctSize * 3)) {
                report.warn("Global colour table declares " + gctSize + " entries but the file ends first");
                return;
            }
            dumpPalette(reader, report, "Global colour table", pos, gctSize);
            pos += gctSize * 3;
        }

        int blockCount = 0;
        int frameCount = 0;
        int commentCount = 0;
        boolean sawTrailer = false;
        while (pos < reader.size()) {
            int introducer = reader.u8(pos);
            if (introducer == 0x3b) {
                report.openSection("Trailer");
                report.field("Offset", "0x" + Integer.toHexString(pos) + " (" + pos + ")");
                pos++;
                sawTrailer = true;
                break;
            }
            if (introducer == 0x2c) {
                if (!reader.has(pos, 10)) {
                    report.warn("Truncated image descriptor at offset " + pos);
                    break;
                }
                frameCount++;
                blockCount++;
                report.openSection("Image descriptor #" + frameCount);
                report.field("Offset", "0x" + Integer.toHexString(pos) + " (" + pos + ")");
                int left = reader.le16(pos + 1);
                int top = reader.le16(pos + 3);
                int frameWidth = reader.le16(pos + 5);
                int frameHeight = reader.le16(pos + 7);
                int frameFlags = reader.u8(pos + 9);
                report.field("Position", left + "," + top);
                report.field("Size", frameWidth + "x" + frameHeight);
                if (left + frameWidth > width || top + frameHeight > height) {
                    report.warn("Frame " + frameCount + " extends past the logical screen bounds");
                }
                report.field("Flags", "0x" + Integer.toHexString(frameFlags));
                if ((frameFlags & 0x40) != 0) {
                    report.note("Interlaced frame");
                }
                if ((frameFlags & 0x18) != 0) {
                    report.warn("Frame " + frameCount + " has reserved flag bits set");
                }
                pos += 10;
                if ((frameFlags & 0x80) != 0) {
                    int lctSize = 1 << ((frameFlags & 0x07) + 1);
                    if (!reader.has(pos, lctSize * 3)) {
                        report.warn("Local colour table of frame " + frameCount + " is truncated");
                        break;
                    }
                    dumpPalette(reader, report, "Local colour table", pos, lctSize);
                    pos += lctSize * 3;
                }
                if (!reader.has(pos, 1)) {
                    report.warn("Frame " + frameCount + " has no LZW minimum code size");
                    break;
                }
                int lzwSize = reader.u8(pos);
                report.field("LZW minimum code size", lzwSize);
                pos++;
                int dataStart = pos;
                pos = skipSubBlocks(reader, pos);
                if (pos < 0) {
                    report.warn("Frame " + frameCount + " image data runs past the end of the file");
                    break;
                }
                report.field("Compressed image data", (pos - dataStart) + " bytes in sub blocks");
                continue;
            }
            if (introducer == 0x21) {
                if (!reader.has(pos + 1, 1)) {
                    report.warn("Truncated extension introducer at offset " + pos);
                    break;
                }
                int label = reader.u8(pos + 1);
                blockCount++;
                switch (label) {
                    case 0xf9 -> {
                        report.openSection("Graphic control extension");
                        if (!reader.has(pos + 6, 2)) {
                            report.warn("Truncated graphic control extension at offset " + pos);
                            pos = reader.size();
                            break;
                        }
                        int blockSize = reader.u8(pos + 2);
                        int gceFlags = reader.u8(pos + 3);
                        report.field("Block size", blockSize + (blockSize == 4 ? " (correct)" : " (should be 4)"));
                        report.field("Flags", "0x" + Integer.toHexString(gceFlags));
                        if ((gceFlags & 0xe0) != 0) {
                            report.warn("Graphic control extension has reserved flag bits set");
                        }
                        report.field("Disposal method", (gceFlags >> 2) & 0x07);
                        report.field("Transparent colour", (gceFlags & 0x01) != 0
                                ? String.valueOf(reader.u8(pos + 6)) : "none");
                        report.field("Delay", reader.le16(pos + 4) + " (1/100 s)");
                        pos = skipSubBlocks(reader, pos + 2 + blockSize);
                        if (pos < 0) {
                            pos = reader.size();
                        }
                    }
                    case 0xfe -> {
                        commentCount++;
                        report.openSection("Comment extension #" + commentCount);
                        int textStart = pos + 2;
                        int end = skipSubBlocks(reader, textStart);
                        if (end < 0) {
                            report.warn("Comment extension runs past the end of the file");
                            end = reader.size();
                        }
                        int length = Math.max(0, end - textStart);
                        report.field("Length", length + " bytes");
                        report.field("Text", reader.ascii(textStart, Math.min(length, 300)));
                        report.dump("Data", reader.array(), textStart, length);
                        pos = end;
                    }
                    case 0x01 -> {
                        report.openSection("Plain text extension");
                        if (!reader.has(pos + 2, 13)) {
                            report.warn("Truncated plain text extension at offset " + pos);
                            pos = reader.size();
                            break;
                        }
                        report.field("Block size", reader.u8(pos + 2) + " (should be 12)");
                        report.field("Grid", reader.le16(pos + 3) + "," + reader.le16(pos + 5)
                                + " " + reader.le16(pos + 7) + "x" + reader.le16(pos + 9));
                        report.field("Cell size", reader.u8(pos + 11) + "x" + reader.u8(pos + 12));
                        report.field("Foreground colour", reader.u8(pos + 13));
                        report.field("Background colour", reader.u8(pos + 14));
                        int textStart = pos + 15;
                        int end = skipSubBlocks(reader, textStart);
                        if (end < 0) {
                            report.warn("Plain text data runs past the end of the file");
                            end = reader.size();
                        }
                        report.dump("Text data", reader.array(), textStart, Math.max(0, end - textStart));
                        pos = end;
                    }
                    case 0xff -> {
                        report.openSection("Application extension");
                        if (!reader.has(pos + 2, 12)) {
                            report.warn("Truncated application extension at offset " + pos);
                            pos = reader.size();
                            break;
                        }
                        report.field("Block size", reader.u8(pos + 2) + " (should be 11)");
                        report.field("Identifier", reader.ascii(pos + 3, 8));
                        report.field("Authentication code", reader.ascii(pos + 11, 3));
                        int dataStart = pos + 14;
                        int end = skipSubBlocks(reader, dataStart);
                        if (end < 0) {
                            report.warn("Application data runs past the end of the file");
                            end = reader.size();
                        }
                        report.dump("Application data", reader.array(), dataStart, Math.max(0, end - dataStart));
                        pos = end;
                    }
                    default -> {
                        report.openSection("Extension 0x" + Integer.toHexString(label));
                        report.warn("Unrecognised extension label 0x" + Integer.toHexString(label));
                        int start = pos + 2;
                        pos = skipSubBlocks(reader, start);
                        if (pos < 0) {
                            pos = reader.size();
                        }
                        report.dump("Data", reader.array(), start, Math.max(0, pos - start));
                    }
                }
                continue;
            }

            report.openSection("Unknown block");
            report.warn("Unrecognised block introducer 0x" + Integer.toHexString(introducer)
                    + " at offset " + pos);
            break;
        }

        report.openSection("GIF summary");
        report.field("Blocks visited", blockCount);
        report.field("Frames", frameCount);
        report.field("Comment extensions", commentCount);
        report.field("Trailer", sawTrailer ? "present" : "MISSING");
        if (!sawTrailer) {
            report.warn("No trailer block found: the GIF is truncated");
        } else if (pos < reader.size()) {
            int trailing = reader.size() - pos;
            report.warn(trailing + " bytes appended after the GIF trailer");
            report.dump("Data after trailer", reader.array(), pos, trailing);
        }
    }

    /** Walks a chain of sub blocks; returns the offset after the terminating zero length block. */
    private static int skipSubBlocks(ByteReader reader, int start) {
        int pos = Math.max(0, start);
        while (true) {
            if (!reader.has(pos, 1)) {
                return -1;
            }
            int size = reader.u8(pos);
            if (size == 0) {
                return pos + 1;
            }
            pos += size + 1;
            if (pos > reader.size()) {
                return -1;
            }
        }
    }

    private static void dumpPalette(ByteReader reader, FileReport report, String label, int offset, int entries) {
        report.add(label + ": " + entries + " entries (RGB triples)");
        int listed = Math.min(entries, MAX_PALETTE_ENTRIES_LISTED);
        int duplicates = 0;
        for (int i = 0; i < listed; i++) {
            int at = offset + i * 3;
            report.add(String.format(Locale.ROOT, "  [%3d] %02x %02x %02x   %s", i,
                    reader.u8(at), reader.u8(at + 1), reader.u8(at + 2), reader.ascii(at, 3)));
        }
        for (int i = 0; i < entries - 1; i++) {
            int a = offset + i * 3;
            int b = offset + (i + 1) * 3;
            if (reader.u8(a) == reader.u8(b) && reader.u8(a + 1) == reader.u8(b + 1)
                    && reader.u8(a + 2) == reader.u8(b + 2)) {
                duplicates++;
            }
        }
        if (entries > listed) {
            report.add("  … " + (entries - listed) + " more entries not listed");
        }
        if (duplicates > 0) {
            report.note(duplicates + " adjacent palette entries are identical, which can indicate that the "
                    + "palette was used to carry data");
        }
    }
}
