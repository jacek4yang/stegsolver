package io.github.jacek4yang.stegsolver.ui;

import javafx.scene.Node;

/**
 * A tool panel shown in the dock next to the image viewport.
 *
 * <p>Panels are created once and reused for every document, so they must tolerate being asked to
 * refresh when nothing is open.</p>
 */
public interface ToolPane {

    /** Tab title. */
    String title();

    /** The panel content. */
    Node content();

    /** Called after a document was opened or closed, and after the viewed transform changed. */
    default void onDocumentChanged() {
    }

    /** Called when the viewport switched between the document and a tool preview. */
    default void onViewChanged() {
    }

    /** Called when the application is shutting down. */
    default void dispose() {
    }
}
