package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.core.FrameSource;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Frame browser for multi frame images.
 *
 * <p>Frames are decoded on demand and only a handful are kept in memory ({@link FrameSource}), so opening
 * a large animation shows the first frame immediately instead of decoding everything up front. The
 * thumbnail strip is a virtualised list whose thumbnails are produced in the background as they are
 * scrolled into view.</p>
 */
public final class FrameBrowserPane implements ToolPane {

    private static final int THUMBNAIL_SIZE = 72;

    private final MainWindow window;
    private final Label status = new Label("No frame source loaded");
    private final Spinner<Integer> frameSpinner = new Spinner<>(1, 1, 1);
    private final ListView<Integer> thumbnails = new ListView<>();
    private final ObservableList<Integer> frameIndexes = FXCollections.observableArrayList();
    private final Button previousButton = new Button("\u25C0");
    private final Button nextButton = new Button("\u25B6");
    private final Button saveButton = new Button("Save frame...");
    private final Button openAsDocumentButton = new Button("Open as document");
    private final Button loadButton = new Button("Load frames...");
    private final Button useDocumentButton = new Button("Use the open file");

    private FrameSource source;
    private int currentFrame;

    public FrameBrowserPane(MainWindow window) {
        this.window = window;
        thumbnails.setItems(frameIndexes);
        thumbnails.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        thumbnails.setPrefHeight(THUMBNAIL_SIZE + 42);
        thumbnails.setCellFactory(view -> new ThumbnailCell());
        thumbnails.getSelectionModel().selectedItemProperty().addListener((observable, old, index) -> {
            if (index != null) {
                showFrame(index);
            }
        });
        previousButton.setOnAction(event -> showFrame(currentFrame - 1));
        nextButton.setOnAction(event -> showFrame(currentFrame + 1));
        saveButton.setOnAction(event -> saveFrame());
        openAsDocumentButton.setOnAction(event -> openFrameAsDocument());
        loadButton.setOnAction(event -> chooseFileToLoad());
        useDocumentButton.setOnAction(event -> loadFromDocument());
        frameSpinner.valueProperty().addListener((observable, old, value) -> {
            if (value != null && value - 1 != currentFrame) {
                showFrame(value - 1);
            }
        });
        updateButtons();
    }

    @Override
    public String title() {
        return "Frames";
    }

