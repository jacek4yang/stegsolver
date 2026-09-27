package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.barcode.ScanResult;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import javafx.stage.Window;

/**
 * What the tool panels need from the application shell.
 *
 * <p>A tiny interface instead of a framework: the panels (extract, barcode, analysis, stereo, combine,
 * frames) receive this and can ask for the current image, show a preview in the central viewport and
 * report status, without knowing anything about the window itself.</p>
 */
public interface PreviewHost {

    /** The image the user is currently looking at (the viewed transform, or a tool preview). */
    ImageData displayedImage();

    /** The untouched image of the open document. */
    ImageData documentImage();

    /** The region selected in the viewport, or {@code null} when nothing is selected. */
    Roi selection();

    /** Shows an image in the central viewport without touching the document. */
    void showPreview(ImageData data, String label);

    /** Returns the central viewport to the document's current transform. */
    void showDocument();

    /** True when a tool preview is currently displayed. */
    boolean isShowingPreview();

    /** The owning window, for dialogs. */
    Window window();

    /** Message in the status bar; {@code null} clears it. */
    void status(String message);

    /** Records the outcome of a barcode scan so the other panels and the overlay stay in sync. */
    void reportScan(ScanResult result);

    /** The last barcode scan result. */
    ScanResult lastScan();

    /** Runs a barcode scan of a region in the background. */
    void scanRegion(Roi region, boolean thorough);
}
