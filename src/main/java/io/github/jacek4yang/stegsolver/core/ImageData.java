package io.github.jacek4yang.stegsolver.core;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;

/**
 * The pixel model used everywhere in StegSolver: a width, a height and one non-premultiplied ARGB
 * {@code int} per pixel (the same layout as {@link BufferedImage#getRGB(int, int)}, i.e.
 * {@code 0xAARRGGBB}).
 *
 * <p>Working on a plain {@code int[]} keeps the transform, extraction and barcode hot paths free of
 * per pixel object allocation and independent from the toolkit, which is what makes both the
 * JavaFX rendering and the unit tests straightforward.</p>
 *
 * <p>For palette (indexed) sources the original palette indices are retained so that the legacy
 * "random colour map" transform can be applied to the palette instead of to the colours, which is
 * observable when two palette entries share the same colour.</p>
 */
public final class ImageData {

    private final int width;
    private final int height;
    private final int[] argb;
    private final boolean alpha;
    private final int[] indices;
    private final int[] palette;

    private ImageData(int width, int height, int[] argb, boolean alpha, int[] indices, int[] palette) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image size: " + width + "x" + height);
        }
        if ((long) argb.length != (long) width * height) {
            throw new IllegalArgumentException(
                    "Pixel array length " + argb.length + " does not match " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.argb = argb;
        this.alpha = alpha;
        this.indices = indices;
        this.palette = palette;
    }

    /** Wraps an existing ARGB array. The array is not copied; callers own the aliasing. */
    public static ImageData of(int width, int height, int[] argb, boolean hasAlpha) {
        return new ImageData(width, height, argb, hasAlpha, null, null);
    }

    /** Wraps a computed pixel array that has no alpha channel, as produced by the transforms. */
    public static ImageData opaque(int width, int height, int[] argb) {
        return new ImageData(width, height, argb, false, null, null);
    }

    /** Converts a decoded image into the internal representation. */
    public static ImageData fromBufferedImage(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = new int[w * h];
        int[] indices = null;
        int[] palette = null;

        WritableRaster raster = image.getRaster();
        if (image.getColorModel() instanceof IndexColorModel indexColorModel
                && raster.getNumBands() == 1
                && raster.getSampleModel().getDataType() != DataBuffer.TYPE_FLOAT
                && raster.getSampleModel().getDataType() != DataBuffer.TYPE_DOUBLE) {
            // Indexed source: keep the palette indices so the random palette map can be applied
            // to the palette rather than to the resolved colours.
            int[] colors = new int[indexColorModel.getMapSize()];
            indexColorModel.getRGBs(colors);
            palette = colors;
            indices = new int[w * h];
            try {
                raster.getSamples(0, 0, w, h, 0, indices);
            } catch (RuntimeException e) {
                indices = null;
                palette = null;
            }
        }
        image.getRGB(0, 0, w, h, pixels, 0, w);
        return new ImageData(w, h, pixels, image.getColorModel().hasAlpha(), indices, palette);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int pixelCount() {
        return width * height;
    }

    /** Direct access to the backing array; used by transforms to read the source pixels. */
    public int[] pixels() {
        return argb;
    }

    public int pixelAt(int x, int y) {
        return argb[y * width + x];
    }

    public int pixelAt(int index) {
        return argb[index];
    }

    public int argbAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return argb[y * width + x];
    }

    /** True when the source image carries an alpha channel (even if every pixel is opaque). */
    public boolean hasAlpha() {
        return alpha;
    }

    /** True when the source image has a palette, see {@link #indices()}. */
    public boolean isIndexed() {
        return indices != null;
    }

    /** Palette index per pixel, or {@code null} for non indexed images. */
    public int[] indices() {
        return indices;
    }

    /** Palette as ARGB values, or {@code null} for non indexed images. */
    public int[] palette() {
        return palette;
    }

    /** A new image sharing nothing with this one, built from a freshly computed pixel array. */
    public ImageData withPixels(int[] newPixels, boolean newAlpha) {
        return new ImageData(width, height, newPixels, newAlpha, null, null);
    }

    /** A new image with the same pixels but different palette information. */
    public ImageData withPalette(int[] newPixels, int[] newIndices, int[] newPalette, boolean newAlpha) {
        return new ImageData(width, height, newPixels, newAlpha, newIndices, newPalette);
    }

    /** Copies a rectangular region out of this image; the region must be inside the image. */
    public ImageData crop(Roi roi) {
        Roi clamped = roi.clampTo(width, height);
        if (clamped.isEmpty()) {
            throw new IllegalArgumentException("Region " + roi + " does not intersect " + width + "x" + height);
        }
        int[] out = new int[clamped.area()];
        for (int y = 0; y < clamped.height(); y++) {
            System.arraycopy(argb, (clamped.y() + y) * width + clamped.x(),
                    out, y * clamped.width(), clamped.width());
        }
        int[] outIndices = null;
        if (indices != null) {
            outIndices = new int[out.length];
            for (int y = 0; y < clamped.height(); y++) {
                System.arraycopy(indices, (clamped.y() + y) * width + clamped.x(),
                        outIndices, y * clamped.width(), clamped.width());
            }
        }
        return new ImageData(clamped.width(), clamped.height(), out, alpha, outIndices, palette);
    }

    /** A copy of this image whose pixels have been replaced. */
    public ImageData replacePixels(int[] newPixels, boolean newAlpha) {
        return new ImageData(width, height, newPixels, newAlpha, indices, palette);
    }

    /** Materialises the image as a {@link BufferedImage} so that ImageIO can write it. */
    public BufferedImage toBufferedImage() {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    /** An image view of a sub region, useful for previews. */
    public BufferedImage toBufferedImage(Roi roi) {
        return crop(roi).toBufferedImage();
    }

    public long estimatedBytes() {
        long bytes = 4L * argb.length;
        if (indices != null) {
            bytes += 4L * indices.length;
        }
        if (palette != null) {
            bytes += 4L * palette.length;
        }
        return bytes;
    }

    @Override
    public String toString() {
        return "ImageData[" + width + "x" + height + (alpha ? " ARGB" : " RGB")
                + (isIndexed() ? " indexed" : "") + ']';
    }
}
