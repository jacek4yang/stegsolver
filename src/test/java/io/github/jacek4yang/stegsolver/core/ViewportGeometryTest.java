package io.github.jacek4yang.stegsolver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ViewportGeometryTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("zoom keeps the image point under the cursor in place")
    void zoomAnchorsAtCursor() {
        ViewportGeometry view = new ViewportGeometry(1000, 800);
        view.setViewportSize(400, 300);
        view.actualSize();
        view.setPan(0, 0);
        view.zoomAt(200, 150, 4.0);
        assertEquals(4.0, view.zoom(), EPSILON);
        // The image point that was under (200,150) before the zoom is still under it.
        assertEquals(200, view.toImageX(200), EPSILON);
        assertEquals(150, view.toImageY(150), EPSILON);
    }

    @Test
    @DisplayName("fit never magnifies past 100 percent and centres the image")
    void fit() {
        ViewportGeometry large = new ViewportGeometry(2000, 1000);
        large.setViewportSize(500, 500);
        large.fit();
        assertEquals(0.25, large.zoom(), EPSILON);
        assertEquals(0, large.panX(), EPSILON);
        assertEquals(125, large.panY(), EPSILON);

        ViewportGeometry small = new ViewportGeometry(100, 50);
        small.setViewportSize(500, 500);
        small.fit();
        assertEquals(1.0, small.zoom(), EPSILON);
        assertEquals(200, small.panX(), EPSILON);
        assertEquals(225, small.panY(), EPSILON);
    }

    @Test
    @DisplayName("panning cannot move the image away from the viewport edges")
    void panClamping() {
        ViewportGeometry view = new ViewportGeometry(1000, 800);
        view.setViewportSize(400, 300);
        view.actualSize();
        view.setPan(1000, 1000);
        assertEquals(0, view.panX(), EPSILON);
        assertEquals(0, view.panY(), EPSILON);
        view.setPan(-1000, -1000);
        assertEquals(400 - 1000, view.panX(), EPSILON);
        assertEquals(300 - 800, view.panY(), EPSILON);
        // A smaller image stays centred.
        ViewportGeometry small = new ViewportGeometry(100, 100);
        small.setViewportSize(400, 300);
        small.actualSize();
        small.panBy(500, 500);
        assertEquals(150, small.panX(), EPSILON);
        assertEquals(100, small.panY(), EPSILON);
    }

    @Test
    @DisplayName("view coordinates map to the right image pixel and are clamped to the image")
    void picking() {
        ViewportGeometry view = new ViewportGeometry(100, 100);
        view.setViewportSize(300, 300);
        view.actualSize();
        // The image is centred: 100x100 image inside 300x300 viewport, so the origin is at 100,100.
        assertEquals(0, view.pickImageX(100));
        assertEquals(0, view.pickImageX(100.9));
        assertEquals(1, view.pickImageX(101));
        assertEquals(99, view.pickImageX(199));
        assertEquals(0, view.pickImageX(-50));
        assertEquals(99, view.pickImageX(5000));
        assertTrue(view.isInsideImage(150, 150));
        assertFalse(view.isInsideImage(50, 150));
    }

    @Test
    @DisplayName("a dragged rectangle maps to image coordinates in any drag direction")
    void roiMapping() {
        ViewportGeometry view = new ViewportGeometry(1000, 1000);
        view.setViewportSize(200, 200);
        view.zoomAt(0, 0, 0.2); // zoom to 20 percent
        view.setPan(0, 0);
        assertEquals(0.2, view.zoom(), EPSILON);

        // Drag from view (0,0) to (40,20) => image (0,0) to (200,100)
        Roi forward = view.viewRectToImageRoi(0, 0, 40, 20);
        assertEquals(new Roi(0, 0, 200, 100), forward);

        // The same rectangle dragged backwards gives the same region.
        assertEquals(forward, view.viewRectToImageRoi(40, 20, 0, 0));
        assertEquals(forward, view.viewRectToImageRoi(40, 0, 0, 20));
    }

    @Test
    @DisplayName("a dragged rectangle that leaves the viewport is clamped to the image")
    void roiMappingIsClamped() {
        ViewportGeometry view = new ViewportGeometry(100, 80);
        view.setViewportSize(100, 80);
        view.actualSize();
        Roi roi = view.viewRectToImageRoi(-50, -50, 500, 500);
        assertEquals(new Roi(0, 0, 100, 80), roi);

        Roi partial = view.viewRectToImageRoi(-10, -10, 30, 20);
        assertEquals(new Roi(0, 0, 30, 20), partial);

        Roi outside = view.viewRectToImageRoi(-100, -100, -10, -10);
        assertTrue(outside.isEmpty());
    }

    @Test
    @DisplayName("mapping after panning accounts for the translation")
    void roiMappingWithPan() {
        ViewportGeometry view = new ViewportGeometry(200, 200);
        view.setViewportSize(100, 100);
        view.actualSize();
        view.setPan(-50, -20);
        // View (0,0) is image (50,20); drag 10x10 view pixels = 10x10 image pixels at 100 percent zoom.
        assertEquals(new Roi(50, 20, 10, 10), view.viewRectToImageRoi(0, 0, 10, 10));
    }

    @Test
    @DisplayName("zoom limits are enforced")
    void zoomLimits() {
        ViewportGeometry view = new ViewportGeometry(100, 100);
        view.setViewportSize(100, 100);
        view.setZoom(1000);
        assertEquals(ViewportGeometry.MAX_ZOOM, view.zoom(), EPSILON);
        view.setZoom(0.0001);
        assertEquals(ViewportGeometry.MIN_ZOOM, view.zoom(), EPSILON);
        assertEquals(1.0, ViewportGeometry.clampZoom(Double.NaN), EPSILON);
        assertEquals(1.0, ViewportGeometry.clampZoom(-3), EPSILON);
    }

    @Test
    @DisplayName("switching to another image resets zoom and centres the new content")
    void switchingImages() {
        ViewportGeometry view = new ViewportGeometry(1000, 1000);
        view.setViewportSize(500, 500);
        view.setZoom(5);
        view.setImage(200, 100);
        assertEquals(200, view.imageWidth());
        assertEquals(100, view.imageHeight());
        assertEquals(1.0, view.zoom(), EPSILON);
        assertEquals(150, view.panX(), EPSILON);
        assertEquals(200, view.panY(), EPSILON);
    }

    @Test
    @DisplayName("the visible region is reported in image coordinates")
    void visibleRegion() {
        ViewportGeometry view = new ViewportGeometry(1000, 1000);
        view.setViewportSize(100, 100);
        view.zoomAt(0, 0, 1.0);
        view.setPan(-200, -300);
        assertEquals(new Roi(200, 300, 100, 100), view.visibleImageRoi());
    }
}
