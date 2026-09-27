package io.github.jacek4yang.stegsolver.barcode;

import io.github.jacek4yang.stegsolver.core.Roi;

/**
 * Maps coordinates from a rotated or scaled scan bitmap back to the original image.
 *
 * <p>ZXing reports symbol positions in the coordinate system of the bitmap it was given. When the
 * scanner tries rotated copies ({@link io.github.jacek4yang.stegsolver.core.ImageOps#rotateCounterClockwise})
 * or rescaled copies, the overlay would draw the symbol in the wrong place unless the points are
 * mapped back. Keeping that arithmetic here makes it unit testable without a toolkit.</p>
 */
public final class RotationMapper {

    /** A point in image coordinates. */
    public record Point(double x, double y) {
    }

    private RotationMapper() {
    }

    public static int rotatedWidth(int quarterTurns, int width, int height) {
        return isOdd(quarterTurns) ? height : width;
    }

    public static int rotatedHeight(int quarterTurns, int width, int height) {
        return isOdd(quarterTurns) ? width : height;
    }

    private static boolean isOdd(int quarterTurns) {
        return Math.floorMod(quarterTurns, 4) % 2 == 1;
    }

    /**
     * Maps a point from a bitmap that was rotated counter clockwise by {@code quarterTurns} back into
     * the original image of the given size.
     */
    public static Point toOriginal(double x, double y, int quarterTurns, int originalWidth,
            int originalHeight) {
        int turns = ((quarterTurns % 4) + 4) % 4;
        if (turns == 0) {
            return new Point(x, y);
        }
        // Dimensions of the rotated bitmap, then undo each turn from the outside in.
        int width = rotatedWidth(turns, originalWidth, originalHeight);
        int height = rotatedHeight(turns, originalWidth, originalHeight);
        double px = x;
        double py = y;
        for (int i = 0; i < turns; i++) {
            double sourceX = height - 1 - py;
            double sourceY = px;
            px = sourceX;
            py = sourceY;
            int swap = width;
            width = height;
            height = swap;
        }
        return new Point(px, py);
    }

    /**
     * Maps a rectangle from a rotated bitmap back into the original image, returning the bounding box
     * of the mapped corner pixels clamped to the image.
     */
    public static Roi toOriginalRect(Roi rotatedRect, int quarterTurns, int originalWidth,
            int originalHeight) {
        if (rotatedRect == null || rotatedRect.isEmpty()) {
            return Roi.EMPTY;
        }
        // The corners of a rectangle are its outermost *pixels*, so the last addressable coordinate is
        // maxX-1 / maxY-1; using the exclusive bounds would inflate the result by one pixel.
        double[] xs = {rotatedRect.x(), rotatedRect.maxX() - 1.0};
        double[] ys = {rotatedRect.y(), rotatedRect.maxY() - 1.0};
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double cornerX : xs) {
            for (double cornerY : ys) {
                Point mapped = toOriginal(cornerX, cornerY, quarterTurns, originalWidth, originalHeight);
                minX = Math.min(minX, mapped.x());
                minY = Math.min(minY, mapped.y());
                maxX = Math.max(maxX, mapped.x());
                maxY = Math.max(maxY, mapped.y());
            }
        }
        int x0 = (int) Math.floor(minX);
        int y0 = (int) Math.floor(minY);
        int x1 = (int) Math.ceil(maxX);
        int y1 = (int) Math.ceil(maxY);
        return Roi.clamp(x0, y0, x1 - x0 + 1, y1 - y0 + 1, originalWidth, originalHeight);
    }

    /** Undoes a uniform scaling applied to a scan bitmap. */
    public static Point unscale(double x, double y, double factor) {
        if (factor == 0 || Double.isNaN(factor)) {
            return new Point(x, y);
        }
        return new Point(x / factor, y / factor);
    }
}
