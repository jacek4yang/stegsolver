package io.github.jacek4yang.stegsolver.parser;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Structural analysis of a PNG file: walks the chunk list with strict bounds checking, verifies the
 * CRC of every chunk and reports the parts that commonly hide data (ancillary chunks, text chunks,
 * palettes, gap bytes and data appended after IEND).
 */
final class PngAnalyzer {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

    private static final Map<String, String> CHUNK_DESCRIPTIONS = Map.ofEntries(
            Map.entry("IHDR", "Image header"),
            Map.entry("PLTE", "Palette"),
            Map.entry("IDAT", "Image data (zlib compressed)"),
            Map.entry("IEND", "Image end"),
            Map.entry("tRNS", "Transparency"),
            Map.entry("gAMA", "Image gamma"),
            Map.entry("cHRM", "Primary chromaticities and white point"),
            Map.entry("sRGB", "Standard RGB colour space"),
            Map.entry("iCCP", "Embedded ICC profile"),
            Map.entry("sBIT", "Significant bits"),
            Map.entry("bKGD", "Background colour"),
            Map.entry("hIST", "Palette histogram"),
            Map.entry("pHYs", "Physical pixel dimensions"),
            Map.entry("sPLT", "Suggested palette"),
            Map.entry("tIME", "Last modification time"),
            Map.entry("tEXt", "Uncompressed text"),
            Map.entry("zTXt", "Compressed text"),
            Map.entry("iTXt", "International text"),
            Map.entry("acTL", "APNG animation control"),
            Map.entry("fcTL", "APNG frame control"),
            Map.entry("fdAT", "APNG frame data"),
            Map.entry("eXIf", "Exif metadata"),
            Map.entry("cICP", "Coding independent code points"));

    private static final List<String> DUMP_CHUNKS = List.of("tEXt", "zTXt", "iTXt", "sPLT", "eXIf", "iCCP");

    private PngAnalyzer() {
    }

    static void analyze(ByteReader reader, FileReport report) {
        report.openSection("PNG structure");
        if (!reader.has(0, 8)) {
            report.warn("File is shorter than the 8 byte PNG signature");
            return;
        }
        boolean signatureOk = true;
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (reader.u8(i) != (SIGNATURE[i] & 0xff)) {
                signatureOk = false;
                break;
            }
        }
        if (signatureOk) {
            report.add("Signature: valid");
        } else {
            report.add("Signature: invalid");
            report.warn("PNG signature bytes are wrong");
        }

        int pos = 8;
        int chunkCount = 0;
        int idatChunks = 0;
        long idatBytes = 0;
        boolean sawIhdr = false;
        boolean sawIend = false;
        boolean sawPlte = false;
        boolean sawIdat = false;
        String lastType = "";

