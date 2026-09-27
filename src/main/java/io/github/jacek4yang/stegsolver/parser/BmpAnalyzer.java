package io.github.jacek4yang.stegsolver.parser;

import java.util.Locale;

/**
 * Structural analysis of a Windows bitmap: file header, DIB header (including the OS/2 variants),
 * colour table, the gap between the header and the pixel data and the bytes appended after the last
 * row.
 *
 * <p>Appended bytes are the classic BMP hiding place when the height in the header is smaller than
 * the real image, so they are reported prominently, and the row padding implied by the width and the
 * bit depth is computed to show where hidden bytes could sit between rows.</p>
 */
final class BmpAnalyzer {

    private BmpAnalyzer() {
    }

    static void analyze(ByteReader reader, FileReport report) {
        report.openSection("BMP structure");
        if (!reader.has(0, 14)) {
            report.warn("File is too short to contain a BITMAPFILEHEADER");
            return;
        }
        long declaredFileSize = reader.le32u(2);
        int offBits = reader.le32(10);
        report.field("Signature", reader.ascii(0, 2));
        report.field("Declared file size", declaredFileSize + " bytes (0x" + Long.toHexString(declaredFileSize) + ")");
        if (declaredFileSize != reader.size()) {
            report.note("Declared size differs from the real size ("
                    + (reader.size() - declaredFileSize) + " byte difference)");
        }
        report.field("Reserved fields", reader.le16(6) + ", " + reader.le16(8));
        report.field("Pixel data offset", offBits + " (0x" + Integer.toHexString(offBits) + ")");
        if (offBits < 14) {
            report.warn("Pixel data offset " + offBits + " points inside the file header");
            return;
        }

        if (!reader.has(14, 4)) {
            report.warn("File is too short to contain a DIB header");
            return;
        }
        int dibSize = reader.le32(14);
        report.field("DIB header size", dibSize + " bytes (" + dibHeaderName(dibSize) + ")");
        if (dibSize < 12) {
            report.warn("DIB header size " + dibSize + " is smaller than the OS/2 v1 header (12 bytes)");
            return;
        }
        if (!reader.has(14, dibSize)) {
            report.warn("DIB header declares " + dibSize + " bytes but the file ends first");
            return;
        }

        boolean coreHeader = dibSize == 12;
        long width;
        long height;
        int planes;
        int bitsPerPixel;
        long compression = 0;
        long imageSize = 0;
        long coloursUsed = 0;
        if (coreHeader) {
            width = reader.le16(18);
            height = reader.le16(20);
            planes = reader.le16(22);
            bitsPerPixel = reader.le16(24);
            report.add("OS/2 v1 header: palette entries are 3 bytes, no compression field");
        } else {
            width = reader.le32(18);
            height = reader.le32(22);
            planes = reader.le16(26);
            bitsPerPixel = reader.le16(28);
            compression = reader.le32u(30);
            imageSize = reader.le32u(34);
            report.field("Pixels per metre", reader.le32(38) + " x " + reader.le32(42));
            coloursUsed = reader.le32u(46);
            report.field("Colours used", coloursUsed + (coloursUsed == 0 ? " (all)" : ""));
            report.field("Important colours", reader.le32(50));
        }
        boolean topDown = height < 0;
        long absoluteHeight = Math.abs(height);
        report.field("Width", width + " pixels");
        report.field("Height", absoluteHeight + " pixels" + (topDown ? " (top-down)" : " (bottom-up)"));
        report.field("Planes", planes + (planes == 1 ? " (correct)" : " (should be 1)"));
        report.field("Bits per pixel", bitsPerPixel);
        if (!coreHeader) {
            report.field("Compression", compression + " (" + compressionName(compression) + ")");
            report.field("Declared image size", imageSize + " bytes"
                    + (imageSize == 0 ? " (allowed for uncompressed images)" : ""));
        }
        if (planes != 1) {
            report.warn("Header declares " + planes + " colour planes, the specification allows only 1");
        }
        if (width <= 0 || absoluteHeight <= 0) {
            report.warn("Header declares an empty image: " + width + "x" + absoluteHeight);
            return;
        }
        if (!validBitsPerPixel(bitsPerPixel)) {
            report.warn("Unusual bit depth " + bitsPerPixel);
        }

        int colourTableEntrySize = coreHeader ? 3 : 4;
        long paletteEntries = 0;
        if (bitsPerPixel <= 8) {
            paletteEntries = coloursUsed != 0 ? coloursUsed : (1L << bitsPerPixel);
        }
        int colourTableStart = 14 + dibSize;
        long colourTableEnd = colourTableStart + paletteEntries * colourTableEntrySize;
        report.field("Colour table", paletteEntries + " entries starting at 0x"
                + Integer.toHexString(colourTableStart));
        if (colourTableStart > 0x36) {
            report.note("Colour table starts at 0x" + Integer.toHexString(colourTableStart)
                    + " instead of the usual 0x36: the header is bigger than the standard BITMAPINFOHEADER");
            report.dump("Bytes between the standard header end and the colour table", reader.array(), 0x36,
                    (int) Math.max(0, Math.min(colourTableStart, reader.size()) - 0x36));
        }
        if (colourTableEnd > reader.size()) {
            report.warn("Colour table ends past the end of the file (" + colourTableEnd + " > " + reader.size() + ")");
        } else if (paletteEntries > 0) {
            dumpPalette(reader, report, colourTableStart, (int) paletteEntries, colourTableEntrySize);
        }
        if (colourTableEnd != offBits) {
            if (colourTableEnd < offBits) {
                int gap = (int) (offBits - colourTableEnd);
                report.note(gap + " bytes between the colour table and the pixel data");
                report.dump("Gap bytes", reader.array(), (int) colourTableEnd, gap);
            } else {
                report.warn("Pixel data offset " + offBits + " is inside the colour table ("
                        + colourTableEnd + ")");
            }
        }

        long rowStride = (((width * bitsPerPixel) + 31) / 32) * 4;
        long expectedPixels = rowStride * absoluteHeight;
        report.field("Row stride", rowStride + " bytes (rows are padded to 4 byte boundaries)");
        report.field("Expected pixel data", expectedPixels + " bytes");
        if (imageSize != 0 && imageSize != expectedPixels) {
            report.note("Header declares " + imageSize + " bytes of pixel data but the geometry implies "
                    + expectedPixels);
        }
        long pixelDataEnd = offBits + expectedPixels;
        if (pixelDataEnd > reader.size()) {
            report.warn("Pixel data is truncated: needs " + expectedPixels + " bytes from offset " + offBits
                    + " but only " + Math.max(0, reader.size() - offBits) + " are present");
        } else if (pixelDataEnd < reader.size()) {
            int trailing = (int) (reader.size() - pixelDataEnd);
            report.warn(trailing + " bytes appended after the pixel data (offset " + pixelDataEnd + ")");
            report.note("Appended bytes often mean the height in the header is smaller than the real image: "
                    + "try increasing the height to reveal a hidden image");
            report.dump("Appended data", reader.array(), (int) pixelDataEnd, trailing);
        }
        if (compression != 0) {
            report.note("Compression " + compressionName(compression)
                    + " is used, so the pixel data layout could not be verified");
        }
    }

