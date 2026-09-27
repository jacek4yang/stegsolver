package io.github.jacek4yang.stegsolver.core;

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
import javax.imageio.ImageWriter;

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
     * Writes an image, choosing the format from the file extension.
     *
     * <p>The data is first written to a sibling temporary file which is then moved into place, so a
     * failed write never leaves a truncated image behind.</p>
     */
    public static void save(ImageData data, Path target) throws IOException {
        if (data == null) {
            throw new IOException("Nothing to save");
        }
        String format = formatForFileName(target);
        var image = data.toBufferedImage();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, ".stegsolver-", "." + format);
        try {
            boolean written = ImageIO.write(image, format, temp.toFile());
            if (!written) {
                throw new IOException("No image writer available for format '" + format + "'");
            }
            moveIntoPlace(temp, target);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
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
        for (var iterator = ImageIO.getImageWritersBySuffix(""); iterator.hasNext(); ) {
            ImageWriter writer = iterator.next();
            for (String suffix : writer.getOriginatingProvider().getFileSuffixes()) {
                names.add(switch (suffix.toLowerCase(Locale.ROOT)) {
                    case "jpeg" -> "jpg";
                    default -> suffix.toLowerCase(Locale.ROOT);
                });
            }
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
