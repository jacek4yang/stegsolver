package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.barcode.ScreenGrabber;
import java.awt.Rectangle;
import java.util.Optional;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * A full screen overlay that lets the user drag a rectangle over the screen and returns it in device
 * pixels, ready for {@link ScreenGrabber#capture(Rectangle)}.
 *
 * <p>This is the replacement for the original tool's always-on-top selection window: it is dismissible
 * with Escape or a right click, it shows the size of the selection while dragging, and on Wayland (where
 * capturing another window's pixels is impossible) the caller refuses to show it at all instead of
 * returning a black image.</p>
 */
public final class ScreenRegionOverlay {

    private ScreenRegionOverlay() {
    }

    /** Shows the overlay and returns the dragged region in device pixels, if the user selected one. */
    public static Optional<Rectangle> pickRegion(Window owner) {
        Screen screen = owner == null ? Screen.getPrimary() : primaryScreenOf(owner);
        Rectangle2D bounds = screen.getBounds();
        Stage overlay = new Stage();
        if (owner != null) {
            overlay.initOwner(owner);
        }
        overlay.initModality(Modality.APPLICATION_MODAL);
        boolean transparent = true;
        try {
            overlay.initStyle(StageStyle.TRANSPARENT);
        } catch (RuntimeException e) {
            // Without a compositor a transparent stage is not available; the overlay then dims the
            // screen with an opaque background instead of showing it through.
            transparent = false;
            overlay.initStyle(StageStyle.UNDECORATED);
        }

        double width = bounds.getWidth();
        double height = bounds.getHeight();
        Canvas canvas = new Canvas(width, height);
        StackPane root = new StackPane(canvas);
        root.setStyle("-fx-background-color: " + (transparent ? "transparent" : "#101010") + ";");

        Scene scene = new Scene(root, width, height);
        scene.setFill(transparent ? Color.TRANSPARENT : Color.web("#101010"));
        double dim = transparent ? 0.35 : 0.85;

        final double[] start = new double[2];
        final double[] current = new double[2];
        final boolean[] dragging = new boolean[1];
        final Rectangle[] selection = new Rectangle[1];

        drawOverlay(canvas, dim, null, false);
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.isSecondaryButtonDown()) {
                overlay.close();
                return;
            }
            start[0] = event.getX();
            start[1] = event.getY();
            current[0] = event.getX();
            current[1] = event.getY();
            dragging[0] = true;
            drawOverlay(canvas, dim, new double[] {start[0], start[1], 0, 0}, true);
        });
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!dragging[0]) {
                return;
            }
            current[0] = event.getX();
            current[1] = event.getY();
            double x = Math.min(start[0], current[0]);
            double y = Math.min(start[1], current[1]);
            drawOverlay(canvas, dim,
                    new double[] {x, y, Math.abs(current[0] - start[0]), Math.abs(current[1] - start[1])}, true);
        });
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (!dragging[0]) {
                return;
            }
            dragging[0] = false;
            double x = Math.min(start[0], current[0]);
            double y = Math.min(start[1], current[1]);
            double w = Math.abs(current[0] - start[0]);
            double h = Math.abs(current[1] - start[1]);
            if (w < 4 || h < 4) {
                overlay.close();
                return;
            }
            selection[0] = toDevicePixels(overlay, screen, x, y, w, h);
            overlay.close();
        });
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                overlay.close();
            }
        });

        overlay.setX(bounds.getMinX());
        overlay.setY(bounds.getMinY());
        overlay.setScene(scene);
        overlay.setTitle("Select a screen region");
        overlay.showAndWait();
        return Optional.ofNullable(selection[0]);
    }

    /**
     * Converts overlay local coordinates into device pixels of the screen, which is what screen capture
     * works in. On a HiDPI screen the logical coordinates of JavaFX and the device pixels of the capture
     * differ by the output scale.
     */
    static Rectangle toDevicePixels(Stage overlay, Screen screen, double localX, double localY, double width,
            double height) {
        double logicalX = overlay.getX() + localX;
        double logicalY = overlay.getY() + localY;
        return ScreenGrabber.toDeviceRectangle(logicalX, logicalY, width, height, screen.getOutputScaleX(),
                screen.getOutputScaleY());
    }

    private static Screen primaryScreenOf(Window owner) {
        var screens = Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(),
                owner.getHeight());
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }

    private static void drawOverlay(Canvas canvas, double dim, double[] rect, boolean withHint) {
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        graphics.clearRect(0, 0, width, height);
        graphics.setFill(Color.color(0, 0, 0, dim));
        graphics.fillRect(0, 0, width, height);
        if (rect != null) {
            // Punch the selection out of the dim layer so the region can be seen clearly.
            graphics.clearRect(rect[0], rect[1], rect[2], rect[3]);
            graphics.setStroke(Color.web("#e8112d"));
            graphics.setLineWidth(2);
            graphics.strokeRect(rect[0], rect[1], rect[2], rect[3]);
            graphics.setFill(Color.web("#e8112d"));
            graphics.setFont(Font.font(13));
            String size = (int) rect[2] + " x " + (int) rect[3];
            graphics.fillText(size, rect[0] + 4, Math.max(14, rect[1] - 6));
        }
        if (withHint || rect == null) {
            graphics.setFill(Color.WHITE);
            graphics.setFont(Font.font(15));
            String hint = "Drag a rectangle around the barcode, right click or Escape to cancel";
            graphics.fillText(hint, 24, height - 28);
        }
    }
}
