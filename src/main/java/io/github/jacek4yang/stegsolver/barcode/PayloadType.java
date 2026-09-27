package io.github.jacek4yang.stegsolver.barcode;

import java.util.Locale;

/**
 * The kind of payload recovered from a barcode or QR code.
 *
 * <p>Detected from the leading bytes of the exact payload returned by the decoder (the
 * {@code BYTE_SEGMENTS} data, never a re-encoding of the decoded text).</p>
 */
public enum PayloadType {

    EMPTY(Category.EMPTY, "", "empty payload"),
    ZIP(Category.ARCHIVE, "zip", "ZIP archive"),
    SEVEN_ZIP(Category.ARCHIVE, "7z", "7-Zip archive"),
    RAR(Category.ARCHIVE, "rar", "RAR archive"),
    GZIP(Category.ARCHIVE, "gz", "gzip stream"),
    BZIP2(Category.ARCHIVE, "bz2", "bzip2 stream"),
    XZ(Category.ARCHIVE, "xz", "xz stream"),
    TAR(Category.ARCHIVE, "tar", "tar archive"),
    PNG(Category.IMAGE, "png", "PNG image"),
    JPEG(Category.IMAGE, "jpg", "JPEG image"),
    GIF(Category.IMAGE, "gif", "GIF image"),
    BMP(Category.IMAGE, "bmp", "BMP image"),
    WEBP(Category.IMAGE, "webp", "WebP image"),
    PDF(Category.DOCUMENT, "pdf", "PDF document"),
    ELF(Category.EXECUTABLE, "elf", "ELF executable or shared object"),
    PE(Category.EXECUTABLE, "exe", "Windows PE executable"),
    JAVA_CLASS(Category.EXECUTABLE, "class", "Java class file"),
    TEXT_UTF8(Category.TEXT, "txt", "UTF-8 text"),
    TEXT_ASCII(Category.TEXT, "txt", "plain ASCII text"),
    BASE64_TEXT(Category.TEXT, "txt", "Base64 encoded text"),
    BINARY(Category.BINARY, "bin", "unrecognised binary data");

    /** Broad grouping used for badges and warnings in the user interface. */
    public enum Category {
        EMPTY,
        ARCHIVE,
        IMAGE,
        DOCUMENT,
        EXECUTABLE,
        TEXT,
        BINARY
    }

    private final Category category;
    private final String extension;
    private final String description;

    PayloadType(Category category, String extension, String description) {
        this.category = category;
        this.extension = extension;
        this.description = description;
    }

    public Category category() {
        return category;
    }

    /** Suggested file extension, without the dot; empty for an empty payload. */
    public String suggestedExtension() {
        return extension;
    }

    /** Suggested file name suffix including the dot, empty for an empty payload. */
    public String suggestedSuffix() {
        return extension.isEmpty() ? "" : "." + extension;
    }

    public String description() {
        return description;
    }

    public boolean isArchive() {
        return category == Category.ARCHIVE;
    }

    public boolean isExecutable() {
        return category == Category.EXECUTABLE;
    }

    public boolean isImage() {
        return category == Category.IMAGE;
    }

    public boolean isText() {
        return category == Category.TEXT;
    }

    /** True when opening or running the payload would be risky; the UI must not do that automatically. */
    public boolean isPotentiallyUnsafe() {
        return isExecutable() || category == Category.ARCHIVE || category == Category.DOCUMENT;
    }

    /** Short badge text such as {@code ZIP}, used in lists and the status bar. */
    public String badge() {
        return name().replace('_', ' ').toUpperCase(Locale.ROOT);
    }
}
