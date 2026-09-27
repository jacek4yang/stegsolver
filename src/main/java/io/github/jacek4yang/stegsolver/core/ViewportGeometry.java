package io.github.jacek4yang.stegsolver.core;

/**
 * Pure geometry of a zoomable, pannable image viewport.
 *
 * <p>Deliberately free of any JavaFX (or AWT) dependency: the mapping between view coordinates
 * (pixels inside the viewport widget) and image coordinates (pixels of the displayed image) is the
 * part that is easiest to get wrong and the most valuable to unit test.</p>
 *
 * <p>The transform applied is a uniform scale followed by a translation:</p>
 * <pre>
 *   viewX = panX + imageX * zoom
 *   imageX = (viewX - panX) / zoom
 * </pre>
 */
public final class ViewportGeometry {

    public static final double MIN_ZOOM = 1.0 / 64.0;
    public static final double MAX_ZOOM = 64.0;

    private int imageWidth;
    private int imageHeight;
    private double viewportWidth;
    private double viewportHeight;
    private double zoom = 1.0;
    private double panX;
    private double panY;

    public ViewportGeometry(int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("Invalid image size: " + imageWidth + "x" + imageHeight);
        }
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    public static double clampZoom(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return 1.0;
        }
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }

    /** Switches this geometry to another image (for example a tool preview) and resets the view. */
    public void setImage(int newImageWidth, int newImageHeight) {
        if (newImageWidth <= 0 || newImageHeight <= 0) {
            throw new IllegalArgumentException("Invalid image size: " + newImageWidth + "x" + newImageHeight);
        }
        this.imageWidth = newImageWidth;
        this.imageHeight = newImageHeight;
        this.zoom = 1.0;
        this.panX = 0;
        this.panY = 0;
        fit();
    }

    public int imageWidth() {
        return imageWidth;
    }

    public int imageHeight() {
        return imageHeight;
    }

    public double viewportWidth() {
        return viewportWidth;
    }

    public double viewportHeight() {
        return viewportHeight;
    }

    public void setViewportSize(double width, double height) {
        this.viewportWidth = Math.max(0, width);
        this.viewportHeight = Math.max(0, height);
        clampPan();
    }

    public double zoom() {
        return zoom;
    }

    public double panX() {
        return panX;
    }

    public double panY() {
        return panY;
    }

    public double displayWidth() {
        return imageWidth * zoom;
    }

    public double displayHeight() {
        return imageHeight * zoom;
    }

    public boolean hasViewport() {
        return viewportWidth > 0 && viewportHeight > 0;
    }

    /** Sets the zoom, keeping the image point currently under the viewport centre in place. */
    public void setZoom(double value) {
        zoomAt(viewportWidth / 2.0, viewportHeight / 2.0, value / zoom);
    }

    /** Multiplies the zoom by {@code factor}, keeping the given viewport point anchored. */
    public void zoomAt(double anchorViewX, double anchorViewY, double factor) {
        if (factor <= 0 || Double.isNaN(factor)) {
            return;
        }
        double newZoom = clampZoom(zoom * factor);
        if (newZoom == zoom) {
            return;
        }
        double imageX = toImageX(anchorViewX);
        double imageY = toImageY(anchorViewY);
        zoom = newZoom;
        panX = anchorViewX - imageX * zoom;
        panY = anchorViewY - imageY * zoom;
        clampPan();
    }

    /** Scales the image down so that it fits the viewport, never magnifying past 100%. */
    public void fit() {
        if (!hasViewport()) {
            zoom = 1.0;
            return;
        }
        double scale = Math.min(viewportWidth / imageWidth, viewportHeight / imageHeight);
        zoom = clampZoom(Math.min(1.0, scale));
        centreContent();
    }

    /** Always scales to fill the viewport, magnifying small images as well. */
    public void stretchToViewport() {
        if (!hasViewport()) {
            return;
        }
        zoom = clampZoom(Math.min(viewportWidth / imageWidth, viewportHeight / imageHeight));
        centreContent();
    }

    public void actualSize() {
        zoom = 1.0;
        centreContent();
    }

    public void centreContent() {
        panX = (viewportWidth - displayWidth()) / 2.0;
        panY = (viewportHeight - displayHeight()) / 2.0;
        clampPan();
    }

    public void panBy(double dx, double dy) {
        panX += dx;
        panY += dy;
        clampPan();
    }

    public void setPan(double x, double y) {
        panX = x;
        panY = y;
        clampPan();
    }

    /**
     * Keeps the image covering the viewport: content smaller than the viewport is centred, larger
     * content cannot be dragged away from the edges.
     */
    public void clampPan() {
        panX = clampAxis(panX, displayWidth(), viewportWidth);
        panY = clampAxis(panY, displayHeight(), viewportHeight);
    }

    private static double clampAxis(double pan, double content, double viewport) {
        if (viewport <= 0) {
            return pan;
        }
        if (content <= viewport) {
            return (viewport - content) / 2.0;
        }
        return Math.max(viewport - content, Math.min(0, pan));
    }

    public double toImageX(double viewX) {
        return (viewX - panX) / zoom;
    }

    public double toImageY(double viewY) {
        return (viewY - panY) / zoom;
    }

    public double toViewX(double imageX) {
        return panX + imageX * zoom;
    }

    public double toViewY(double imageY) {
        return panY + imageY * zoom;
    }

    /** Image column under a viewport point; the result is clamped to the image. */
    public int pickImageX(double viewX) {
        return clampIndex(toImageX(viewX), imageWidth);
    }

    /** Image row under a viewport point; the result is clamped to the image. */
    public int pickImageY(double viewY) {
        return clampIndex(toImageY(viewY), imageHeight);
    }

    /** True when the viewport point is actually over the image content. */
    public boolean isInsideImage(double viewX, double viewY) {
        double ix = toImageX(viewX);
        double iy = toImageY(viewY);
        return ix >= 0 && iy >= 0 && ix < imageWidth && iy < imageHeight;
    }

    private static int clampIndex(double value, int size) {
        if (Double.isNaN(value)) {
            return 0;
        }
        int index = (int) Math.floor(value);
        if (index < 0) {
            return 0;
        }
        return Math.min(index, size - 1);
    }

    /**
     * Maps a rectangle expressed in viewport coordinates (a mouse drag) onto the image.
     *
     * <p>The rectangle is normalised first, so dragging in any direction works, and the result is
     * clamped to the image bounds. The returned region is empty when the drag does not overlap the
     * image at all.</p>
     */
    public Roi viewRectToImageRoi(double viewX0, double viewY0, double viewX1, double viewY1) {
        double left = Math.min(viewX0, viewX1);
        double top = Math.min(viewY0, viewY1);
        double right = Math.max(viewX0, viewX1);
        double bottom = Math.max(viewY0, viewY1);

        double imageLeft = toImageX(left);
        double imageTop = toImageY(top);
        double imageRight = toImageX(right);
        double imageBottom = toImageY(bottom);

        int x0 = (int) Math.floor(Math.max(0, imageLeft));
        int y0 = (int) Math.floor(Math.max(0, imageTop));
        int x1 = (int) Math.ceil(Math.min(imageWidth, imageRight));
        int y1 = (int) Math.ceil(Math.min(imageHeight, imageBottom));
        if (x1 <= x0 || y1 <= y0) {
            return Roi.EMPTY;
        }
        return Roi.clamp(x0, y0, x1 - x0, y1 - y0, imageWidth, imageHeight);
    }

    /** The region of the image currently visible in the viewport, clamped to the image. */
    public Roi visibleImageRoi() {
        if (!hasViewport()) {
            return Roi.whole(imageWidth, imageHeight);
        }
        return viewRectToImageRoi(0, 0, viewportWidth, viewportHeight);
    }

    @Override
    public String toString() {
        return "ViewportGeometry[" + imageWidth + "x" + imageHeight
                + " zoom=" + zoom + " pan=" + panX + "," + panY + "]";
    }
}
