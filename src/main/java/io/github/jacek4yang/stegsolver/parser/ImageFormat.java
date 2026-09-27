package io.github.jacek4yang.stegsolver.parser;

import java.util.Locale;

/**
 * A structural image file format recognised by StegSolver's file analysis.
 */
public enum ImageFormat {

    PNG("PNG", "Portable Network Graphics"),
    JPEG("JPEG", "JPEG / JFIF"),
    GIF("GIF", "Graphics Interchange Format"),
    BMP("BMP", "Windows bitmap"),
    UNKNOWN("unknown", "unrecognised format");

    private final String name;
    private final String description;

    ImageFormat(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String formatName() {
        return name;
    }

    public String description() {
        return description;
    }

    /** Detects the format from the container signature; never throws. */
    public static ImageFormat detect(byte[] data) {
        if (data == null) {
            return UNKNOWN;
        }
        ByteReader reader = new ByteReader(data);
        if (data.length >= 8
                && reader.u8(0) == 0x89 && reader.u8(1) == 'P' && reader.u8(2) == 'N' && reader.u8(3) == 'G'
                && reader.u8(4) == 0x0d && reader.u8(5) == 0x0a && reader.u8(6) == 0x1a && reader.u8(7) == 0x0a) {
            return PNG;
        }
        if (data.length >= 3 && reader.u8(0) == 0xff && reader.u8(1) == 0xd8 && reader.u8(2) == 0xff) {
            return JPEG;
        }
        if (data.length >= 6 && reader.u8(0) == 'G' && reader.u8(1) == 'I' && reader.u8(2) == 'F'
                && reader.u8(3) == '8' && (reader.u8(4) == '7' || reader.u8(4) == '9') && reader.u8(5) == 'a') {
            return GIF;
        }
        if (data.length >= 2 && reader.u8(0) == 'B' && reader.u8(1) == 'M') {
            return BMP;
        }
        return UNKNOWN;
    }

    /** Name of the file extension typically used by this format, without the dot. */
    public String defaultExtension() {
        return switch (this) {
            case JPEG -> "jpg";
            case PNG, GIF, BMP -> name.toLowerCase(Locale.ROOT);
            case UNKNOWN -> "bin";
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
