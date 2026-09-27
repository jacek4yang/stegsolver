package io.github.jacek4yang.stegsolver;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.jacek4yang.stegsolver.core.ImageData;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Image and barcode fixtures shared by the tests.
 *
 * <p>Everything is generated in memory: the repository contains no binary fixtures, so a review can
 * read exactly what a test feeds into the code under test.</p>
 */
public final class TestImages {

    private TestImages() {
    }

    /** A deterministic pseudo random RGB image. */
    public static ImageData randomRgb(int width, int height, long seed) {
        Random random = new Random(seed);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt(0x1000000);
        }
        return ImageData.opaque(width, height, pixels);
    }

    /** A deterministic pseudo random ARGB image with varying alpha. */
    public static ImageData randomArgb(int width, int height, long seed) {
        Random random = new Random(seed);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt() | 0x01000000;
        }
        return ImageData.of(width, height, pixels, true);
    }

    /** An image filled with one colour. */
    public static ImageData solid(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, argb);
        return ImageData.of(width, height, pixels, (argb >>> 24) != 0xff);
    }

    /** An image whose pixel at {@code index} is {@code argb}, everything else black. */
    public static ImageData blackWith(int width, int height, int index, int argb) {
        int[] pixels = new int[width * height];
        pixels[index] = argb;
        return ImageData.opaque(width, height, pixels);
    }

    /** Encodes an image as PNG bytes. */
    public static byte[] pngBytes(ImageData image) {
        return encode(image.toBufferedImage(), "png");
    }

    public static byte[] jpegBytes(BufferedImage image) {
        return encode(image, "jpg");
    }

    public static byte[] gifBytes(BufferedImage image) {
        return encode(image, "gif");
    }

    public static byte[] bmpBytes(BufferedImage image) {
        return encode(image, "bmp");
    }

    public static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Appends bytes to a copy of an array, the classic "extra data after the image" case. */
    public static byte[] withTrailingBytes(byte[] image, byte[] trailing) {
        byte[] result = java.util.Arrays.copyOf(image, image.length + trailing.length);
        System.arraycopy(trailing, 0, result, image.length, trailing.length);
        return result;
    }

    /** A text QR code rendered as an image with a one module quiet zone and no scaling artefacts. */
    public static ImageData qrCode(String text, int moduleSize) {
        return qrCode(text, moduleSize, null);
    }

    /** A QR code whose byte content is the given charset rendering of {@code content}. */
    public static ImageData qrCode(String content, int moduleSize, String characterSet) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        if (characterSet != null) {
            hints.put(EncodeHintType.CHARACTER_SET, characterSet);
        }
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            return renderMatrix(matrix, moduleSize, 4);
        } catch (WriterException e) {
            throw new IllegalStateException("Could not encode QR code", e);
        }
    }

    /** Renders arbitrary payload bytes as a QR code using the ISO-8859-1 byte mode mapping. */
    public static ImageData qrCodeForBytes(byte[] payload, int moduleSize) {
        return qrCode(new String(payload, StandardCharsets.ISO_8859_1), moduleSize, "ISO-8859-1");
    }

    /** Renders a Code 128 barcode, used to exercise the barcodes that need a horizontal orientation. */
    public static ImageData code128(String content, int width, int height) {
        BitMatrix matrix = new com.google.zxing.oned.Code128Writer()
                .encode(content, BarcodeFormat.CODE_128, width, height, java.util.Map.of());
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        java.util.Arrays.fill(pixels, 0xffffffff);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    pixels[y * matrix.getWidth() + x] = 0xff000000;
                }
            }
        }
        return ImageData.opaque(matrix.getWidth(), matrix.getHeight(), pixels);
    }

    /** Renders a BitMatrix with a quiet zone; scale 0 means "use the module size as is". */
    public static ImageData renderMatrix(BitMatrix matrix, int moduleSize, int quietZoneModules) {
        int scale = Math.max(1, moduleSize);
        int quiet = quietZoneModules * scale;
        int width = matrix.getWidth() * scale + 2 * quiet;
        int height = matrix.getHeight() * scale + 2 * quiet;
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, 0xffffffff);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (!matrix.get(x, y)) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    int rowStart = (quiet + y * scale + dy) * width + quiet;
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[rowStart + x * scale + dx] = 0xff000000;
                    }
                }
            }
        }
        return ImageData.opaque(width, height, pixels);
    }

    /** Places several images side by side on a white canvas, used for the multiple symbol tests. */
    public static ImageData sideBySide(ImageData... parts) {
        int width = 0;
        int height = 0;
        for (ImageData part : parts) {
            width += part.width();
            height = Math.max(height, part.height());
        }
        int gap = 12;
        width += gap * Math.max(0, parts.length - 1);
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, 0xffffffff);
        int x = 0;
        for (ImageData part : parts) {
            for (int y = 0; y < part.height(); y++) {
                System.arraycopy(part.pixels(), y * part.width(), pixels, y * width + x, part.width());
            }
            x += part.width() + gap;
        }
        return ImageData.opaque(width, height, pixels);
    }

    /** Inverts every pixel, producing a light-on-dark symbol. */
    public static ImageData invert(ImageData image) {
        int[] pixels = new int[image.pixels().length];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = image.pixels()[i] ^ 0xffffff;
        }
        return ImageData.of(image.width(), image.height(), pixels, image.hasAlpha());
    }

    /** Draws an image onto an existing image in place, returning a new image. */
    public static ImageData overlay(ImageData canvas, ImageData image, int offsetX, int offsetY) {
        int[] pixels = canvas.pixels().clone();
        for (int y = 0; y < image.height(); y++) {
            int targetY = y + offsetY;
            if (targetY < 0 || targetY >= canvas.height()) {
                continue;
            }
            for (int x = 0; x < image.width(); x++) {
                int targetX = x + offsetX;
                if (targetX < 0 || targetX >= canvas.width()) {
                    continue;
                }
                pixels[targetY * canvas.width() + targetX] = image.pixels()[y * image.width() + x];
            }
        }
        return ImageData.of(canvas.width(), canvas.height(), pixels, canvas.hasAlpha());
    }

    /** A white canvas of the given size. */
    public static ImageData whiteCanvas(int width, int height) {
        return solid(width, height, 0xffffffff);
    }

    /** Copies an image into a larger white canvas at the given offset. */
    public static ImageData pasteOnCanvas(ImageData image, int canvasWidth, int canvasHeight, int offsetX,
            int offsetY) {
        int[] pixels = new int[canvasWidth * canvasHeight];
        java.util.Arrays.fill(pixels, 0xffffffff);
        for (int y = 0; y < image.height(); y++) {
            int targetY = y + offsetY;
            if (targetY < 0 || targetY >= canvasHeight) {
                continue;
            }
            for (int x = 0; x < image.width(); x++) {
                int targetX = x + offsetX;
                if (targetX < 0 || targetX >= canvasWidth) {
                    continue;
                }
                pixels[targetY * canvasWidth + targetX] = image.pixels()[y * image.width() + x];
            }
        }
        return ImageData.opaque(canvasWidth, canvasHeight, pixels);
    }
}
