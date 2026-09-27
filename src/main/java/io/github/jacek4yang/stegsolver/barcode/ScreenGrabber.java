package io.github.jacek4yang.stegsolver.barcode;

import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Captures a region of the screen so that a barcode which is only visible in another window can be
 * scanned.
 *
 * <p>The capture itself uses {@link Robot}, which on Linux requires an X11 session: on Wayland the
 * compositor does not expose other clients' pixels, and StegSolver says so instead of returning a
 * black image. The coordinate arithmetic lives in {@link #toDeviceRectangle}, which is a pure function
 * so that the HiDPI scaling between JavaFX logical coordinates and screen pixels can be tested.</p>
 */
public final class ScreenGrabber {

    private ScreenGrabber() {
    }

    /**
     * Maps a rectangle expressed in JavaFX logical screen coordinates onto device pixels.
     *
     * @param logicalX  left edge in logical (JavaFX) coordinates
     * @param logicalY  top edge in logical (JavaFX) coordinates
     * @param logicalW  width in logical coordinates
     * @param logicalH  height in logical coordinates
     * @param scaleX    horizontal output scale of the screen ({@code Screen.getOutputScaleX()})
     * @param scaleY    vertical output scale of the screen ({@code Screen.getOutputScaleY()})
     */
    public static Rectangle toDeviceRectangle(double logicalX, double logicalY, double logicalW,
            double logicalH, double scaleX, double scaleY) {
        if (logicalW <= 0 || logicalH <= 0) {
            throw new IllegalArgumentException("Empty capture rectangle");
        }
        double effectiveScaleX = scaleX <= 0 ? 1.0 : scaleX;
        double effectiveScaleY = scaleY <= 0 ? 1.0 : scaleY;
        int x = (int) Math.floor(logicalX * effectiveScaleX);
        int y = (int) Math.floor(logicalY * effectiveScaleY);
        int width = Math.max(1, (int) Math.ceil(logicalW * effectiveScaleX));
        int height = Math.max(1, (int) Math.ceil(logicalH * effectiveScaleY));
        return new Rectangle(x, y, width, height);
    }

    /** True when the current session is Wayland, where screen capture of other windows is not possible. */
    public static boolean isWaylandSession() {
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        return "wayland".equalsIgnoreCase(sessionType)
                || (waylandDisplay != null && !waylandDisplay.isBlank());
    }

    /** Human readable description of the current session, used in error messages. */
    public static String sessionDescription() {
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        return String.format(Locale.ROOT, "%s / %s",
                sessionType == null ? "unknown session" : sessionType,
                desktop == null ? "unknown desktop" : desktop);
    }

    /**
     * Captures the given device rectangle.
     *
     * @throws IllegalStateException when the environment cannot provide the pixels
     */
    public static ImageData capture(Rectangle deviceRectangle) {
        if (deviceRectangle.width <= 0 || deviceRectangle.height <= 0) {
            throw new IllegalStateException("The capture region is empty");
        }
        if (isWaylandSession()) {
            throw new IllegalStateException("Screen capture is only supported on X11 (current session: "
                    + sessionDescription() + "). On Wayland the compositor does not allow reading other "
                    + "windows' pixels.");
        }
        Rectangle screenBounds = primaryScreenBounds();
        Rectangle clipped = screenBounds == null ? deviceRectangle
                : deviceRectangle.intersection(screenBounds);
        if (clipped.width <= 0 || clipped.height <= 0) {
            throw new IllegalStateException("The capture region " + deviceRectangle
                    + " does not overlap the screen " + screenBounds);
        }
        try {
            Robot robot = new Robot();
            BufferedImage image = robot.createScreenCapture(clipped);
            return ImageData.fromBufferedImage(image);
        } catch (java.awt.AWTException | RuntimeException e) {
            throw new IllegalStateException("Screen capture failed: " + e, e);
        }
    }

    private static Rectangle primaryScreenBounds() {
        try {
            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice device = environment.getDefaultScreenDevice();
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            return configuration == null ? null : configuration.getBounds();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Crops a sub region of the screen, for example a single monitor. */
    public static Roi clampToScreen(Roi region) {
        Rectangle bounds = primaryScreenBounds();
        if (bounds == null) {
            return region;
        }
        return region.clampTo(bounds.width, bounds.height);
    }
}
