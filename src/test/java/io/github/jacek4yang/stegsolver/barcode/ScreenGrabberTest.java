package io.github.jacek4yang.stegsolver.barcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScreenGrabberTest {

    @Test
    @DisplayName("logical coordinates are scaled to device pixels")
    void deviceRectangle() {
        Rectangle rectangle = ScreenGrabber.toDeviceRectangle(100, 50, 200, 100, 1.0, 1.0);
        assertEquals(new Rectangle(100, 50, 200, 100), rectangle);
        // HiDPI: JavaFX logical coordinates are smaller than device pixels.
        rectangle = ScreenGrabber.toDeviceRectangle(100, 50, 200, 100, 2.0, 2.0);
        assertEquals(new Rectangle(200, 100, 400, 200), rectangle);
        // Mixed scaling (common with fractional scaling) rounds outwards so nothing is lost.
        rectangle = ScreenGrabber.toDeviceRectangle(1.5, 2.5, 10.2, 10.2, 1.5, 1.5);
        assertEquals(2, rectangle.x);
        assertEquals(3, rectangle.y);
        assertEquals(16, rectangle.width);
        assertEquals(16, rectangle.height);
        // A degenerate scale falls back to 1:1 instead of producing an empty rectangle.
        rectangle = ScreenGrabber.toDeviceRectangle(0, 0, 10, 10, 0, 0);
        assertEquals(new Rectangle(0, 0, 10, 10), rectangle);
    }

    @Test
    @DisplayName("an empty capture rectangle is rejected before touching the screen")
    void emptyRectangle() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScreenGrabber.toDeviceRectangle(0, 0, 0, 10, 1, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScreenGrabber.toDeviceRectangle(0, 0, 10, -1, 1, 1));
    }

    @Test
    @DisplayName("an empty region is refused by the capture itself")
    void emptyCapture() {
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> ScreenGrabber.capture(new Rectangle(0, 0, 0, 0)));
        assertTrue(error.getMessage().contains("empty"), error.getMessage());
    }

    @Test
    @DisplayName("the session description is always available for error messages")
    void sessionDescription() {
        String description = ScreenGrabber.sessionDescription();
        assertFalse(description.isBlank());
        assertTrue(description.contains("/"));
    }

    @Test
    @DisplayName("Wayland sessions are detectable so that the limitation can be explained")
    void waylandDetection() {
        // The result depends on the environment, but the call must never throw and must be usable.
        boolean wayland = ScreenGrabber.isWaylandSession();
        assertEquals(wayland, ScreenGrabber.isWaylandSession());
    }

    @Test
    @DisplayName("clamping a region to the screen keeps a usable rectangle")
    void clamping() {
        io.github.jacek4yang.stegsolver.core.Roi clamped = ScreenGrabber.clampToScreen(
                new io.github.jacek4yang.stegsolver.core.Roi(-10, -10, 50, 50));
        assertTrue(clamped.width() >= 0 && clamped.height() >= 0);
    }
}
