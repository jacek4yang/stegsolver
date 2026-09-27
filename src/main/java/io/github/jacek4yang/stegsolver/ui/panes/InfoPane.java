package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.PreviewHost;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Image information: the facts about the open file, the current view, the pixel under the cursor and the
 * selected region. This replaces the "width/height explorer" that the original tool only had as a TODO.
 */
public final class InfoPane implements ToolPane {

    private final MainWindow window;
    private final PreviewHost host;
    private final GridPane fileGrid = grid();
    private final GridPane viewGrid = grid();
    private final GridPane pixelGrid = grid();
    private final Label pixelBits = new Label(" ");
    private final Label selectionLabel = new Label("none");

    public InfoPane(MainWindow window) {
        this.window = window;
        this.host = window;
        pixelBits.getStyleClass().add("mono");
    }

    @Override
    public String title() {
        return "Info";
    }

    @Override
    public Node content() {
        Label hint = new Label("Move the mouse over the image to inspect pixels; the bit rows show the eight "
                + "bits of each channel of the source pixel, which is where hidden data usually lives.");
        hint.getStyleClass().add("steg-hint");
        hint.setWrapText(true);

        VBox box = new VBox(14,
                section("Document", fileGrid),
                section("Current view", viewGrid),
                section("Pixel inspector", pixelGrid, pixelBits, hint),
                section("Selected region", selectionLabel));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(10));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        return grid;
    }

    private static VBox section(String title, Node... children) {
        Label header = new Label(title);
        header.getStyleClass().add("steg-tool-header");
        VBox box = new VBox(4);
        box.getChildren().add(header);
        box.getChildren().addAll(children);
        return box;
    }

    @Override
    public void onDocumentChanged() {
        refresh();
    }

    @Override
    public void onViewChanged() {
        refresh();
    }

    /** Rebuilds the panel; safe to call at any time. */
    public void refresh() {
        fileGrid.getChildren().clear();
        viewGrid.getChildren().clear();
        pixelGrid.getChildren().clear();

        if (!window.document().isOpen()) {
            row(fileGrid, 0, "Document", "no image open");
            row(viewGrid, 0, "View", host.isShowingPreview() ? window.displayedLabel() : "-");
            row(pixelGrid, 0, "Pixel", "-");
            pixelBits.setText(" ");
            selectionLabel.setText(describeSelection(window.selection()));
            return;
        }

        ImageData image = window.document().image();
        int row = 0;
        row(fileGrid, row++, "File", window.document().fileName());
        row(fileGrid, row++, "Location", String.valueOf(window.document().path()));
        row(fileGrid, row++, "File size", FxUtils.bytes(window.document().loadedBytes()));
        row(fileGrid, row++, "Dimensions", image.width() + " x " + image.height()
                + " (" + (long) image.width() * image.height() + " pixels)");
        row(fileGrid, row++, "Alpha channel", image.hasAlpha() ? "present" : "absent");
        row(fileGrid, row++, "Indexed palette", image.isIndexed() ? "yes" : "no");
        row(fileGrid, row++, "Pixel array", FxUtils.bytes(image.estimatedBytes()));
        row(fileGrid, row++, "Transform cache", window.document().engine().cachedTransformCount()
                + " transforms, " + FxUtils.bytes(window.document().engine().cachedBytes()));

        var transform = window.document().transform();
        row(viewGrid, 0, "Transform", transform.describe());
        row(viewGrid, 1, "Group", transform.group());
        row(viewGrid, 2, "Operation", transform.kind().label());
        if (host.isShowingPreview()) {
            row(viewGrid, 3, "Tool preview", window.displayedLabel() + " (the document is unchanged)");
        }
        row(viewGrid, 4, "Barcode results", host.lastScan() == null ? "none yet" : host.lastScan().summary());

        int x = window.hoveredImageX();
        int y = window.hoveredImageY();
        if (x < 0 || y < 0) {
            row(pixelGrid, 0, "Pixel", "move the mouse over the image");
            pixelBits.setText(" ");
        } else {
            int source = image.argbAt(x, y);
            int displayed = window.viewport().viewPixelAt(x, y);
            row(pixelGrid, 0, "Position", "x=" + x + " y=" + y);
            row(pixelGrid, 1, "Source ARGB", FxUtils.argbHex(source));
            row(pixelGrid, 2, "Source channels", FxUtils.channels(source));
            row(pixelGrid, 3, "Displayed ARGB", FxUtils.argbHex(displayed));
            pixelBits.setText(FxUtils.bitPlanes(source));
        }
        selectionLabel.setText(describeSelection(window.selection()));
        selectionLabel.getStyleClass().setAll("mono");
    }

    private static void row(GridPane grid, int row, String label, String value) {
        Label name = new Label(label);
        name.setMinWidth(110);
        Label text = new Label(value);
        text.setWrapText(true);
        grid.add(name, 0, row);
        grid.add(text, 1, row);
    }

    /** Describes a region in image coordinates, as shown in the panel and the status bar. */
    public static String describeSelection(Roi roi) {
        if (roi == null || roi.isEmpty()) {
            return "none";
        }
        return String.format(Locale.ROOT, "%s (%d pixels)", roi.describe(), roi.area());
    }
}
