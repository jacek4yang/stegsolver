package io.github.jacek4yang.stegsolver.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;

/**
 * Loading and saving of images through ImageIO, with clear diagnostics and atomic writes.
 */
public final class ImageIoUtil {

    private ImageIoUtil() {
    }

    /** Upper bound for files we are willing to pull into memory (both images and analyses). */
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    /**
     * Decodes an image file.
     *
     * @throws IOException if the file cannot be read, is too large or is not a decodable image
     */
    public static ImageData load(Path path) throws IOException {
        if (path == null) {
            throw new IOException("No file given");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a readable file: " + path);
        }
        long size = Files.size(path);
        if (size <= 0) {
            throw new IOException("File is empty: " + path.getFileName());
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException("File is larger than the " + (MAX_FILE_BYTES / (1024 * 1024)) + " MiB limit: "
                    + path.getFileName());
        }
        var image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Unsupported or corrupt image format: " + path.getFileName());
        }
        return ImageData.fromBufferedImage(image);
    }

    /**
     * What happened while saving, so that the user interface can explain any lossy conversion.
     *
     * @param format    the ImageIO format that was used
     * @param flattened {@code true} when the alpha channel had to be composited over white
     */
    public record SaveOutcome(String format, boolean flattened, long bytes) {

        public String message(Path target) {
            String base = "Saved " + target.getFileName() + " as " + format + " (" + bytes + " bytes)";
            return flattened ? base + "; the format cannot store transparency, so transparent pixels were "
                    + "composited over white" : base;
        }
    }

    /**
     * Writes an image, choosing the format from the file extension.
     *
     * <p>The data is first written to a sibling temporary file which is then moved into place, so a
     * failed write never leaves a truncated image behind. If the writer of the requested format cannot
     * represent the image (BMP and JPEG, for example, cannot store an alpha channel) the image is
     * converted to a supported type — compositing transparency over white — instead of failing.</p>
     */
    public static SaveOutcome save(ImageData data, Path target) throws IOException {
        if (data == null) {
            throw new IOException("Nothing to save");
        }
        String format = formatForFileName(target);
        if (!ImageIO.getImageWritersByFormatName(format).hasNext()) {
            throw new IOException("No image writer available for format '" + format + "'. Supported formats: "
                    + String.join(", ", writableExtensions()));
        }
        BufferedImage image = data.toBufferedImage();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, ".stegsolver-", "." + format);
        boolean flattened = false;
        try {
            if (!ImageIO.write(image, format, temp.toFile())) {
                boolean written = false;
                for (BufferedImage candidate : fallbackRepresentations(image)) {
                    if (candidate != null && ImageIO.write(candidate, format, temp.toFile())) {
                        written = true;
                        flattened = data.hasAlpha() && !supportsAlpha(format);
                        break;
                    }
                }
                if (!written) {
                    throw new IOException("The " + format + " writer cannot store this image type ("
                            + image.getType() + ")");
                }
            }
            moveIntoPlace(temp, target);
            return new SaveOutcome(format, flattened, Files.size(target));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** Image representations to try when the writer rejects the natural one, best first. */
    private static List<BufferedImage> fallbackRepresentations(BufferedImage image) {
        List<BufferedImage> candidates = new ArrayList<>(4);
        candidates.add(flattenOverWhite(image));
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            candidates.add(convert(image, BufferedImage.TYPE_3BYTE_BGR));
        }
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            candidates.add(convert(image, BufferedImage.TYPE_INT_RGB));
        }
        candidates.add(convert(image, BufferedImage.TYPE_BYTE_INDEXED));
        return candidates;
    }

    /** Flattens an image over white, which is the conventional treatment for a format without alpha. */
    public static BufferedImage flattenOverWhite(BufferedImage image) {
        BufferedImage flattened = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = flattened.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return flattened;
    }

    private static BufferedImage convert(BufferedImage image, int type) {
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), type);
        java.awt.Graphics2D graphics = converted.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return converted;
    }

    /** True for the formats StegSolver knows cannot store an alpha channel. */
    private static boolean supportsAlpha(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg", "bmp", "wbmp" -> false;
            default -> true;
        };
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Some file systems (and cross-device targets) do not support atomic moves.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The ImageIO format name implied by a file name; defaults to {@code png} without one. */
    public static String formatForFileName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "png";
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg" -> "jpeg";
            case "jpeg", "png", "gif", "bmp", "wbmp", "tif", "tiff" -> extension;
            default -> extension;
        };
    }

    /** Extensions the user can save to, in a stable order, based on the registered writers. */
    public static List<String> writableExtensions() {
        Set<String> names = new TreeSet<>();
        for (String name : ImageIO.getWriterFormatNames()) {
            names.add(switch (name.toLowerCase(Locale.ROOT)) {
                case "jpeg" -> "jpg";
                default -> name.toLowerCase(Locale.ROOT);
            });
        }
        List<String> ordered = new ArrayList<>();
        for (String preferred : List.of("png", "bmp", "jpg", "gif", "tif")) {
            if (names.contains(preferred)) {
                ordered.add(preferred);
            }
        }
        for (String name : names) {
            if (!ordered.contains(name)) {
                ordered.add(name);
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    /** True when ImageIO can write the format implied by the file name. */
    public static boolean canWrite(Path path) {
        String format = formatForFileName(path);
        return ImageIO.getImageWritersByFormatName(format).hasNext();
    }
}
