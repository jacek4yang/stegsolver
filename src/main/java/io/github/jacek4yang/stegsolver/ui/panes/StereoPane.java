package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import io.github.jacek4yang.stegsolver.transform.StereoTransform;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.io.IOException;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Stereogram solver: the XOR-shift workflow of the original tool, with a slider and arrow buttons, plus
 * an automatic offset search that the original only hinted at in a TODO comment.
 */
public final class StereoPane implements ToolPane {

    private final MainWindow window;
    private final Label offsetLabel = new Label("Offset: 0");
    private final Slider offsetSlider = new Slider(0, 100, 0);
    private final Spinner<Integer> offsetSpinner = new Spinner<>(0, 100, 0);
    private final CheckBox edgeHold = new CheckBox("Hold the edge (no wrap around seam)");
    private final CheckBox autoSolve = new CheckBox("Solve automatically on open");
    private final Button previousButton = new Button("\u25C0");
    private final Button nextButton = new Button("\u25B6");
    private final Button solveButton = new Button("Find offset");
    private final Button saveButton = new Button("Save...");
    private final Button copyButton = new Button("Copy");

    private ImageData source;
    private ImageData result;
    private int offset;
    private int suggestedOffset = -1;

    public StereoPane(MainWindow window) {
        this.window = window;
        previousButton.setTooltip(new Tooltip("Decrease the offset"));
        previousButton.setOnAction(event -> setOffset(offset - 1));
        nextButton.setTooltip(new Tooltip("Increase the offset"));
        nextButton.setOnAction(event -> setOffset(offset + 1));
        solveButton.setTooltip(new Tooltip("Search for the offset with the strongest self similarity"));
        solveButton.setOnAction(event -> solve());
        offsetSlider.setPrefWidth(200);
        offsetSlider.valueProperty().addListener((observable, old, value) -> {
            if (!offsetSlider.isValueChanging() || Math.abs(value.intValue() - offset) >= 1) {
                setOffset(value.intValue());
            }
        });
        offsetSpinner.setPrefWidth(90);
        offsetSpinner.valueProperty().addListener((observable, old, value) -> setOffset(value));
        edgeHold.setOnAction(event -> recompute());
        saveButton.setOnAction(event -> save());
        copyButton.setOnAction(event -> copy());
        updateButtons();
    }

    @Override
    public String title() {
        return "Stereo";
    }

    @Override
    public Node content() {
        Label hint = new Label("The solver XORs the image with a copy of itself shifted by the offset. "
                + "The hidden depth map appears where the shift matches the pattern width. The result is "
                + "shown in the main view; the open document is not modified.");
        hint.setWrapText(true);
        hint.getStyleClass().add("steg-hint");

        HBox offsetRow = new HBox(6, offsetSpinner, offsetSlider);
        offsetRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10,
                offsetLabel,
                offsetRow,
                new HBox(6, previousButton, nextButton, solveButton),
                edgeHold,
                autoSolve,
                new HBox(6, saveButton, copyButton),
                hint);
        box.setPadding(new Insets(10));
        return box;
    }

    @Override
    public void onDocumentChanged() {
        result = null;
        source = null;
        suggestedOffset = -1;
        updateButtons();
    }

    @Override
    public void onViewChanged() {
        updateButtons();
    }

    /** Searches for the most likely pattern width and jumps to it. */
    private void solve() {
        ImageData image = window.documentImage();
        if (image == null) {
            window.status("Open an image first");
            return;
        }
        source = image;
        window.status("Looking for the repeating pattern width...");
        window.runImageJob("stereo solve", () -> StereoTransform.bestOffset(image, 4), best -> {
            suggestedOffset = best;
            window.status("Suggested offset: " + best + " (press the arrows to refine it)");
            setOffset(best);
        }, error -> window.status("Stereo solve failed: " + error));
    }

    private void setOffset(int value) {
        ImageData image = window.documentImage();
        if (image == null) {
            return;
        }
        source = image;
        int max = Math.max(0, image.width() - 1);
        offset = Math.max(0, Math.min(value, max));
        offsetLabel.setText("Offset: " + offset);
        if (Math.abs(offsetSlider.getValue() - offset) > 0.5) {
            offsetSlider.setValue(offset);
        }
        Integer spinnerValue = offsetSpinner.getValue();
        if (spinnerValue == null || spinnerValue != offset) {
            offsetSpinner.getValueFactory().setValue(offset);
        }
        recompute();
    }

    private void recompute() {
        if (source == null) {
            return;
        }
        ImageData input = source;
        int current = offset;
        boolean hold = edgeHold.isSelected();
        window.runImageJob("stereo " + current,
                () -> hold ? StereoTransform.withEdgeHold(input, current)
                        : StereoTransform.shiftedXor(input, current),
                pixels -> {
                    result = ImageData.opaque(input.width(), input.height(), pixels);
                    window.showPreview(result, "Stereogram offset " + current);
                    updateButtons();
                },
                error -> window.status("Stereogram solve failed: " + error));
    }

    private void updateButtons() {
        saveButton.setDisable(result == null);
        copyButton.setDisable(result == null);
    }

    private void save() {
        if (result == null) {
            return;
        }
        FxUtils.chooseFileToSave(window.window(), "Save the solved stereogram",
                "stereogram-" + offset + ".png").ifPresent(this::write);
    }

    private void write(Path path) {
        try {
            window.status(ImageIoUtil.save(result, path).message(path));
        } catch (IOException e) {
            FxUtils.error(window.window(), "Could not save the image", String.valueOf(e), e);
        }
    }

    private void copy() {
        if (result != null) {
            FxUtils.copyImage(result);
            window.status("Copied the solved stereogram to the clipboard");
        }
    }
}
