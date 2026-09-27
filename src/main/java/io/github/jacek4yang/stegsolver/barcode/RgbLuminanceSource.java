package io.github.jacek4yang.stegsolver.barcode;

import com.google.zxing.LuminanceSource;
import io.github.jacek4yang.stegsolver.core.Channel;

/**
 * A ZXing {@link LuminanceSource} over StegSolver's flat ARGB pixel array.
 *
 * <p>Using our own source instead of a {@code BufferedImage} based one keeps barcode scanning free of
 * AWT conversions, allows scanning a region of the image directly and, importantly, composites
 * semi transparent pixels over white first: a QR code drawn on a transparent background would
 * otherwise be seen as black on black.</p>
 *
 * <p>Cropping, inversion and counter clockwise rotation are supported so that the scanner can try the
 * practical fallbacks (region, polarity, orientation) without copying through a raster.</p>
 */
public final class RgbLuminanceSource extends LuminanceSource {

    private final byte[] luminances;
    private final int dataWidth;
    private final int dataHeight;
    private final int left;
    private final int top;

    /** Wraps a whole image. */
    public RgbLuminanceSource(int[] argb, int width, int height) {
        this(computeLuminances(argb, width, height), width, height, 0, 0, width, height);
    }

    private RgbLuminanceSource(byte[] luminances, int dataWidth, int dataHeight, int left, int top,
            int width, int height) {
        super(width, height);
        if (left + width > dataWidth || top + height > dataHeight) {
            throw new IllegalArgumentException("Crop rectangle does not fit within image data");
        }
        this.luminances = luminances;
        this.dataWidth = dataWidth;
        this.dataHeight = dataHeight;
        this.left = left;
        this.top = top;
    }

    /**
     * Composites every pixel over white and converts it to a luminance using the integer Rec.601
     * approximation used by ZXing ({@code (306 R + 601 G + 117 B) >> 10}).
     */
    static byte[] computeLuminances(int[] argb, int width, int height) {
        if (argb.length < width * height) {
            throw new IllegalArgumentException("Pixel array is too small for " + width + "x" + height);
        }
        byte[] luminances = new byte[width * height];
        for (int i = 0; i < luminances.length; i++) {
            int pixel = argb[i];
            int alpha = (pixel >>> Channel.ALPHA.shift()) & 0xff;
            int red = (pixel >>> Channel.RED.shift()) & 0xff;
            int green = (pixel >>> Channel.GREEN.shift()) & 0xff;
            int blue = pixel & 0xff;
            if (alpha != 255) {
                // Composite over white so that transparent areas are treated as background.
                red = (red * alpha + 255 * (255 - alpha)) / 255;
                green = (green * alpha + 255 * (255 - alpha)) / 255;
                blue = (blue * alpha + 255 * (255 - alpha)) / 255;
            }
            int luminance = (306 * red + 601 * green + 117 * blue) >> 10;
            luminances[i] = (byte) (luminance & 0xff);
        }
        return luminances;
    }

    @Override
    public byte[] getRow(int y, byte[] row) {
        if (y < 0 || y >= getHeight()) {
            throw new IllegalArgumentException("Row " + y + " is outside 0.." + (getHeight() - 1));
        }
        int width = getWidth();
        if (row == null || row.length < width) {
            row = new byte[width];
        }
        int offset = (y + top) * dataWidth + left;
        System.arraycopy(luminances, offset, row, 0, width);
        return row;
    }

    @Override
    public byte[] getMatrix() {
        int width = getWidth();
        int height = getHeight();
        if (width == dataWidth && height == dataHeight) {
            return luminances.clone();
        }
        byte[] matrix = new byte[width * height];
        for (int y = 0; y < height; y++) {
            System.arraycopy(luminances, (y + top) * dataWidth + left, matrix, y * width, width);
        }
        return matrix;
    }

    @Override
    public boolean isCropSupported() {
        return true;
    }

    @Override
    public LuminanceSource crop(int cropLeft, int cropTop, int cropWidth, int cropHeight) {
        return new RgbLuminanceSource(luminances, dataWidth, dataHeight, left + cropLeft, top + cropTop,
                cropWidth, cropHeight);
    }

    @Override
    public boolean isRotateSupported() {
        return true;
    }

    /**
     * Rotates the visible region a quarter turn counter clockwise:
     * {@code rotated(x', y') = source(width - 1 - y', x')}.
     */
    @Override
    public LuminanceSource rotateCounterClockwise() {
        int width = getWidth();
        int height = getHeight();
        int newWidth = height;
        int newHeight = width;
        byte[] rotated = new byte[newWidth * newHeight];
        for (int newY = 0; newY < newHeight; newY++) {
            int rowStart = newY * newWidth;
            for (int newX = 0; newX < newWidth; newX++) {
                rotated[rowStart + newX] = luminances[(newX + top) * dataWidth + (width - 1 - newY) + left];
            }
        }
        return new RgbLuminanceSource(rotated, newWidth, newHeight, 0, 0, newWidth, newHeight);
    }

    @Override
    public LuminanceSource invert() {
        byte[] inverted = getMatrix();
        for (int i = 0; i < inverted.length; i++) {
            inverted[i] = (byte) (255 - (inverted[i] & 0xff));
        }
        return new RgbLuminanceSource(inverted, getWidth(), getHeight(), 0, 0, getWidth(), getHeight());
    }
}
