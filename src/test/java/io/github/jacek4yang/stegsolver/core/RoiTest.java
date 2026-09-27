package io.github.jacek4yang.stegsolver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoiTest {

    @Test
    @DisplayName("a drag in any direction produces a normalised region")
    void fromCorners() {
        assertEquals(new Roi(2, 3, 4, 5), Roi.fromCorners(2, 3, 6, 8));
        assertEquals(new Roi(2, 3, 4, 5), Roi.fromCorners(6, 8, 2, 3));
        assertEquals(new Roi(2, 3, 4, 5), Roi.fromCorners(6, 3, 2, 8));
    }

    @Test
    @DisplayName("clamping keeps a region inside the image and reports empty when disjoint")
    void clamping() {
        assertEquals(new Roi(0, 0, 10, 10), Roi.clamp(-5, -5, 20, 20, 10, 10));
        assertEquals(new Roi(8, 8, 2, 2), Roi.clamp(8, 8, 10, 10, 10, 10));
        assertTrue(Roi.clamp(20, 20, 5, 5, 10, 10).isEmpty());
        assertTrue(Roi.clamp(0, 0, 0, 5, 10, 10).isEmpty());
        assertTrue(Roi.clamp(0, 0, 5, 5, 0, 0).isEmpty());
    }

    @Test
    @DisplayName("intersection, containment and expansion behave as expected")
    void operations() {
        Roi left = new Roi(0, 0, 5, 5);
        Roi right = new Roi(4, 4, 5, 5);
        assertEquals(new Roi(4, 4, 1, 1), left.intersect(right));
        assertTrue(left.intersect(new Roi(10, 10, 2, 2)).isEmpty());
        assertTrue(left.contains(0, 0));
        assertTrue(left.contains(4, 4));
        assertFalse(left.contains(5, 5));
        assertEquals(new Roi(0, 0, 7, 7), left.expand(2, 10, 10));
        assertEquals(new Roi(0, 0, 5, 5), new Roi(1, 1, 3, 3).expand(1, 5, 5));
        assertEquals(25, left.area());
        assertEquals("0,0 5x5", left.describe());
        assertEquals(5, left.maxX());
        assertEquals(5, left.maxY());
    }

    @Test
    @DisplayName("negative sizes are rejected early")
    void negativeSizes() {
        assertThrows(IllegalArgumentException.class, () -> new Roi(0, 0, -1, 5));
        assertThrows(IllegalArgumentException.class, () -> new Roi(0, 0, 5, -1));
    }

    @Test
    @DisplayName("scaling keeps at least one pixel")
    void scaling() {
        assertEquals(new Roi(1, 2, 4, 6), new Roi(1, 2, 2, 3).scaled(2));
        assertEquals(new Roi(0, 0, 1, 1), new Roi(0, 0, 1, 1).scaled(0.1));
    }
}