        while (pos < reader.size()) {
            if (sawIend) {
                // Everything after IEND is appended data, not part of the PNG stream.
                break;
            }
            if (!reader.has(pos, 12)) {
                report.warn("Truncated chunk header at offset " + pos + " (" + (reader.size() - pos)
                        + " bytes left, 8 needed)");
                break;
            }
            long lengthLong = reader.be32u(pos);
            String type = reader.fourCcBe(pos + 4);
            int dataStart = pos + 8;
            if (lengthLong > Integer.MAX_VALUE - 12) {
                report.warn("Chunk '" + type + "' at offset " + pos + " declares an impossible length of "
                        + lengthLong + " bytes");
                break;
            }
            int length = (int) lengthLong;
            if (!reader.has(dataStart, length + 4)) {
                report.warn("Chunk '" + type + "' at offset " + pos + " declares " + length
                        + " bytes but only " + Math.max(0, reader.size() - dataStart - 4)
                        + " bytes are available");
                report.add("Chunk '" + type + "': declared " + length + " bytes, truncated file");
                break;
            }

            chunkCount++;
            report.openSection("Chunk #" + chunkCount + " " + type);
            report.field("Offset", "0x" + Integer.toHexString(pos) + " (" + pos + ")");
            report.field("Declared length", length + " bytes");
            describeChunkFlags(type, report);

            long storedCrc = reader.be32u(pos + 8 + length);
            // The CRC covers the chunk type and the chunk data, i.e. from pos+4 up to pos+8+length.
            long computedCrc = computeCrc(reader.array(), pos + 4, 4 + length);
            if (storedCrc != computedCrc) {
                report.warn("CRC mismatch in chunk '" + type + "' at offset " + pos
                        + " (stored 0x" + Long.toHexString(storedCrc) + ", computed 0x"
                        + Long.toHexString(computedCrc) + ") — the chunk was modified after writing");
            }
            report.field("CRC", "0x" + Long.toHexString(storedCrc)
                    + (storedCrc == computedCrc ? " (valid)" : " (computed 0x" + Long.toHexString(computedCrc) + ")"));

            switch (type) {
                case "IHDR" -> {
                    sawIhdr = true;
                    analyzeIhdr(reader, report, dataStart, length);
                }
                case "PLTE" -> {
                    sawPlte = true;
                    analyzePlte(reader, report, dataStart, length, sawIdat);
                }
                case "tRNS" -> {
                    report.add("Transparency table, " + length + " bytes");
                    if (length > 256) {
                        report.warn("tRNS chunk is longer than the PNG specification allows (" + length + " bytes)");
                    }
                    report.dump("Data", reader.array(), dataStart, length);
                }
                case "IDAT" -> {
                    sawIdat = true;
                    idatChunks++;
                    idatBytes += length;
                    report.add("Compressed image data (zlib stream), " + length + " bytes");
                }
                case "IEND" -> {
                    sawIend = true;
                    if (length != 0) {
                        report.warn("IEND chunk declares " + length + " bytes of data, it must be empty");
                        report.dump("Data", reader.array(), dataStart, length);
                    }
                    report.add("End of the PNG stream");
                }
                case "tEXt", "zTXt", "iTXt" -> {
                    report.add("Text chunk");
                    report.field("Keyword", reader.asciiUntilNul(dataStart, length));
                    report.dump("Data", reader.array(), dataStart, length);
                }
                case "iCCP" -> {
                    report.field("Profile name", reader.asciiUntilNul(dataStart, length));
                    report.add("Compressed ICC profile, " + length + " bytes");
                }
                case "tIME" -> {
                    if (length >= 7) {
                        report.field("Timestamp", String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d",
                                reader.be16(dataStart), reader.u8(dataStart + 2), reader.u8(dataStart + 3),
                                reader.u8(dataStart + 4), reader.u8(dataStart + 5), reader.u8(dataStart + 6)));
                    }
                }
                case "pHYs" -> {
                    if (length >= 9) {
                        report.field("Pixels per unit X", reader.be32u(dataStart));
                        report.field("Pixels per unit Y", reader.be32u(dataStart + 4));
                        report.field("Unit", reader.u8(dataStart + 8) == 1 ? "metre" : "unknown");
                    }
                }
                case "bKGD" -> {
                    report.add("Background colour, " + length + " bytes");
                    report.dump("Data", reader.array(), dataStart, length);
                }
                case "acTL" -> {
                    if (length >= 8) {
                        report.field("APNG frames", reader.be32u(dataStart));
                        report.field("Plays", reader.be32u(dataStart + 4));
                    }
                }
                case "fcTL" -> report.add("APNG frame control block");
                case "fdAT" -> report.add("APNG frame data, " + length + " bytes");
                default -> {
                    if (!DUMP_CHUNKS.contains(type)) {
                        report.add("Unrecognised chunk, " + length + " bytes");
                        report.note("Unknown chunks are a classic place to hide a second payload");
                    }
                    report.dump("Data", reader.array(), dataStart, length);
                }
            }