    private static void dumpPalette(ByteReader reader, FileReport report, int start, int entries, int entrySize) {
        int listed = Math.min(entries, 64);
        report.openSection("Colour table");
        for (int i = 0; i < listed; i++) {
            int at = start + i * entrySize;
            if (!reader.has(at, entrySize)) {
                break;
            }
            if (entrySize == 4) {
                report.add(String.format(Locale.ROOT, "  [%3d] %02x %02x %02x alpha=%02x (stored blue, green, red, alpha)",
                        i, reader.u8(at + 2), reader.u8(at + 1), reader.u8(at), reader.u8(at + 3)));
            } else {
                report.add(String.format(Locale.ROOT, "  [%3d] %02x %02x %02x (stored blue, green, red)",
                        i, reader.u8(at + 2), reader.u8(at + 1), reader.u8(at)));
            }
        }
        if (entries > listed) {
            report.add("  … " + (entries - listed) + " more entries not listed");
        }
        report.note("The fourth byte of a 4 byte entry should be zero; non-zero values can hide data");
    }

    private static boolean validBitsPerPixel(int bitsPerPixel) {
        return bitsPerPixel == 1 || bitsPerPixel == 4 || bitsPerPixel == 8 || bitsPerPixel == 16
                || bitsPerPixel == 24 || bitsPerPixel == 32 || bitsPerPixel == 64;
    }

    private static String dibHeaderName(int size) {
        return switch (size) {
            case 12 -> "OS/2 BITMAPCOREHEADER";
            case 40 -> "BITMAPINFOHEADER";
            case 52 -> "BITMAPV2INFOHEADER";
            case 56 -> "BITMAPV3INFOHEADER";
            case 64 -> "OS/2 v2 BITMAPCOREHEADER2";
            case 108 -> "BITMAPV4HEADER";
            case 124 -> "BITMAPV5HEADER";
            default -> "unknown header variant";
        };
    }

    private static String compressionName(long compression) {
        return switch ((int) compression) {
            case 0 -> "no compression";
            case 1 -> "RLE8";
            case 2 -> "RLE4";
            case 3 -> "bit fields";
            case 4 -> "JPEG";
            case 5 -> "PNG";
            case 6 -> "alpha bit fields";
            default -> {
                String fourCc = ((char) (compression & 0xff)) + "" + ((char) ((compression >> 8) & 0xff))
                        + ((char) ((compression >> 16) & 0xff)) + ((char) ((compression >> 24) & 0xff));
                yield "unknown (0x" + Long.toHexString(compression) + " fourCC '" + fourCc + "')";
            }
        };
    }
}
