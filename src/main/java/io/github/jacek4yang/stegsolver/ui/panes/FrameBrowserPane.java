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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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

    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stegsolver-frames");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.Set<FrameSource> openSources = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean updatingSelection;
    private volatile long generation;
    private volatile long frameRequest;
    private volatile boolean disposed;
    private FrameSource source;
    private int currentFrame = -1;

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
        previousButton.setMinWidth(Region.USE_PREF_SIZE);
        previousButton.setOnAction(event -> showFrame(currentFrame - 1));
        nextButton.setMinWidth(Region.USE_PREF_SIZE);
        nextButton.setOnAction(event -> showFrame(currentFrame + 1));
        saveButton.setMinWidth(Region.USE_PREF_SIZE);
        saveButton.setOnAction(event -> saveFrame());
        openAsDocumentButton.setMinWidth(Region.USE_PREF_SIZE);
        openAsDocumentButton.setOnAction(event -> openFrameAsDocument());
        loadButton.setMinWidth(Region.USE_PREF_SIZE);
        loadButton.setOnAction(event -> chooseFileToLoad());
        useDocumentButton.setMinWidth(Region.USE_PREF_SIZE);
        useDocumentButton.setOnAction(event -> loadFromDocument());
        frameSpinner.setMinWidth(Region.USE_PREF_SIZE);
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
        Label hint = new Label("Frames are decoded on demand: only the frames you inspect are kept in memory.");
        hint.setWrapText(true);
        hint.getStyleClass().add("steg-hint");

        status.setWrapText(true);
        status.getStyleClass().addAll("mono", "steg-transform-label");

        FlowPane sourceButtons = new FlowPane(6, 6, useDocumentButton, loadButton);
        sourceButtons.setAlignment(Pos.CENTER_LEFT);

        VBox sourceCard = new VBox(8,
                new Label("Frame Source"),
                sourceButtons,
                status);
        sourceCard.getStyleClass().add("steg-card");

        HBox navigation = new HBox(6, previousButton, frameSpinner, nextButton);
        navigation.setAlignment(Pos.CENTER_LEFT);

        FlowPane frameActions = new FlowPane(6, 6, saveButton, openAsDocumentButton);
        frameActions.setAlignment(Pos.CENTER_LEFT);

        VBox navCard = new VBox(8,
                new Label("Frame Navigation"),
                navigation,
                frameActions,
                thumbnails,
                hint);
        navCard.getStyleClass().add("steg-card");

        VBox box = new VBox(10, sourceCard, navCard);
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
        frameRequest++;
        updateButtons();
    }

    @Override
    public void dispose() {
        closeSource();
        disposed = true;
        worker.shutdown();
    }

    private void closeSource() {
        generation++;
        frameRequest++;
        source = null;
        if (!disposed) worker.execute(() -> {
            for (FrameSource old : openSources) old.close();
            openSources.clear();
        });
        frameIndexes.clear();
        currentFrame = -1;
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
        long request = generation;
        status.setText("Reading frame headers...");
        worker.execute(() -> {
            try {
                FrameSource opened = FrameSource.open(path);
                openSources.add(opened);
                if (disposed || request != generation) {
                    opened.close();
                    openSources.remove(opened);
                    return;
                }
                javafx.application.Platform.runLater(() -> {
                    if (disposed || request != generation) {
                        // The source has no active reads yet, so closing it here cannot block.
                        opened.close();
                        openSources.remove(opened);
                        return;
                    }
                    source = opened;
                    installSource(path);
                });
            } catch (IOException | RuntimeException error) {
                javafx.application.Platform.runLater(() -> {
                    if (!disposed && request == generation)
                        window.status("Could not read the frames: " + error);
                });
            }
        });
    }

    private void installSource(Path path) {
        int count = source.frameCount();
        if (count > 0) {
            frameIndexes.setAll(java.util.stream.IntStream.range(0, count).boxed().toList());
            frameSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, count, 1));
            status.setText(path.getFileName() + " \u00b7 " + count + " frame(s) \u00b7 loaded lazily");
        } else {
            // Unknown-count readers remain usable without eagerly decoding every frame.
            frameIndexes.setAll(0);
            frameSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1, 1));
            status.setText(path.getFileName() + " - frame count unavailable");
        }
        updateButtons();
        if (!frameIndexes.isEmpty()) {
            thumbnails.getSelectionModel().select(0);
        }
        window.status("Loaded " + path.getFileName() + " with " + frameIndexes.size() + " frame(s)");
    }

    private void showFrame(int index) {
        if (updatingSelection || source == null || frameIndexes.isEmpty()) {
            return;
        }
        if (!source.hasKnownFrameCount() && index == frameIndexes.size()) {
            FrameSource input = source;
            submit(() -> input.frame(index), () -> true, frame -> {
                frameIndexes.add(index);
                frameSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, frameIndexes.size(), index + 1));
                showFrame(index);
            }, error -> window.status("No further frame: " + error.getMessage()));
            return;
        }
        int count = frameIndexes.size();
        int wrapped = ((index % count) + count) % count;
        currentFrame = wrapped;
        updatingSelection = true;
        try {
            frameSpinner.getValueFactory().setValue(wrapped + 1);
            thumbnails.getSelectionModel().select(wrapped);
        } finally {
            updatingSelection = false;
        }
        int frameIndex = wrapped;
        FrameSource input = source;
        long request = ++frameRequest;
        submit(() -> input.frame(frameIndex), () -> request == frameRequest, frame -> {
            window.showPreview(frame, "Frame " + (frameIndex + 1) + " of " + count);
            status.setText("Frame " + (frameIndex + 1) + " of " + count + " \u00b7 " + frame.width() + "x"
                    + frame.height() + " \u00b7 " + input.cachedFrameCount() + " frame(s) in memory");
            updateButtons();
        }, error -> window.status("Could not decode the frame: " + error));
    }

    private void saveFrame() {
        if (source == null) {
            return;
        }
        int frameIndex = currentFrame;
        FrameSource input = source;
        FxUtils.chooseFileToSave(window.window(), "Save frame", "frame" + (frameIndex + 1) + ".png")
                .ifPresent(path -> submit(
                        () -> ImageIoUtil.save(input.frame(frameIndex), path), () -> true,
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
        FrameSource input = source;
        submit(() -> input.frame(frameIndex), () -> true, frame -> {
            window.document().openFromImage(frame, "frame " + (frameIndex + 1) + " of "
                    + input.path().getFileName());
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
        useDocumentButton.setDisable(window == null || !window.document().isOpen());
    }

    /** A virtualised thumbnail cell that decodes its frame in the background and caches it. */
    private final class ThumbnailCell extends ListCell<Integer> {

        private final ImageView image = new ImageView();
        private volatile long request;
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
            long ticket = ++request;
            image.setImage(null);
            if (empty || index == null || source == null) {
                image.setImage(null);
                caption.setText("");
                return;
            }
            caption.setText("#" + (index + 1));
            int frameIndex = index;
            FrameSource input = source;
            submit(() -> input.thumbnail(frameIndex, THUMBNAIL_SIZE * 2), () -> ticket == request,
                    thumbnail -> {
                        if (getItem() != null && getItem() == frameIndex) {
                            image.setImage(FxUtils.toFxImage(thumbnail));
                        }
                    },
                    error -> caption.setText("#" + (frameIndex + 1) + " failed"));
        }
    }

    private <T> void submit(io.github.jacek4yang.stegsolver.core.CoalescingJobRunner.Job<T> job,
            java.util.function.BooleanSupplier current, java.util.function.Consumer<T> success,
            java.util.function.Consumer<Throwable> failure) {
        long ticket = generation;
        worker.execute(() -> {
            if (disposed || ticket != generation || !current.getAsBoolean()) return;
            try {
                T value = job.run();
                javafx.application.Platform.runLater(() -> {
                    if (!disposed && ticket == generation && current.getAsBoolean()) success.accept(value);
                });
            } catch (Exception error) {
                javafx.application.Platform.runLater(() -> {
                    if (!disposed && ticket == generation && current.getAsBoolean()) failure.accept(error);
                });
            }
        });
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
