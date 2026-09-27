package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import io.github.jacek4yang.stegsolver.transform.CombineMode;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Image combiner: the 13 combination modes of the original tool.
 *
 * <p>The second image is loaded into the panel and the result is shown in the main view, so the open
 * document — and above all its file — is never touched. The result can be saved or copied like any other
 * image.</p>
 */
public final class CombinePane implements ToolPane {

    private final MainWindow window;
    private final Label secondLabel = new Label("No second image loaded");
    private final Label modeLabel = new Label();
    private final ComboBox<CombineMode> modeChoice = new ComboBox<>();
    private final Button previousButton = new Button("\u25C0");
    private final Button nextButton = new Button("\u25B6");
    private final Button saveButton = new Button("Save...");
    private final Button copyButton = new Button("Copy");
    private final Button clearButton = new Button("Unload");

    private ImageData second;
    private ImageData result;

    public CombinePane(MainWindow window) {
        this.window = window;
        modeChoice.getItems().setAll(CombineMode.values());
        modeChoice.setValue(CombineMode.XOR);
        modeChoice.setOnAction(event -> recompute());
        previousButton.setTooltip(new Tooltip("Previous combination mode"));
        previousButton.setOnAction(event -> modeChoice.setValue(CombineMode.previous(modeChoice.getValue())));
        nextButton.setTooltip(new Tooltip("Next combination mode"));
        nextButton.setOnAction(event -> modeChoice.setValue(CombineMode.next(modeChoice.getValue())));
        saveButton.setOnAction(event -> save());
        copyButton.setOnAction(event -> copy());
        clearButton.setOnAction(event -> unload());
        secondLabel.setWrapText(true);
        updateModeLabel();
        updateButtons();
    }

    @Override
    public String title() {
        return "Combine";
    }

    @Override
    public Node content() {
        Button loadButton = new Button("Load second image...");
        loadButton.setOnAction(event -> loadSecond());
        Label hint = new Label("The first image is the one currently shown in the main view; the second is "
                + "loaded here. Combining never changes the open document or its file.");
        hint.setWrapText(true);
        hint.getStyleClass().add("steg-hint");

        HBox modeRow = new HBox(6, previousButton, modeChoice, nextButton);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10,
                loadButton,
                secondLabel,
                clearButton,
                modeLabel,
                modeRow,
                new HBox(6, saveButton, copyButton),
                hint);
        box.setPadding(new Insets(10));
        return box;
    }

    @Override
    public void onDocumentChanged() {
        result = null;
        updateButtons();
    }

    @Override
    public void onViewChanged() {
        updateButtons();
    }

    private void loadSecond() {
        Optional<Path> chosen = window.chooseImage("Load the second image");
        chosen.ifPresent(path -> window.runImageJob("load second image", () -> ImageIoUtil.load(path),
                loaded -> {
                    second = loaded;
                    secondLabel.setText("Second image: " + path.getFileName() + " ("
                            + loaded.width() + "x" + loaded.height() + ")");
                    window.status("Loaded " + path.getFileName() + " for combining");
                    recompute();
                },
                error -> FxUtils.error(window.window(), "Could not load the second image",
                        String.valueOf(error), error)));
    }

    private void unload() {
        second = null;
        result = null;
        secondLabel.setText("No second image loaded");
        updateButtons();
        window.status("Second image unloaded");
    }

    private void updateModeLabel() {
        CombineMode mode = modeChoice.getValue();
        modeLabel.setText(mode == null ? "" : "Mode " + (mode.legacyIndex() + 1) + " of "
                + CombineMode.values().length);
    }

    private void recompute() {
        updateModeLabel();
        ImageData first = window.displayedImage();
        if (first == null || second == null) {
            updateButtons();
            return;
        }
        CombineMode mode = modeChoice.getValue();
        window.runImageJob("combine " + mode,
                () -> mode.combine(first, second),
                combined -> {
                    result = combined;
                    window.showPreview(combined, "Combine " + mode.label());
                    updateButtons();
                    window.status("Combined " + mode.label() + " \u2192 " + combined.width() + "x"
                            + combined.height());
                },
                error -> window.status("Combining failed: " + error));
    }

    private void updateButtons() {
        boolean enabled = result != null;
        saveButton.setDisable(!enabled);
        copyButton.setDisable(!enabled);
        clearButton.setDisable(second == null);
    }

    private void save() {
        if (result == null) {
            return;
        }
        String suggestion = "combined-" + modeChoice.getValue().name().toLowerCase(java.util.Locale.ROOT)
                + ".png";
        FxUtils.chooseFileToSave(window.window(), "Save the combined image", suggestion).ifPresent(path -> {
            try {
                window.status(ImageIoUtil.save(result, path).message(path));
            } catch (IOException e) {
                FxUtils.error(window.window(), "Could not save the image", String.valueOf(e), e);
            }
        });
    }

    private void copy() {
        if (result != null) {
            FxUtils.copyImage(result);
            window.status("Copied the combined image to the clipboard");
        }
    }
}
