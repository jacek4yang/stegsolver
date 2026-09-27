package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.barcode.BarcodeHit;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.core.ViewportGeometry;
import java.util.List;
import java.util.function.Consumer;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * The central image viewport: zoom, pan, region selection, the barcode overlay and the pixel inspector.
 *
 * <p>Pan and zoom are implemented directly on an {@link ImageView} inside a clipped pane rather than
 * through a {@code ScrollPane}, because that makes "zoom at the cursor", "fit", "1:1" and, most
 * importantly, mapping a dragged rectangle back to image pixels deterministic — the arithmetic lives
 * in {@link ViewportGeometry} and is unit tested without a toolkit.</p>
 *
 * <p>Nearest neighbour rendering is used at 100% and above, so that a single pixel of a bit plane
 * stays exactly one pixel on screen; smoothing is only enabled when zooming out.</p>
 */
public final class ImageViewport extends Pane {

    private final ImageView imageView = new ImageView();
    private final Canvas overlay = new Canvas();
    private final Rectangle clip = new Rectangle();

    private ViewportGeometry geometry;
    private int[] pixels;
    private int imageWidth;
    private int imageHeight;
    private boolean hasAlpha;
    private String contentLabel = "No image";

    private boolean selectionMode;
    private Roi selection = Roi.EMPTY;
    private double dragStartX;
    private double dragStartY;
    private double panStartX;
    private double panStartY;
    private double panAnchorX;
    private double panAnchorY;
    private boolean panning;
    private boolean selecting;
    private List<BarcodeHit> hits = List.of();
    private boolean showBarcodeOverlay = true;

    private Consumer<Integer> onPixelX = value -> {
    };
    private Consumer<Integer> onPixelY = value -> {
    };
    private Runnable onPixelExit = () -> {
    };
    private Consumer<Roi> onSelectionChanged = roi -> {
    };

    public ImageViewport() {
        getStyleClass().add("steg-viewport");
        imageView.setPreserveRatio(false);
        imageView.setSmooth(false);
        imageView.setManaged(false);
        overlay.setManaged(false);
        getChildren().addAll(imageView, overlay);
        setClip(clip);
        setMinSize(0, 0);
        setFocusTraversable(true);
        geometry = new ViewportGeometry(1, 1);
        installHandlers();
    }

    // ------------------------------------------------------------------ content

    /**
     * Sets the image to display. The view is kept when the new content has the same size (so stepping
     * through transforms does not jump around) and refitted otherwise.
     */
    public void setContent(int[] newPixels, int width, int height, boolean alpha, WritableImage rendered,
            String label) {
        boolean sameSize = width == imageWidth && height == imageHeight;
        this.pixels = newPixels;
        this.imageWidth = width;
        this.imageHeight = height;
        this.hasAlpha = alpha;
        this.contentLabel = label;
        imageView.setImage(rendered);
        if (!sameSize) {
            geometry.setImage(width, height);
            geometry.setViewportSize(getWidth(), getHeight());
            geometry.fit();
            selection = Roi.EMPTY;
        }
        geometry.setViewportSize(getWidth(), getHeight());
        layoutImage();
    }

    /** Clears the viewport, for example when the document is closed. */
    public void clear() {
        pixels = null;
        imageView.setImage(null);
        hits = List.of();
        selection = Roi.EMPTY;
        contentLabel = "No image";
        overlay.getGraphicsContext2D().clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
        layoutImage();
    }

    public String contentLabel() {
        return contentLabel;
    }

    public boolean hasContent() {
        return pixels != null;
    }

    public ViewportGeometry geometry() {
        return geometry;
    }

    /** The current view pixels; may be {@code null} when nothing is displayed. */
    public int[] viewPixels() {
        return pixels;
    }

    public int viewPixelAt(int x, int y) {
        if (pixels == null || x < 0 || y < 0 || x >= imageWidth || y >= imageHeight) {
            return 0;
        }
        return pixels[y * imageWidth + x];
    }

    public boolean contentHasAlpha() {
        return hasAlpha;
    }

    // ------------------------------------------------------------------ zoom & pan

    public void fitToWindow() {
        geometry.fit();
        layoutImage();
    }

    public void actualSize() {
        geometry.actualSize();
        layoutImage();
    }

    public void zoomBy(double factor) {
        geometry.zoomAt(getWidth() / 2, getHeight() / 2, factor);
        layoutImage();
    }

    public double zoom() {
        return geometry.zoom();
    }

    public void setZoom(double zoom) {
        geometry.setZoom(zoom);
        layoutImage();
    }

