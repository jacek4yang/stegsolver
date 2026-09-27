package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import io.github.jacek4yang.stegsolver.transform.CombineMode;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.nio.file.Path;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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

    private ImageData primary;
    private ImageData second;
    private ImageData result;

    public CombinePane(MainWindow window) {
        this.window = window;
        modeChoice.getItems().setAll(CombineMode.values());
        modeChoice.setValue(CombineMode.XOR);
        modeChoice.setOnAction(event -> recompute());
        previousButton.setMinWidth(Region.USE_PREF_SIZE);
        previousButton.setTooltip(new Tooltip("Previous combination mode"));
        previousButton.setOnAction(event -> modeChoice.setValue(CombineMode.previous(modeChoice.getValue())));
        nextButton.setMinWidth(Region.USE_PREF_SIZE);
        nextButton.setTooltip(new Tooltip("Next combination mode"));
        nextButton.setOnAction(event -> modeChoice.setValue(CombineMode.next(modeChoice.getValue())));
        saveButton.setMinWidth(Region.USE_PREF_SIZE);
        saveButton.setOnAction(event -> save());
        copyButton.setMinWidth(Region.USE_PREF_SIZE);
        copyButton.setOnAction(event -> copy());
        clearButton.setMinWidth(Region.USE_PREF_SIZE);
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
        Button loadButton = new Button("Load Second Image...");
        loadButton.setMinWidth(Region.USE_PREF_SIZE);
        loadButton.setOnAction(event -> loadSecond());

        Label hint = new Label("The primary image is the one currently shown in the central view; the second image is "
                + "loaded here. Combining never alters the open document or its file on disk.");
        hint.setWrapText(true);
        hint.getStyleClass().add("steg-hint");

        secondLabel.setWrapText(true);
        secondLabel.getStyleClass().add("steg-hint");

        FlowPane secondRow = new FlowPane(6, 6, loadButton, clearButton);
        secondRow.setAlignment(Pos.CENTER_LEFT);

        VBox secondCard = new VBox(8,
                new Label("Secondary Image"),
                secondRow,
                secondLabel);
        secondCard.getStyleClass().add("steg-card");

        modeLabel.getStyleClass().addAll("mono", "steg-transform-label");
        modeChoice.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modeChoice, Priority.ALWAYS);
        HBox modeRow = new HBox(6, previousButton, modeChoice, nextButton);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        VBox modeCard = new VBox(8,
                new Label("Combination Mode"),
                modeLabel,
                modeRow);
        modeCard.getStyleClass().add("steg-card");

        FlowPane actionRow = new FlowPane(8, 6, saveButton, copyButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox actionCard = new VBox(8,
                new Label("Export & Save"),
                actionRow,
                hint);
        actionCard.getStyleClass().add("steg-card");

        VBox box = new VBox(10, secondCard, modeCard, actionCard);
        box.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPadding(new Insets(0));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    @Override
    public void onDocumentChanged() {
        primary = null;
        result = null;
        updateButtons();
    }

    @Override
    public void onViewChanged() {
        updateButtons();
    }

    private void loadSecond() {
        Optional<Path> chosen = window.chooseImage("Load the second image");
        ImageData selectedPrimary = window.displayedImage();
        chosen.ifPresent(path -> window.runImageJob("load second image", () -> ImageIoUtil.load(path),
                loaded -> {
                    primary = selectedPrimary;
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
        window.cancelToolJobs();
        primary = null;
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
        if (primary == null) primary = window.displayedImage();
        ImageData first = primary;
        if (first == null || second == null) {
            updateButtons();
            return;
        }
        CombineMode mode = modeChoice.getValue();
        ImageData secondImage = second;
        window.runImageJob("combine " + mode,
                () -> mode.combine(first, secondImage),
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
        ImageData saved = result;
        FxUtils.chooseFileToSave(window.window(), "Save the combined image", suggestion)
                .ifPresent(path -> window.saveImage(saved, path));
    }

    private void copy() {
        if (result != null) {
            FxUtils.copyImage(result);
            window.status("Copied the combined image to the clipboard");
        }
    }
}
