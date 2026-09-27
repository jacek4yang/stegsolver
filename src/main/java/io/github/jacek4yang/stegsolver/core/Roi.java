package io.github.jacek4yang.stegsolver.core;

import java.util.Objects;

/**
 * An immutable description of a rectangular region of an image, in image (pixel) coordinates.
 *
 * <p>Instances are always expected to be clamped to the bounds of the image they refer to; use
 * {@link #clampTo(int, int)} or {@link #intersect(Roi)} which guarantee that.</p>
 */
public record Roi(int x, int y, int width, int height) {

    public static final Roi EMPTY = new Roi(0, 0, 0, 0);

    public Roi {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Negative size: " + width + "x" + height);
        }
    }

    /** The whole image as a region. */
    public static Roi whole(int imageWidth, int imageHeight) {
        return new Roi(0, 0, imageWidth, imageHeight);
    }

    /**
     * Builds a region from two corner points, in any order, which is what a mouse drag produces.
     */
    public static Roi fromCorners(int x0, int y0, int x1, int y1) {
        int minX = Math.min(x0, x1);
        int minY = Math.min(y0, y1);
        int w = Math.abs(x1 - x0);
        int h = Math.abs(y1 - y0);
        return new Roi(minX, minY, w, h);
    }

    /** Clamps a rectangle to {@code [0, maxWidth] x [0, maxHeight]}, returning an empty region if disjoint. */
    public static Roi clamp(int x, int y, int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            return EMPTY;
        }
        int x0 = Math.max(0, Math.min(x, maxWidth));
        int y0 = Math.max(0, Math.min(y, maxHeight));
        int x1 = Math.max(0, Math.min(x + width, maxWidth));
        int y1 = Math.max(0, Math.min(y + height, maxHeight));
        return new Roi(x0, y0, x1 - x0, y1 - y0);
    }

    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    public boolean isNotEmpty() {
        return !isEmpty();
    }

    public int maxX() {
        return x + width;
    }

    public int maxY() {
        return y + height;
    }

    public int area() {
        return width * height;
    }

    public boolean contains(int px, int py) {
        return px >= x && py >= y && px < maxX() && py < maxY();
    }

    public Roi clampTo(int maxWidth, int maxHeight) {
        return clamp(x, y, width, height, maxWidth, maxHeight);
    }

    public Roi intersect(Roi other) {
        Objects.requireNonNull(other, "other");
        int x0 = Math.max(x, other.x);
        int y0 = Math.max(y, other.y);
        int x1 = Math.min(maxX(), other.maxX());
        int y1 = Math.min(maxY(), other.maxY());
        if (x1 <= x0 || y1 <= y0) {
            return EMPTY;
        }
        return new Roi(x0, y0, x1 - x0, y1 - y0);
    }

    /** Grows the region by {@code margin} pixels on every side, keeping it inside the image. */
    public Roi expand(int margin, int maxWidth, int maxHeight) {
        return clamp(x - margin, y - margin, width + 2 * margin, height + 2 * margin, maxWidth, maxHeight);
    }

    public Roi scaled(double factor) {
        int w = Math.max(1, (int) Math.round(width * factor));
        int h = Math.max(1, (int) Math.round(height * factor));
        return new Roi(x, y, w, h);
    }

    /** Human readable form used in the status bar and in reports, e.g. {@code 10,20 100x50}. */
    public String describe() {
        return x + "," + y + " " + width + "x" + height;
    }
}
