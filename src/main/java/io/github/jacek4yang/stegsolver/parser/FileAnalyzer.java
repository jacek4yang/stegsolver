package io.github.jacek4yang.stegsolver.parser;

import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Entry point of the structural file analysis.
 *
 * <p>The analysis is defensive by design: it works on files that are truncated, have impossible
 * lengths, wrong checksums or appended garbage, and reports what it found instead of failing. An
 * analyser that throws is caught and turned into a warning, so the user always gets the part of the
 * report that could be built.</p>
 */
public final class FileAnalyzer {

    private FileAnalyzer() {
    }

    /** Runs the analysis for a file on disk. */
    public static FileReport analyze(Path path) {
        byte[] data;
        long size;
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return new FileReport(path == null ? "(none)" : path.getFileName().toString(), 0, ImageFormat.UNKNOWN)
                        .withWarning("Not a readable file");
            }
            size = Files.size(path);
            if (size > ImageIoUtil.MAX_FILE_BYTES) {
                return new FileReport(path.getFileName().toString(), size, ImageFormat.UNKNOWN)
                        .withWarning("File is larger than the " + (ImageIoUtil.MAX_FILE_BYTES / (1024 * 1024))
                                + " MiB analysis limit");
            }
            data = Files.readAllBytes(path);
        } catch (IOException | RuntimeException e) {
            return new FileReport(String.valueOf(path), 0, ImageFormat.UNKNOWN)
                    .withWarning("Could not read the file: " + e);
        }
        return analyze(data, path.getFileName().toString());
    }

    /** Runs the analysis for an in-memory file. */
    public static FileReport analyze(byte[] data, String fileName) {
        byte[] bytes = data == null ? new byte[0] : data;
        ImageFormat format = ImageFormat.detect(bytes);
        FileReport report = new FileReport(fileName, bytes.length, format);
        report.field("Size", bytes.length + " bytes (0x" + Integer.toHexString(bytes.length) + ")");
        report.field("Detected format", format.formatName() + " — " + format.description());
        report.field("SHA-256", sha256(bytes));

        ByteReader reader = new ByteReader(bytes);
        report.openSection("Header bytes");
        report.dump("First " + Math.min(bytes.length, 64) + " bytes", bytes, 0, Math.min(bytes.length, 64));

        if (bytes.length < 4) {
            report.warn("File is shorter than a single signature, nothing can be analysed");
            return report;
        }

        try {
            switch (format) {
                case PNG -> PngAnalyzer.analyze(reader, report);
                case JPEG -> JpegAnalyzer.analyze(reader, report);
                case GIF -> GifAnalyzer.analyze(reader, report);
                case BMP -> BmpAnalyzer.analyze(reader, report);
                case UNKNOWN -> {
                    report.openSection("Unknown format");
                    report.warn("The container signature is not PNG, JPEG, GIF or BMP");
                    report.add("Analysis is limited to what the decoded image reveals");
                }
            }
        } catch (RuntimeException e) {
            report.warn("The analyser stopped early because of an unexpected structure: " + e);
        }

        appendDecodedFacts(report, bytes);
        return report;
    }

    /**
     * Adds what the image decoder sees, which is the complement of the structural view: a file can be
     * structurally odd while decoding perfectly, or contain a valid image that ends well before the end
     * of the file.
     */
    private static void appendDecodedFacts(FileReport report, byte[] bytes) {
        report.openSection("Decoded image");
        try (var stream = new java.io.ByteArrayInputStream(bytes)) {
            var image = javax.imageio.ImageIO.read(stream);
            if (image == null) {
                report.warn("The image could not be decoded even though the header was recognised");
                return;
            }
            report.field("Dimensions", image.getWidth() + "x" + image.getHeight());
            report.field("Decoded pixel count", (long) image.getWidth() * image.getHeight());
            report.field("Colour model", image.getColorModel().getClass().getSimpleName()
                    + (image.getColorModel().hasAlpha() ? " (with alpha)" : " (no alpha)"));
            report.field("Sample model", image.getSampleModel().getClass().getSimpleName());
            report.field("Bands", image.getRaster().getNumBands());
            report.field("Raster buffer", image.getRaster().getDataBuffer().getClass().getSimpleName());
        } catch (IOException | RuntimeException e) {
            report.warn("Could not decode the image for cross checking: " + e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "(unavailable)";
        }
    }
}