            lastType = type;
            pos = dataStart + length + 4;
        }

        report.openSection("PNG summary");
        report.field("Chunks visited", chunkCount);
        report.field("IDAT chunks", idatChunks);
        report.field("IDAT payload", idatBytes + " bytes of compressed data");
        report.field("Last chunk", lastType.isEmpty() ? "(none)" : lastType);
        if (!sawIhdr) {
            report.warn("No IHDR chunk found — this is not a well formed PNG stream");
        }
        if (!sawIdat) {
            report.warn("No IDAT chunk found — the image has no pixel data");
        }
        if (!sawIend) {
            report.warn("No IEND chunk found: the file may be truncated or data may have been appended");
        } else if (pos < reader.size()) {
            int trailing = reader.size() - pos;
            report.warn(trailing + " bytes appended after the IEND chunk");
            report.dump("Data after IEND", reader.array(), pos, trailing);
        }
    }

    private static void analyzeIhdr(ByteReader reader, FileReport report, int dataStart, int length) {
        if (length != 13) {
            report.warn("IHDR must be 13 bytes long but declares " + length + " bytes");
            if (length < 13) {
                return;
            }
        }
        long width = reader.be32u(dataStart);
        long height = reader.be32u(dataStart + 4);
        int bitDepth = reader.u8(dataStart + 8);
        int colorType = reader.u8(dataStart + 9);
        int compression = reader.u8(dataStart + 10);
        int filter = reader.u8(dataStart + 11);
        int interlace = reader.u8(dataStart + 12);

        report.field("Width", width);
        report.field("Height", height);
        report.field("Bit depth", bitDepth);
        report.field("Colour type", colorType + " (" + colourTypeName(colorType) + ")");
        report.field("Compression", compression + (compression == 0 ? " (deflate)" : " (unknown)"));
        report.field("Filter", filter + (filter == 0 ? " (adaptive)" : " (unknown)"));
        report.field("Interlace", interlace + switch (interlace) {
            case 0 -> " (none)";
            case 1 -> " (Adam7)";
            default -> " (unknown)";
        });

        if (width <= 0 || height <= 0) {
            report.warn("IHDR declares an empty image: " + width + "x" + height);
        }
        if (interlace == 1 && height < 32) {
            report.warn("Adam7 interlaced image is shorter than the interlace pass structure (height < 32)");
        }
        if (compression != 0) {
            report.warn("Unknown compression method " + compression + " in IHDR");
        }
        if (filter != 0) {
            report.warn("Unknown filter method " + filter + " in IHDR");
        }
        if (!validDepthForColorType(bitDepth, colorType)) {
            report.warn("Bit depth " + bitDepth + " is not valid for colour type " + colorType);
        }
        int channels = switch (colorType) {
            case 0 -> 1;
            case 2 -> 3;
            case 3 -> 1;
            case 4 -> 2;
            case 6 -> 4;
            default -> -1;
        };
        if (channels > 0 && width > 0 && height > 0) {
            long bits = width * height * channels * (long) bitDepth;
            long pixels = width * height;
            report.field("Pixels", pixels);
            report.field("Raw bit depth of image data", bits + " bits (" + ((bits + 7) / 8) + " bytes before filtering)");
            if (pixels > 500_000_000L) {
                report.warn("IHDR claims an implausibly large image (" + pixels + " pixels)");
            }
        }
    }

    private static void analyzePlte(ByteReader reader, FileReport report, int dataStart, int length,
            boolean idatAlreadySeen) {
        if (length % 3 != 0) {
            report.warn("PLTE length " + length + " is not a multiple of 3");
        }
        report.field("Palette entries", length / 3);
        if (length / 3 > 256) {
            report.warn("PLTE declares more than 256 entries (" + (length / 3) + ")");
        }
        if (idatAlreadySeen) {
            report.warn("PLTE appears after IDAT, which the specification forbids");
        }
        int entries = Math.min(length / 3, 64);
        for (int i = 0; i < entries; i++) {
            int offset = dataStart + i * 3;
            report.add(String.format(Locale.ROOT, "  [%3d] %02x %02x %02x   %s", i,
                    reader.u8(offset), reader.u8(offset + 1), reader.u8(offset + 2),
                    reader.ascii(offset, 3)));
        }
        if (length / 3 > entries) {
            report.add("  … " + (length / 3 - entries) + " more entries not listed");
        }
        report.note("If the colour type is truecolour, a palette is not required and may hide data");
    }

    private static String colourTypeName(int colorType) {
        return switch (colorType) {
            case 0 -> "grayscale";
            case 2 -> "truecolour";
            case 3 -> "palette";
            case 4 -> "grayscale + alpha";
            case 6 -> "truecolour + alpha";
            default -> "invalid";
        };
    }

    private static boolean validDepthForColorType(int bitDepth, int colorType) {
        return switch (colorType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            default -> false;
        };
    }

    /**
     * Reports the four properties encoded in the case of the chunk type letters. The original
     * StegSolve read these bits from the file signature instead of the chunk name, which always
     * reported "critical, public, safe to copy"; this implementation reads them correctly.
     */
    private static void describeChunkFlags(String type, FileReport report) {
        if (type.length() != 4 || !type.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
            report.warn("Chunk type '" + type + "' is not four ASCII letters");
            return;
        }
        boolean critical = Character.isUpperCase(type.charAt(0));
        boolean publicChunk = Character.isUpperCase(type.charAt(1));
        boolean reservedValid = Character.isUpperCase(type.charAt(2));
        boolean safeToCopy = Character.isLowerCase(type.charAt(3));
        String description = CHUNK_DESCRIPTIONS.get(type);
        report.add("Type: " + type + (description == null ? "" : " — " + description));
        report.add("  " + (critical ? "critical" : "ancillary")
                + ", " + (publicChunk ? "public" : "private")
                + ", " + (safeToCopy ? "safe to copy" : "unsafe to copy"));
        if (!reservedValid) {
            report.warn("Chunk '" + type + "' has the reserved bit set (third letter must be upper case), "
                    + "which the specification forbids");
        }
        if (!publicChunk) {
            report.note("Private chunk: software specific content, worth inspecting");
        }
    }

    private static long computeCrc(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }
}
