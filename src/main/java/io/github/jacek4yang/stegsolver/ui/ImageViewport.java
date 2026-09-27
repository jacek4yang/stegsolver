package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.barcode.BarcodeHit;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.core.ViewportGeometry;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

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

    private final VBox emptyStateNode = buildEmptyState();

    private Runnable onActionCopyImage;
    private Runnable onActionSaveImage;
    private Runnable onActionScanImage;
    private Runnable onActionScanSelection;
    private Runnable onActionNextTransform;
    private Runnable onActionPrevTransform;

    public ImageViewport() {
        getStyleClass().add("steg-viewport");
        imageView.setPreserveRatio(false);
        imageView.setSmooth(false);
        imageView.setManaged(false);
        overlay.setManaged(false);
        emptyStateNode.setManaged(false);
        getChildren().addAll(imageView, overlay, emptyStateNode);
        setClip(clip);
        setMinSize(0, 0);
        setFocusTraversable(true);
        geometry = new ViewportGeometry(1, 1);
        installHandlers();
    }

    private VBox buildEmptyState() {
        Label icon = new Label("\u25A3");
        icon.getStyleClass().add("steg-empty-icon");

        Label title = new Label("No Image Loaded");
        title.getStyleClass().add("steg-empty-title");

        Label subtitle = new Label("Drop an image here or press Ctrl+O to open");
        subtitle.getStyleClass().add("steg-empty-hint");

        HBox hintsRow1 = new HBox(8,
                hintPill("Ctrl+O", "Open"),
                hintPill("\u25C0 / \u25B6", "Transforms"),
                hintPill("Ctrl+B", "Scan Barcodes")
        );
        hintsRow1.setAlignment(Pos.CENTER);

        HBox hintsRow2 = new HBox(8,
                hintPill("Scroll", "Zoom"),
                hintPill("Drag", "Pan"),
                hintPill("Double-click", "Fit / 1:1")
        );
        hintsRow2.setAlignment(Pos.CENTER);

        VBox box = new VBox(10, icon, title, subtitle, hintsRow1, hintsRow2);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("steg-empty-state");
        return box;
    }

    private static HBox hintPill(String key, String desc) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("steg-key-badge");
        Label descLabel = new Label(desc);
        descLabel.getStyleClass().add("steg-key-desc");
        HBox pill = new HBox(5, keyLabel, descLabel);
        pill.setAlignment(Pos.CENTER);
        pill.getStyleClass().add("steg-hint-pill");
        return pill;
    }

    public void setContextActions(Runnable copy, Runnable save, Runnable scanImage, Runnable scanSelection,
            Runnable nextTransform, Runnable prevTransform) {
        this.onActionCopyImage = copy;
        this.onActionSaveImage = save;
        this.onActionScanImage = scanImage;
        this.onActionScanSelection = scanSelection;
        this.onActionNextTransform = nextTransform;
        this.onActionPrevTransform = prevTransform;
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
        emptyStateNode.setVisible(false);
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
        emptyStateNode.setVisible(true);
        layoutImage();
    }

    public String contentLabel() {
        return contentLabel;
    }

    public boolean hasContent() {
        return pixels != null;
    }

    /** The node that draws the image, for rendering diagnostics. */
    public javafx.scene.Node imageViewNode() {
        return imageView;
    }

    /** Layout state of the viewport, used by the smoke test and when diagnosing rendering problems. */
    public String diagnostics() {
        return "viewport=" + getWidth() + "x" + getHeight()
                + " content=" + (pixels == null ? "none" : imageWidth + "x" + imageHeight)
                + " rendered=" + (imageView.getImage() == null ? "none"
                        : (int) imageView.getImage().getWidth() + "x" + (int) imageView.getImage().getHeight())
                + " zoom=" + geometry.zoom()
                + " pan=" + geometry.panX() + "," + geometry.panY()
                + " imageView=" + imageView.getX() + "," + imageView.getY()
                + " " + imageView.getFitWidth() + "x" + imageView.getFitHeight()
                + " visible=" + imageView.isVisible();
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
        if (emptyStateNode.isVisible()) {
            double ew = Math.min(width - 40, 520);
            double eh = emptyStateNode.prefHeight(ew);
            emptyStateNode.resizeRelocate((width - ew) / 2, (height - eh) / 2, ew, eh);
        }
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

        if (!hasContent()) {
            return;
        }

        double zoom = geometry.zoom();
        double imgX = geometry.panX();
        double imgY = geometry.panY();
        double imgW = imageWidth * zoom;
        double imgH = imageHeight * zoom;

        // Subtle outer border defining exact image canvas boundary
        graphics.setLineWidth(1.0);
        graphics.setStroke(Color.color(0.5, 0.5, 0.5, 0.35));
        graphics.strokeRect(imgX - 0.5, imgY - 0.5, imgW + 1.0, imgH + 1.0);

        // Pixel grid when zoomed in (zoom >= 4.0)
        if (zoom >= 4.0) {
            int startX = Math.max(0, (int) Math.floor(geometry.toImageX(0)));
            int endX = Math.min(imageWidth, (int) Math.ceil(geometry.toImageX(width)));
            int startY = Math.max(0, (int) Math.floor(geometry.toImageY(0)));
            int endY = Math.min(imageHeight, (int) Math.ceil(geometry.toImageY(height)));

            double opacity = Math.min(0.28, (zoom - 3.5) * 0.07);
            graphics.setStroke(Color.color(0.5, 0.5, 0.5, opacity));
            graphics.setLineWidth(0.65);

            for (int x = startX; x <= endX; x++) {
                double vx = Math.floor(geometry.toViewX(x)) + 0.5;
                graphics.strokeLine(vx, Math.max(imgY, 0), vx, Math.min(imgY + imgH, height));
            }
            for (int y = startY; y <= endY; y++) {
                double vy = Math.floor(geometry.toViewY(y)) + 0.5;
                graphics.strokeLine(Math.max(imgX, 0), vy, Math.min(imgX + imgW, width), vy);
            }
        }

        // Selection overlay with dual-tone contrast and precision badge
        if (selection.isNotEmpty()) {
            double x = geometry.toViewX(selection.x());
            double y = geometry.toViewY(selection.y());
            double w = selection.width() * zoom;
            double h = selection.height() * zoom;

            // Semi-transparent selection fill
            graphics.setFill(Color.color(0.18, 0.55, 0.95, 0.22));
            graphics.fillRect(x, y, w, h);

            // Outer dark boundary for high contrast on bright pixels
            graphics.setLineWidth(1.0);
            graphics.setStroke(Color.rgb(15, 23, 42, 0.7));
            graphics.strokeRect(x - 0.5, y - 0.5, w + 1.0, h + 1.0);

            // Inner bright accent border
            graphics.setLineWidth(1.5);
            graphics.setStroke(Color.web("#38bdf8"));
            graphics.strokeRect(x + 0.5, y + 0.5, w - 1.0, h - 1.0);

            // Corner handles
            double hs = 3.5;
            drawCornerHandle(graphics, x, y, hs);
            drawCornerHandle(graphics, x + w, y, hs);
            drawCornerHandle(graphics, x, y + h, hs);
            drawCornerHandle(graphics, x + w, y + h, hs);

            // Precision dimension & coordinate badge
            String badgeText = selection.width() + " \u00d7 " + selection.height() + "  (" + selection.x() + ", " + selection.y() + ")";
            graphics.setFont(Font.font("Consolas", FontWeight.BOLD, 10.5));
            double badgeW = badgeText.length() * 6.5 + 12;
            double badgeH = 18;
            double badgeX = x + w - badgeW;
            double badgeY = y + h + 4;
            if (badgeY + badgeH > height) {
                badgeY = y - badgeH - 4;
            }
            if (badgeX < 4) {
                badgeX = 4;
            }
            graphics.setFill(Color.rgb(15, 23, 42, 0.88));
            graphics.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 4, 4);
            graphics.setStroke(Color.rgb(56, 189, 248, 0.7));
            graphics.setLineWidth(1.0);
            graphics.strokeRoundRect(badgeX, badgeY, badgeW, badgeH, 4, 4);
            graphics.setFill(Color.WHITE);
            graphics.fillText(badgeText, badgeX + 6, badgeY + 13);
        }

        // Barcode overlay
        if (showBarcodeOverlay && !hits.isEmpty()) {
            graphics.setLineWidth(2.0);
            for (int i = 0; i < hits.size(); i++) {
                BarcodeHit hit = hits.get(i);
                Roi bounds = hit.bounds();
                if (bounds == null || bounds.isEmpty()) {
                    continue;
                }
                double x = geometry.toViewX(bounds.x());
                double y = geometry.toViewY(bounds.y());
                double w = Math.max(4, bounds.width() * zoom);
                double h = Math.max(4, bounds.height() * zoom);

                // Subtle transparent red fill
                graphics.setFill(Color.rgb(239, 68, 68, 0.15));
                graphics.fillRect(x, y, w, h);

                // Outer crisp border
                graphics.setStroke(Color.web("#ef4444"));
                graphics.strokeRect(x, y, w, h);

                // Hit badge with number and symbology
                String label = (i + 1) + "  " + hit.format().name();
                graphics.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.5));
                double tagW = label.length() * 6.2 + 10;
                double tagH = 17;
                double tagY = Math.max(0, y - tagH - 2);

                graphics.setFill(Color.web("#dc2626"));
                graphics.fillRoundRect(x, tagY, tagW, tagH, 3, 3);
                graphics.setFill(Color.WHITE);
                graphics.fillText(label, x + 5, tagY + 12);
            }
        }
    }

    private static void drawCornerHandle(GraphicsContext graphics, double x, double y, double radius) {
        graphics.setFill(Color.WHITE);
        graphics.setStroke(Color.web("#0284c7"));
        graphics.setLineWidth(1.0);
        graphics.fillRect(x - radius, y - radius, radius * 2, radius * 2);
        graphics.strokeRect(x - radius, y - radius, radius * 2, radius * 2);
    }

    // ------------------------------------------------------------------ input

    private void installHandlers() {
        addEventHandler(ScrollEvent.SCROLL, event -> {
            if (!hasContent()) {
                return;
            }
            double factor = event.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
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
                double endX = event.getX();
                double endY = event.getY();
                if (event.isShiftDown()) {
                    double deltaX = endX - dragStartX;
                    double deltaY = endY - dragStartY;
                    double side = Math.max(Math.abs(deltaX), Math.abs(deltaY));
                    endX = dragStartX + Math.copySign(side, deltaX);
                    endY = dragStartY + Math.copySign(side, deltaY);
                }
                setSelection(geometry.viewRectToImageRoi(dragStartX, dragStartY, endX, endY));
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

        setOnContextMenuRequested(event -> {
            showContextMenu(event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private void showContextMenu(double screenX, double screenY) {
        ContextMenu menu = new ContextMenu();
        MenuItem copyItem = new MenuItem("Copy displayed image");
        copyItem.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        copyItem.setDisable(!hasContent());
        copyItem.setOnAction(e -> {
            if (onActionCopyImage != null) {
                onActionCopyImage.run();
            }
        });

        MenuItem saveItem = new MenuItem("Save displayed image as...");
        saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        saveItem.setDisable(!hasContent());
        saveItem.setOnAction(e -> {
            if (onActionSaveImage != null) {
                onActionSaveImage.run();
            }
        });

        MenuItem fitItem = new MenuItem("Fit to window");
        fitItem.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN));
        fitItem.setDisable(!hasContent());
        fitItem.setOnAction(e -> fitToWindow());

        MenuItem actualItem = new MenuItem("Actual size (1:1)");
        actualItem.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN));
        actualItem.setDisable(!hasContent());
        actualItem.setOnAction(e -> actualSize());

        menu.getItems().addAll(copyItem, saveItem, new SeparatorMenuItem(), fitItem, actualItem);

        if (selection.isNotEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem scanSel = new MenuItem("Scan selection for barcodes");
            scanSel.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
            scanSel.setOnAction(e -> {
                if (onActionScanSelection != null) {
                    onActionScanSelection.run();
                }
            });

            MenuItem clearSel = new MenuItem("Clear selection");
            clearSel.setAccelerator(new KeyCodeCombination(KeyCode.ESCAPE));
            clearSel.setOnAction(e -> clearSelection());
            menu.getItems().addAll(scanSel, clearSel);
        } else if (hasContent()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem scanAll = new MenuItem("Scan image for barcodes");
            scanAll.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN));
            scanAll.setOnAction(e -> {
                if (onActionScanImage != null) {
                    onActionScanImage.run();
                }
            });
            menu.getItems().add(scanAll);
        }

        if (hasContent()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem nextT = new MenuItem("Next transform");
            nextT.setAccelerator(new KeyCodeCombination(KeyCode.RIGHT));
            nextT.setOnAction(e -> {
                if (onActionNextTransform != null) {
                    onActionNextTransform.run();
                }
            });

            MenuItem prevT = new MenuItem("Previous transform");
            prevT.setAccelerator(new KeyCodeCombination(KeyCode.LEFT));
            prevT.setOnAction(e -> {
                if (onActionPrevTransform != null) {
                    onActionPrevTransform.run();
                }
            });
            menu.getItems().addAll(prevT, nextT);
        }

        menu.show(this, screenX, screenY);
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