    @Override
    public Node content() {
        Label hint = new Label("Frames are loaded lazily: only the frames you look at are decoded and kept "
                + "in memory.");
        hint.setWrapText(true);
        hint.getStyleClass().add("steg-hint");

        HBox navigation = new HBox(6, previousButton, frameSpinner, nextButton);
        navigation.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10,
                new HBox(6, useDocumentButton, loadButton),
                status,
                navigation,
                new HBox(6, saveButton, openAsDocumentButton),
                thumbnails,
                hint);
        box.setPadding(new Insets(10));
        return box;
    }

    @Override
    public void onDocumentChanged() {
        updateButtons();
    }

    @Override
    public void dispose() {
        closeSource();
    }

    private void closeSource() {
        if (source != null) {
            source.close();
            source = null;
        }
        frameIndexes.clear();
        currentFrame = 0;
        status.setText("No frame source loaded");
        updateButtons();
    }

    private void loadFromDocument() {
        Path path = window.document().path();
        if (path == null) {
            window.status("Open an image from a file first, or use \"Load frames...\"");
            return;
        }
        load(path);
    }

    private void chooseFileToLoad() {
        FxUtils.chooseImageToOpen(window.window(), "Open a multi frame image")
                .ifPresent(this::load);
    }

    private void load(Path path) {
        closeSource();
        try {
            source = FrameSource.open(path);
        } catch (IOException e) {
            FxUtils.error(window.window(), "Could not read the frames", String.valueOf(e), e);
            return;
        }
        int count = source.frameCount();
        if (count > 0) {
            frameIndexes.setAll(java.util.stream.IntStream.range(0, count).boxed().toList());
            frameSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, count, 1));
            status.setText(path.getFileName() + " \u00b7 " + count + " frame(s) \u00b7 loaded lazily");
        } else {
            // The reader cannot count the frames up front: discover them by probing.
            int discovered = discoverFrameCount();
            frameIndexes.setAll(java.util.stream.IntStream.range(0, discovered).boxed().toList());
            frameSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1,
                    Math.max(1, discovered), 1));
            status.setText(path.getFileName() + " \u00b7 " + discovered + " frame(s) discovered by probing");
        }
        updateButtons();
        if (!frameIndexes.isEmpty()) {
            thumbnails.getSelectionModel().select(0);
        }
        window.status("Loaded " + path.getFileName() + " with " + frameIndexes.size() + " frame(s)");
    }

    /** Probes frames one at a time for readers that cannot report the frame count. */
    private int discoverFrameCount() {
        int index = 0;
        int limit = 10_000;
        while (index < limit) {
            try {
                source.frame(index);
                index++;
            } catch (IOException e) {
                break;
            }
        }
        return index;
    }

    private void showFrame(int index) {
        if (source == null || frameIndexes.isEmpty()) {
            return;
        }
        int count = frameIndexes.size();
        int wrapped = ((index % count) + count) % count;
        currentFrame = wrapped;
        frameSpinner.getValueFactory().setValue(wrapped + 1);
        thumbnails.getSelectionModel().select(wrapped);
        int frameIndex = wrapped;
        window.runImageJob("frame " + frameIndex, () -> source.frame(frameIndex), frame -> {
            window.showPreview(frame, "Frame " + (frameIndex + 1) + " of " + count);
            status.setText("Frame " + (frameIndex + 1) + " of " + count + " \u00b7 " + frame.width() + "x"
                    + frame.height() + " \u00b7 " + source.cachedFrameCount() + " frame(s) in memory");
            updateButtons();
        }, error -> window.status("Could not decode the frame: " + error));
    }

    private void saveFrame() {
        if (source == null) {
            return;
        }
        int frameIndex = currentFrame;
        FxUtils.chooseFileToSave(window.window(), "Save frame", "frame" + (frameIndex + 1) + ".png")
                .ifPresent(path -> window.runImageJob("save frame " + frameIndex,
                        () -> ImageIoUtil.save(source.frame(frameIndex), path),
                        outcome -> window.status(outcome.message(path)),
                        error -> FxUtils.error(window.window(), "Could not save the frame",
                                String.valueOf(error), error)));
    }

    private void openFrameAsDocument() {
        if (source == null) {
            return;
        }
        int frameIndex = currentFrame;
        int count = frameIndexes.size();
        window.runImageJob("frame as document", () -> source.frame(frameIndex), frame -> {
            window.document().openFromImage(frame, "frame " + (frameIndex + 1) + " of "
                    + source.path().getFileName());
            window.showDocument();
            window.status("Opened frame " + (frameIndex + 1) + " of " + count + " as the document");
        }, error -> FxUtils.error(window.window(), "Could not open the frame", String.valueOf(error),
                error));
    }

    private void updateButtons() {
        boolean loaded = source != null && !frameIndexes.isEmpty();
        previousButton.setDisable(!loaded);
        nextButton.setDisable(!loaded);
        saveButton.setDisable(!loaded);
        openAsDocumentButton.setDisable(!loaded);
        frameSpinner.setDisable(!loaded);
        thumbnails.setDisable(!loaded);
        useDocumentButton.setDisable(!window.document().isOpen());
    }

    /** A virtualised thumbnail cell that decodes its frame in the background and caches it. */
    private final class ThumbnailCell extends ListCell<Integer> {

        private final ImageView image = new ImageView();
        private final Label caption = new Label();

        ThumbnailCell() {
            image.setFitWidth(THUMBNAIL_SIZE);
            image.setFitHeight(THUMBNAIL_SIZE);
            image.setPreserveRatio(true);
            caption.getStyleClass().add("steg-hint");
            VBox box = new VBox(2, image, caption);
            box.setAlignment(Pos.CENTER);
            setGraphic(box);
            setTooltip(new Tooltip("Click to show this frame"));
        }

        @Override
        protected void updateItem(Integer index, boolean empty) {
            super.updateItem(index, empty);
            if (empty || index == null || source == null) {
                image.setImage(null);
                caption.setText("");
                return;
            }
            caption.setText("#" + (index + 1));
            int frameIndex = index;
            window.runImageJob("thumbnail " + frameIndex,
                    () -> source.thumbnail(frameIndex, THUMBNAIL_SIZE * 2),
                    thumbnail -> {
                        if (getItem() != null && getItem() == frameIndex) {
                            image.setImage(FxUtils.toFxImage(thumbnail));
                        }
                    },
                    error -> caption.setText("#" + (frameIndex + 1) + " failed"));
        }
    }

    /** Exposes the currently loaded frame data, used by tests and diagnostics. */
    public Optional<ImageData> currentFrameImage() {
        if (source == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(source.frame(currentFrame));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
