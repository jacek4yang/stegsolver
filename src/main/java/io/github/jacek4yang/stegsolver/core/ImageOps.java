package io.github.jacek4yang.stegsolver.core;

/**
 * Bulk pixel operations used when a scan needs a rotated or rescaled copy of an image.
 *
 * <p>Rotations follow the usual screen orientation convention (y grows downwards): a counter
 * clockwise quarter turn maps {@code destination(x', y') = source(width - 1 - y', x')}, so the source
 * right hand edge becomes the top edge of the result.</p>
 */
public final class ImageOps {

    private ImageOps() {
    }

    /** One counter clockwise quarter turn. */
    public static ImageData rotateCounterClockwise(ImageData source) {
        int width = source.width();
        int height = source.height();
        int newWidth = height;
        int newHeight = width;
        int[] in = source.pixels();
        int[] out = new int[in.length];
        for (int newY = 0; newY < newHeight; newY++) {
            int rowStart = newY * newWidth;
            for (int newX = 0; newX < newWidth; newX++) {
                out[rowStart + newX] = in[newX * width + (width - 1 - newY)];
            }
        }
        return ImageData.opaque(newWidth, newHeight, out);
    }

    /** Applies {@code quarterTurns} counter clockwise quarter turns; 0 returns the source itself. */
    public static ImageData rotateCounterClockwise(ImageData source, int quarterTurns) {
        int turns = ((quarterTurns % 4) + 4) % 4;
        ImageData result = source;
        for (int i = 0; i < turns; i++) {
            result = rotateCounterClockwise(result);
        }
        return result;
    }

    /** Nearest neighbour scaling; {@code factor > 1} enlarges, {@code factor < 1} shrinks. */
    public static ImageData scaleNearest(ImageData source, double factor) {
        if (factor <= 0 || Double.isNaN(factor)) {
            throw new IllegalArgumentException("factor must be positive but was " + factor);
        }
        int newWidth = Math.max(1, (int) Math.round(source.width() * factor));
        int newHeight = Math.max(1, (int) Math.round(source.height() * factor));
        if (newWidth == source.width() && newHeight == source.height()) {
            return source;
        }
        int[] in = source.pixels();
        int[] out = new int[newWidth * newHeight];
        int sourceWidth = source.width();
        for (int y = 0; y < newHeight; y++) {
            int sourceY = Math.min(source.height() - 1, (int) (y / factor));
            int rowStart = y * newWidth;
            int sourceRowStart = sourceY * sourceWidth;
            for (int x = 0; x < newWidth; x++) {
                int sourceX = Math.min(sourceWidth - 1, (int) (x / factor));
                out[rowStart + x] = in[sourceRowStart + sourceX];
            }
        }
        return ImageData.opaque(newWidth, newHeight, out);
    }

    /** Crops a region, returning the source itself when the region covers everything. */
    public static ImageData crop(ImageData source, Roi region) {
        Roi clamped = region.clampTo(source.width(), source.height());
        if (clamped.width() == source.width() && clamped.height() == source.height()) {
            return source;
        }
        return source.crop(clamped);
    }
}