    // ------------------------------------------------------------------ selection

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean enabled) {
        this.selectionMode = enabled;
        setCursor(enabled ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        if (!enabled) {
            clearSelection();
        }
        drawOverlay();
    }

    public Roi selection() {
        return selection;
    }

    public void setSelection(Roi roi) {
        selection = roi == null ? Roi.EMPTY : roi.clampTo(Math.max(1, imageWidth), Math.max(1, imageHeight));
        onSelectionChanged.accept(selection);
        drawOverlay();
    }

    public void clearSelection() {
        if (selection.isNotEmpty()) {
            selection = Roi.EMPTY;
            onSelectionChanged.accept(selection);
            drawOverlay();
        }
    }

    // ------------------------------------------------------------------ barcode overlay

    public void setBarcodeHits(List<BarcodeHit> barcodeHits) {
        this.hits = barcodeHits == null ? List.of() : barcodeHits;
        drawOverlay();
    }

    public void setShowBarcodeOverlay(boolean show) {
        this.showBarcodeOverlay = show;
        drawOverlay();
    }

    public boolean isShowBarcodeOverlay() {
        return showBarcodeOverlay;
    }

    // ------------------------------------------------------------------ listeners

    public void setOnPixelHover(Consumer<Integer> xConsumer, Consumer<Integer> yConsumer, Runnable exit) {
        this.onPixelX = xConsumer;
        this.onPixelY = yConsumer;
        this.onPixelExit = exit;
    }

    public void setOnSelectionChanged(Consumer<Roi> consumer) {
        this.onSelectionChanged = consumer;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        clip.setWidth(width);
        clip.setHeight(height);
        overlay.setWidth(Math.max(0, width));
        overlay.setHeight(Math.max(0, height));
        geometry.setViewportSize(width, height);
        layoutImage();
    }

    private void layoutImage() {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return;
        }
        double zoom = geometry.zoom();
        imageView.setX(geometry.panX());
        imageView.setY(geometry.panY());
        imageView.setFitWidth(imageWidth * zoom);
        imageView.setFitHeight(imageHeight * zoom);
        // Nearest neighbour above 1:1 keeps bit plane pixels crisp; smoothing helps when zoomed out.
        imageView.setSmooth(zoom < 1.0);
        drawOverlay();
    }

    private void drawOverlay() {
        GraphicsContext graphics = overlay.getGraphicsContext2D();
        double width = overlay.getWidth();
        double height = overlay.getHeight();
        graphics.clearRect(0, 0, width, height);

        if (selection.isNotEmpty()) {
            double x = geometry.toViewX(selection.x());
            double y = geometry.toViewY(selection.y());
            double w = selection.width() * geometry.zoom();
            double h = selection.height() * geometry.zoom();
            graphics.setFill(Color.color(0.2, 0.6, 1.0, 0.18));
            graphics.fillRect(x, y, w, h);
            graphics.setStroke(Color.web("#2f6fb5"));
            graphics.setLineWidth(1.5);
            graphics.strokeRect(x + 0.5, y + 0.5, w, h);
        }

        if (showBarcodeOverlay && !hits.isEmpty()) {
            graphics.setLineWidth(2);
            for (int i = 0; i < hits.size(); i++) {
                BarcodeHit hit = hits.get(i);
                Roi bounds = hit.bounds();
                if (bounds == null || bounds.isEmpty()) {
                    continue;
                }
                double x = geometry.toViewX(bounds.x());
                double y = geometry.toViewY(bounds.y());
                double w = Math.max(2, bounds.width() * geometry.zoom());
                double h = Math.max(2, bounds.height() * geometry.zoom());
                graphics.setStroke(Color.web("#e8112d"));
                graphics.strokeRect(x, y, w, h);
                String label = String.valueOf(i + 1);
                graphics.setFill(Color.web("#e8112d"));
                graphics.fillRect(x, Math.max(0, y - 16), 16, 16);
                graphics.setFill(Color.WHITE);
                graphics.fillText(label, x + 5, Math.max(12, y - 4));
            }
        }
    }

    // ------------------------------------------------------------------ input

    private void installHandlers() {
        addEventHandler(ScrollEvent.SCROLL, event -> {
            if (!hasContent()) {
                return;
            }
            double factor = event.getDeltaY() > 0 ? 1.12 : 1 / 1.12;
            geometry.zoomAt(event.getX(), event.getY(), factor);
            layoutImage();
            event.consume();
        });

        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            requestFocus();
            if (event.getButton() == MouseButton.PRIMARY && selectionMode && hasContent()) {
                selecting = true;
                dragStartX = event.getX();
                dragStartY = event.getY();
                setSelection(geometry.viewRectToImageRoi(dragStartX, dragStartY, dragStartX, dragStartY));
            } else if (event.getButton() == MouseButton.PRIMARY || event.getButton() == MouseButton.MIDDLE) {
                panning = true;
                panStartX = event.getX();
                panStartY = event.getY();
                panAnchorX = geometry.panX();
                panAnchorY = geometry.panY();
                setCursor(Cursor.CLOSED_HAND);
            }
        });

        addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (selecting) {
                setSelection(geometry.viewRectToImageRoi(dragStartX, dragStartY, event.getX(), event.getY()));
                reportPixel(event);
            } else if (panning) {
                geometry.setPan(panAnchorX + (event.getX() - panStartX), panAnchorY + (event.getY() - panStartY));
                layoutImage();
            }
        });

        addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            selecting = false;
            if (panning) {
                panning = false;
                setCursor(selectionMode ? Cursor.CROSSHAIR : Cursor.DEFAULT);
            }
        });

        addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getClickCount() == 2 && hasContent()) {
                if (geometry.zoom() < 1.0) {
                    actualSize();
                } else {
                    fitToWindow();
                }
            }
        });

        addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
            if (hits != null || hasContent()) {
                reportPixel(event);
            }
        });
        addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            selecting = false;
            onPixelExit.run();
        });
    }

    private void reportPixel(MouseEvent event) {
        if (!hasContent() || !geometry.isInsideImage(event.getX(), event.getY())) {
            onPixelExit.run();
            return;
        }
        onPixelX.accept(geometry.pickImageX(event.getX()));
        onPixelY.accept(geometry.pickImageY(event.getY()));
    }
}
