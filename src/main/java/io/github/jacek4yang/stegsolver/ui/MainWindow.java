package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.Launcher;
import io.github.jacek4yang.stegsolver.barcode.BarcodeScanner;
import io.github.jacek4yang.stegsolver.barcode.ScanOptions;
import io.github.jacek4yang.stegsolver.barcode.ScanResult;
import io.github.jacek4yang.stegsolver.core.CoalescingJobRunner;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.transform.TransformCatalog;
import io.github.jacek4yang.stegsolver.transform.TransformDef;
import io.github.jacek4yang.stegsolver.ui.panes.AnalysisPane;
import io.github.jacek4yang.stegsolver.ui.panes.BarcodePane;
import io.github.jacek4yang.stegsolver.ui.panes.CombinePane;
import io.github.jacek4yang.stegsolver.ui.panes.ExtractPane;
import io.github.jacek4yang.stegsolver.ui.panes.FrameBrowserPane;
import io.github.jacek4yang.stegsolver.ui.panes.InfoPane;
import io.github.jacek4yang.stegsolver.ui.panes.StereoPane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The application window.
 *
 * <p>Layout: a menu bar and toolbar on top, the image viewport in the middle with a collapsible tool
 * dock on the right and a status bar at the bottom. Tools live in tabs instead of separate windows, and
 * a tool that produces an image (stereogram solve, image combine, frame browser) shows it in the central
 * viewport, which never modifies the open document.</p>
 */
public final class MainWindow implements PreviewHost {

    private static final String APP_TITLE = "StegSolver";

    private final Stage stage;
    private final DocumentSession document = new DocumentSession();
    private final ImageViewport viewport = new ImageViewport();
    private final BarcodeScanner scanner = new BarcodeScanner();

    private final CoalescingJobRunner renderRunner = new CoalescingJobRunner("stegsolver-render", Platform::runLater);
    private final CoalescingJobRunner workRunner = new CoalescingJobRunner("stegsolver-work", Platform::runLater);
    private final CoalescingJobRunner scanRunner = new CoalescingJobRunner("stegsolver-scan", Platform::runLater);
    private final CoalescingJobRunner toolRunner = new CoalescingJobRunner("stegsolver-tool", Platform::runLater);

    private boolean closed;
    private final java.util.concurrent.ExecutorService fileWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stegsolver-save");
        // Explicit saves finish before JVM shutdown, even if the window closes.
        thread.setDaemon(false);
        return thread;
    });
    private ImageData lastDocumentImage;
    private ImageData displayed;

    private RenderCache renderCache = new RenderCache(RenderCache.MIN_BUDGET_BYTES);

    private final BorderPane root = new BorderPane();
    private final TabPane toolDock = new TabPane();
    private final SplitPane split = new SplitPane();

    private final Label transformLabel = new Label("No image");
    private final Label transformStepBadge = new Label("[--/42]");
    private final Label sizeLabel = new Label("-");
    private final Label zoomLabel = new Label("100%");
    private final Label pixelLabel = new Label(" ");
    private final Label channelLabel = new Label(" ");
    private final Label messageLabel = new Label("Open an image to start (Ctrl+O)");
    private final Label warningBadge = new Label();
    private final Label barcodeBadge = new Label();
    private final Label viewBadge = new Label();
    private final Label selectionBadge = new Label("ROI: none");
    private final Region colorSwatch = new Region();

    private final HBox transformPill = new HBox(6);
    private final HBox docInfoPill = new HBox(6);
    private final HBox zoomPill = new HBox(6);
    private final HBox pixelInspectorPill = new HBox(6);

    private final Button previousButton = new Button("\u25C0 Prev");
    private final Button nextButton = new Button("Next \u25B6");
    private final Button backToDocumentButton = new Button("Back to document");
    private final ComboBox<TransformDef> transformChoice = new ComboBox<>();
    private final Slider zoomSlider = new Slider(2, 1000, 100);
    private final ToggleButton selectionToggle = new ToggleButton("Select ROI");
    private final ToggleButton dockToggle = new ToggleButton("Tools \u25EB");
    private final CheckMenuItem barcodeOverlayItem = new CheckMenuItem("Show barcode overlay");
    private final CheckMenuItem backgroundScanItem = new CheckMenuItem("Scan new images for barcodes");

    private final List<ToolPane> panes = new ArrayList<>();
    private final BarcodePane barcodePane;
    private final ExtractPane extractPane;
    private final AnalysisPane analysisPane;
    private final InfoPane infoPane;
    private final StereoPane stereoPane;
    private final CombinePane combinePane;
    private final FrameBrowserPane frameBrowserPane;

    private ThemeManager themeManager;
    private ImageData previewImage;
    private String previewLabel;
    private String statusOverride;
    private int hoveredX = -1;
    private int hoveredY = -1;
    private long lastScanSequence;

    public MainWindow(Stage stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
        barcodePane = new BarcodePane(this);
        extractPane = new ExtractPane(this);
        analysisPane = new AnalysisPane(this);
        infoPane = new InfoPane(this);
        stereoPane = new StereoPane(this);
        combinePane = new CombinePane(this);
        frameBrowserPane = new FrameBrowserPane(this);
        panes.add(infoPane);
        panes.add(extractPane);
        panes.add(barcodePane);
        panes.add(analysisPane);
        panes.add(stereoPane);
        panes.add(combinePane);
        panes.add(frameBrowserPane);

        buildLayout();
        buildMenus();
        buildToolbar();
        buildStatusBar();
        installShortcuts();
        installDragAndDrop();

        document.addListener(this::onDocumentChanged);
        viewport.setContextActions(
                this::copyDisplayedImage,
                this::saveDisplayedImage,
                () -> scanRegion(null, false),
                () -> scanRegion(viewport.selection(), false),
                this::nextTransform,
                this::previousTransform
        );
        viewport.setOnPixelHover(this::onPixelHoverX, this::onPixelHoverY, this::onPixelExit);
        viewport.setOnSelectionChanged(roi -> {
            selectionToggle.setSelected(roi.isNotEmpty());
            barcodePane.onSelectionChanged(roi);
            extractPane.onSelectionChanged(roi);
            updateStatusBar();
        });
        onDocumentChanged();
    }

    private Scene scene;

    public Scene createScene() {
        scene = new Scene(root, 1280, 820);
        themeManager = new ThemeManager(scene);
        themeManager.apply(ThemeManager.Theme.SYSTEM);
        return scene;
    }

    /** The scene, for helpers such as the screenshot used in the documentation. */
    public Scene scene() {
        return scene;
    }

    public Stage stage() {
        return stage;
    }

    // =============================================================== layout

    private void buildLayout() {
        split.setOrientation(Orientation.HORIZONTAL);
        split.getItems().addAll(viewport, toolDock);
        split.setDividerPositions(0.68);
        SplitPane.setResizableWithParent(toolDock, Boolean.FALSE);

        toolDock.getStyleClass().add("steg-tool-dock");
        toolDock.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        toolDock.setMinWidth(380);
        toolDock.setPrefWidth(440);
        for (ToolPane pane : panes) {
            Tab tab = new Tab(pane.title(), pane.content());
            tab.setClosable(false);
            toolDock.getTabs().add(tab);
        }

        root.setCenter(split);
        root.setPadding(new Insets(0));
    }

    private void buildMenus() {
        MenuBar menuBar = new MenuBar();

        Menu file = new Menu("File");
        file.getItems().addAll(
                item("Open image...", KeyCodeCombination.keyCombination("Shortcut+O"), this::openImage),
                item("Reload", KeyCodeCombination.keyCombination("F5"), this::reload),
                new SeparatorMenuItem(),
                item("Save displayed image as...", new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                        this::saveDisplayedImage),
                item("Copy displayed image", new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN),
                        this::copyDisplayedImage),
                new SeparatorMenuItem(),
                item("Close image", null, this::closeImage),
                item("Exit", KeyCodeCombination.keyCombination("Shortcut+Q"), this::exit));

        Menu view = new Menu("View");
        view.getItems().addAll(
                item("Zoom in", KeyCodeCombination.keyCombination("Shortcut+Plus"), () -> viewport.zoomBy(1.25)),
                item("Zoom out", KeyCodeCombination.keyCombination("Shortcut+Minus"), () -> viewport.zoomBy(0.8)),
                item("Fit to window", KeyCodeCombination.keyCombination("Shortcut+0"), viewport::fitToWindow),
                item("Actual size (1:1)", KeyCodeCombination.keyCombination("Shortcut+1"), viewport::actualSize),
                new SeparatorMenuItem(),
                item("Toggle tool dock", KeyCodeCombination.keyCombination("Shortcut+T"), this::toggleDock),
                fullscreenItem(),
                new SeparatorMenuItem(),
                themeMenu());

        Menu transform = new Menu("Transform");
        transform.getItems().addAll(
                item("Previous transform", new KeyCodeCombination(KeyCode.LEFT), this::previousTransform),
                item("Next transform", new KeyCodeCombination(KeyCode.RIGHT), this::nextTransform),
                item("Previous in group", new KeyCodeCombination(KeyCode.UP), this::previousTransformInGroup),
                item("Next in group", new KeyCodeCombination(KeyCode.DOWN), this::nextTransformInGroup),
                new SeparatorMenuItem(),
                item("Original image", null, () -> document.setTransformIndex(0)),
                item("Invert colours", null, () -> document.setTransformIndex(1)),
                new SeparatorMenuItem());
        for (var entry : TransformCatalog.grouped().entrySet()) {
            Menu group = new Menu(entry.getKey());
            for (TransformDef def : entry.getValue()) {
                RadioMenuItem radio = new RadioMenuItem(def.label());
                radio.setSelected(def.index() == document.transformIndex());
                radio.setOnAction(event -> document.setTransformIndex(def.index()));
                group.getItems().add(radio);
            }
            transform.getItems().add(group);
        }
        menuRoot(transform, () -> {
            for (var item : transform.getItems()) {
                if (item instanceof Menu group) {
                    for (var sub : group.getItems()) {
                        if (sub instanceof RadioMenuItem radio
                                && transformChoice.getValue() != null
                                && radio.getText().equals(transformChoice.getValue().label())) {
                            radio.setSelected(true);
                        }
                    }
                }
            }
        });

        Menu analyse = new Menu("Analyse");
        analyse.getItems().addAll(
                item("Extract data...", null, () -> focusPane(extractPane)),
                item("File format analysis", null, () -> focusPane(analysisPane)),
                item("Stereogram solver", null, () -> focusPane(stereoPane)),
                item("Frame browser", null, () -> focusPane(frameBrowserPane)),
                item("Combine with another image...", null, () -> focusPane(combinePane)),
                new SeparatorMenuItem(),
                item("Image information", null, () -> focusPane(infoPane)));

        Menu barcode = new Menu("Barcode");
        barcode.getItems().addAll(
                item("Scan displayed image", KeyCodeCombination.keyCombination("Shortcut+B"),
                        () -> scanRegion(null, false)),
                item("Scan selection", KeyCodeCombination.keyCombination("Shortcut+Shift+B"),
                        () -> scanRegion(viewport.selection(), false)),
                item("Scan screen region (X11)", null, () -> barcodePane.scanScreenRegion()),
                new SeparatorMenuItem(),
                barcodeOverlayItem,
                backgroundScanItem,
                new SeparatorMenuItem(),
                item("Clear results", null, () -> barcodePane.clearResults()));

        Menu help = new Menu("Help");
        help.getItems().addAll(
                item("Run self test on this image", null, this::runSelfTest),
                item("Keyboard shortcuts", null, this::showShortcuts),
                item("About StegSolver", null, this::showAbout));

        menuBar.getMenus().addAll(file, view, transform, analyse, barcode, help);
        root.setTop(new VBox(menuBar));
    }

    private MenuItem fullscreenItem() {
        CheckMenuItem item = new CheckMenuItem("Full screen (F11)");
        item.setAccelerator(new KeyCodeCombination(KeyCode.F11));
        item.setOnAction(event -> {
            boolean full = !stage.isFullScreen();
            stage.setFullScreen(full);
            item.setSelected(full);
        });
        stage.fullScreenProperty().addListener((observable, was, is) -> item.setSelected(is));
        return item;
    }

    private Menu themeMenu() {
        Menu menu = new Menu("Theme");
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            RadioMenuItem item = new RadioMenuItem(theme.label());
            item.setSelected(theme == ThemeManager.Theme.SYSTEM);
            item.setOnAction(event -> {
                if (themeManager != null) {
                    themeManager.apply(theme);
                }
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    private static void menuRoot(Menu menu, Runnable onShowing) {
        menu.setOnShowing(event -> onShowing.run());
    }

    private static MenuItem item(String text, KeyCombination accelerator, Runnable action) {
        MenuItem menuItem = new MenuItem(text);
        if (accelerator != null) {
            menuItem.setAccelerator(accelerator);
        }
        menuItem.setOnAction(event -> action.run());
        return menuItem;
    }

    private void buildToolbar() {
        Button openButton = new Button("Open");
        openButton.setMinWidth(Region.USE_PREF_SIZE);
        openButton.setTooltip(new Tooltip("Open an image file (Ctrl+O)"));
        openButton.setOnAction(event -> openImage());

        Button saveButton = new Button("Save");
        saveButton.setMinWidth(Region.USE_PREF_SIZE);
        saveButton.setTooltip(new Tooltip("Save the displayed image (Ctrl+S)"));
        saveButton.setOnAction(event -> saveDisplayedImage());

        previousButton.getStyleClass().add("steg-nav-btn");
        previousButton.setMinWidth(Region.USE_PREF_SIZE);
        previousButton.setTooltip(new Tooltip("Previous transform plane (Left arrow)"));
        previousButton.setOnAction(event -> previousTransform());

        nextButton.getStyleClass().add("steg-nav-btn");
        nextButton.setMinWidth(Region.USE_PREF_SIZE);
        nextButton.setTooltip(new Tooltip("Next transform plane (Right arrow)"));
        nextButton.setOnAction(event -> nextTransform());

        transformStepBadge.getStyleClass().add("steg-step-badge");
        transformStepBadge.setMinWidth(Region.USE_PREF_SIZE);

        transformChoice.getItems().setAll(TransformCatalog.definitions());
        transformChoice.setMinWidth(180);
        transformChoice.setPrefWidth(260);
        transformChoice.setMaxWidth(380);
        transformChoice.getStyleClass().add("steg-transform-combo");
        transformChoice.setTooltip(new Tooltip("Current transform; up/down arrow keys step within a group"));
        transformChoice.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TransformDef item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    String text = String.format("[%02d/42] %s", item.index() + 1, item.label());
                    setText(text);
                    setTooltip(new Tooltip(text));
                }
            }
        });
        transformChoice.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TransformDef item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("No image");
                    setTooltip(null);
                } else {
                    String text = String.format("[%02d/42] %s", item.index() + 1, item.label());
                    setText(text);
                    setTooltip(new Tooltip(text));
                }
            }
        });
        transformChoice.setOnAction(event -> {
            TransformDef selected = transformChoice.getValue();
            if (selected != null && selected.index() != document.transformIndex()) {
                document.setTransformIndex(selected.index());
            }
        });

        Button restoreButton = new Button("Fit");
        restoreButton.setMinWidth(Region.USE_PREF_SIZE);
        restoreButton.setTooltip(new Tooltip("Fit image to window (Ctrl+0)"));
        restoreButton.setOnAction(event -> viewport.fitToWindow());

        Button actualButton = new Button("1:1");
        actualButton.setMinWidth(Region.USE_PREF_SIZE);
        actualButton.setTooltip(new Tooltip("Actual pixel size 100% (Ctrl+1)"));
        actualButton.setOnAction(event -> viewport.actualSize());

        Button zoomOutButton = new Button("\u2212");
        zoomOutButton.setMinWidth(Region.USE_PREF_SIZE);
        zoomOutButton.setTooltip(new Tooltip("Zoom out (Ctrl+Minus)"));
        zoomOutButton.setOnAction(e -> viewport.zoomBy(0.8));

        Button zoomInButton = new Button("+");
        zoomInButton.setMinWidth(Region.USE_PREF_SIZE);
        zoomInButton.setTooltip(new Tooltip("Zoom in (Ctrl+Plus)"));
        zoomInButton.setOnAction(e -> viewport.zoomBy(1.25));

        zoomSlider.setMinWidth(80);
        zoomSlider.setPrefWidth(120);
        zoomSlider.setBlockIncrement(10);
        zoomSlider.valueProperty().addListener((observable, old, value) -> {
            if (viewport.hasContent() && Math.abs(value.doubleValue() / 100.0 - viewport.zoom()) > 0.0005) {
                viewport.setZoom(value.doubleValue() / 100.0);
                updateStatusBar();
            }
        });

        selectionToggle.setMinWidth(Region.USE_PREF_SIZE);
        selectionToggle.setTooltip(new Tooltip("Drag inside image to select a region (Esc to clear, Shift for square)"));
        selectionToggle.setOnAction(event -> {
            viewport.setSelectionMode(selectionToggle.isSelected());
            updateStatusBar();
        });

        backToDocumentButton.setText("\u21B6 Return to Document");
        backToDocumentButton.setMinWidth(Region.USE_PREF_SIZE);
        backToDocumentButton.getStyleClass().addAll("steg-badge", "steg-badge-warning");
        backToDocumentButton.setVisible(false);
        backToDocumentButton.setManaged(false);
        backToDocumentButton.setOnAction(event -> showDocument());

        dockToggle.setSelected(true);
        dockToggle.setMinWidth(Region.USE_PREF_SIZE);
        dockToggle.setTooltip(new Tooltip("Toggle tools dock visibility (Ctrl+T)"));
        dockToggle.setOnAction(event -> toggleDock());

        Button themeButton = new Button("Theme \u25D0");
        themeButton.setMinWidth(Region.USE_PREF_SIZE);
        themeButton.setTooltip(new Tooltip("Toggle theme between Dark and Light"));
        themeButton.setOnAction(event -> {
            if (themeManager != null) {
                themeManager.toggle();
            }
        });

        ToolBar toolbar = new ToolBar(
                openButton, saveButton, new Separator(Orientation.VERTICAL),
                previousButton, transformStepBadge, transformChoice, nextButton, new Separator(Orientation.VERTICAL),
                restoreButton, actualButton, zoomOutButton, zoomSlider, zoomInButton, new Separator(Orientation.VERTICAL),
                selectionToggle, backToDocumentButton, new Separator(Orientation.VERTICAL),
                dockToggle, themeButton
        );
        ((VBox) root.getTop()).getChildren().add(toolbar);
    }

    private void buildStatusBar() {
        transformLabel.getStyleClass().add("steg-transform-label");
        transformLabel.setWrapText(false);
        transformPill.getChildren().setAll(transformLabel);
        transformPill.getStyleClass().add("steg-status-pill");
        transformPill.setAlignment(Pos.CENTER_LEFT);
        transformPill.setMinWidth(Region.USE_PREF_SIZE);

        viewBadge.getStyleClass().addAll("steg-badge", "steg-badge-warning");
        viewBadge.setMinWidth(Region.USE_PREF_SIZE);
        viewBadge.setVisible(false);
        viewBadge.setManaged(false);

        docInfoPill.getChildren().setAll(sizeLabel);
        docInfoPill.getStyleClass().add("steg-status-pill");
        docInfoPill.setAlignment(Pos.CENTER_LEFT);
        docInfoPill.setMinWidth(Region.USE_PREF_SIZE);

        zoomPill.getChildren().setAll(zoomLabel);
        zoomPill.getStyleClass().add("steg-status-pill");
        zoomPill.setAlignment(Pos.CENTER_LEFT);
        zoomPill.setMinWidth(Region.USE_PREF_SIZE);
        zoomPill.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(zoomPill, new Tooltip("Click to toggle Fit / 100%"));
        zoomPill.setOnMouseClicked(event -> {
            if (viewport.hasContent()) {
                if (Math.abs(viewport.zoom() - 1.0) < 0.05) {
                    viewport.fitToWindow();
                } else {
                    viewport.actualSize();
                }
            }
        });

        selectionBadge.getStyleClass().addAll("steg-status-pill", "mono");
        selectionBadge.setMinWidth(Region.USE_PREF_SIZE);
        selectionBadge.setVisible(false);
        selectionBadge.setManaged(false);

        warningBadge.getStyleClass().addAll("steg-badge", "steg-badge-warning");
        warningBadge.setMinWidth(Region.USE_PREF_SIZE);
        barcodeBadge.getStyleClass().addAll("steg-badge", "steg-badge-payload");
        barcodeBadge.setMinWidth(Region.USE_PREF_SIZE);
        for (Label badge : List.of(warningBadge, barcodeBadge)) {
            badge.setVisible(false);
            badge.setManaged(false);
        }

        colorSwatch.getStyleClass().add("steg-color-swatch");
        colorSwatch.setVisible(false);
        colorSwatch.setManaged(false);

        pixelLabel.getStyleClass().add("mono");
        channelLabel.getStyleClass().add("mono");
        messageLabel.getStyleClass().add("steg-hint");
        messageLabel.textProperty().addListener((obs, old, text) ->
                messageLabel.setTooltip(text != null && !text.isBlank() ? new Tooltip(text) : null));

        pixelInspectorPill.getChildren().setAll(colorSwatch, pixelLabel, channelLabel);
        pixelInspectorPill.getStyleClass().add("steg-status-pill");
        pixelInspectorPill.setAlignment(Pos.CENTER_LEFT);
        pixelInspectorPill.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8,
                transformPill,
                viewBadge,
                docInfoPill,
                zoomPill,
                selectionBadge,
                spacer,
                messageLabel,
                warningBadge,
                barcodeBadge,
                pixelInspectorPill
        );
        bar.getStyleClass().add("steg-status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        root.setBottom(bar);

        barcodeOverlayItem.setSelected(true);
        barcodeOverlayItem.setOnAction(event -> viewport.setShowBarcodeOverlay(barcodeOverlayItem.isSelected()));
        backgroundScanItem.setSelected(true);
        backgroundScanItem.setDisable(false);
    }

    private void installShortcuts() {
        root.sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null) {
                return;
            }
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), () -> {
                selectionToggle.setSelected(false);
                viewport.setSelectionMode(false);
            });
            scene.setOnKeyPressed(event -> {
                if (event.getTarget() instanceof javafx.scene.control.TextInputControl) {
                    return;
                }
                switch (event.getCode()) {
                    case LEFT -> previousTransform();
                    case RIGHT -> nextTransform();
                    case UP -> previousTransformInGroup();
                    case DOWN -> nextTransformInGroup();
                    default -> {
                    }
                }
            });
        });
    }

    private void installDragAndDrop() {
        root.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        root.setOnDragDropped(event -> {
            var files = event.getDragboard().getFiles();
            boolean success = false;
            if (files != null && !files.isEmpty()) {
                openImage(files.get(0).toPath());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    // =============================================================== document

    /** Opens a file, reporting problems in a dialog instead of the console. */
    public void openImage(Path path) {
        if (path == null) {
            return;
        }
        workRunner.submit("open " + path.getFileName(), () -> ImageIoUtil.load(path), loaded -> {
            renderCache = new RenderCache(RenderCache.budgetFor(loaded.width(), loaded.height()));
            document.acceptLoaded(path, loaded);
            showDocument();
            status("Opened " + path.getFileName() + " (" + loaded.width() + "x" + loaded.height() + ")");
            startBackgroundBarcodeScan();
        }, error -> FxUtils.error(window(), "Could not open the image", String.valueOf(error), error));
    }

    private void openImage() {
        FxUtils.chooseImageToOpen(stage, "Open image").ifPresent(this::openImage);
    }

    private void reload() {
        if (document.isOpen()) {
            openImage(document.path());
        }
    }

    private void closeImage() {
        workRunner.cancel();
        document.close();
        renderCache.clear();
        viewport.clear();
        status("Image closed");
    }

    private void exit() {
        shutdown();
        Platform.exit();
    }

    /**
     * Runs an image computation for a tool panel on the background executor. Superseded requests are
     * dropped, which is what keeps dragging a stereogram offset slider responsive on a large image.
     */
    public <T> void runImageJob(String label, CoalescingJobRunner.Job<T> job, java.util.function.Consumer<T> onSuccess,
            java.util.function.Consumer<Throwable> onFailure) {
        toolRunner.submit(label, job, onSuccess, onFailure);
    }

    public void cancelBarcodeScan() { scanRunner.cancel(); lastScanSequence++; }

    public void cancelToolJobs() { toolRunner.cancel(); }

    /** Explicit saves are never coalesced or cancelled by navigation. */
    public <T> void runFileJob(CoalescingJobRunner.Job<T> job, java.util.function.Consumer<T> success) {
        if (closed) return;
        fileWorker.execute(() -> {
            try {
                T result = job.run();
                Platform.runLater(() -> { if (!closed) success.accept(result); });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed) FxUtils.error(window(), "Could not save the file", String.valueOf(error), error);
                });
            }
        });
    }

    public void saveImage(ImageData data, Path path) {
        runFileJob(() -> ImageIoUtil.save(data, path), outcome -> status(outcome.message(path)));
    }

    /** Releases background resources; called when the window closes. */
    public void shutdown() {
        if (closed) return;
        closed = true;
        fileWorker.shutdown();
        renderRunner.close();
        workRunner.close();
        scanRunner.close();
        toolRunner.close();
        for (ToolPane pane : panes) {
            pane.dispose();
        }
    }

    private void onDocumentChanged() {
        renderRunner.cancel();
        toolRunner.cancel();
        scanRunner.cancel();
        lastScanSequence++;
        if (lastDocumentImage != document.image()) {
            lastDocumentImage = document.image();
            renderCache.clear();
            displayed = null;
            viewport.clear();
            previewImage = null;
            previewLabel = null;
        }
        boolean open = document.isOpen();
        previousButton.setDisable(!open);
        nextButton.setDisable(!open);
        transformChoice.setDisable(!open);
        transformChoice.getSelectionModel().select(document.transform());
        for (ToolPane pane : panes) {
            pane.onDocumentChanged();
        }
        if (!open) {
            viewport.clear();
            transformStepBadge.setText("[--/42]");
            transformLabel.setText("No image");
            sizeLabel.setText("-");
            warningBadge.setVisible(false);
            barcodeBadge.setVisible(false);
            updateStatusBar();
            return;
        }
        transformStepBadge.setText(String.format("[%02d/42]", document.transformIndex() + 1));
        transformLabel.setText(document.transform().describe());
        sizeLabel.setText(document.image().width() + "x" + document.image().height()
                + " \u00b7 " + FxUtils.bytes(document.loadedBytes())
                + " \u00b7 " + (document.image().hasAlpha() ? "alpha" : "no alpha"));
        previewImage = null;
        previewLabel = null;
        renderCurrentTransform();
        updateStatusBar();
    }

    /**
     * Renders the current transform.
     *
     * <p>Cache hits (the pixel cache of the engine and the render cache of the viewport) are served
     * immediately on the JavaFX thread; a miss goes to the coalescing runner, which drops superseded
     * requests. That is what makes holding an arrow key smooth on a large image.</p>
     */
    private void renderCurrentTransform() {
        renderRunner.cancel();
        if (!document.isOpen()) {
            return;
        }
        int index = document.transformIndex();
        TransformDef def = TransformCatalog.byIndex(index);
        String key = "T" + index;
        WritableImage cached = renderCache.peek(key);
        if (cached != null && (index == 0 || document.engine().isCached(index))) {
            displayed = document.currentImage();
            viewport.setContent(document.engine().pixelsFor(index), document.image().width(),
                    document.image().height(), def.kind() == io.github.jacek4yang.stegsolver.transform.TransformKind.ORIGINAL
                            && document.image().hasAlpha(),
                    cached, def.label());
            updateStatusBar();
            return;
        }
        int width = document.image().width();
        int height = document.image().height();
        boolean alpha = def.kind() == io.github.jacek4yang.stegsolver.transform.TransformKind.ORIGINAL
                && document.image().hasAlpha();
        var engine = document.engine();
        renderRunner.submit("transform " + index, () -> {
            int[] pixels = engine.pixelsFor(index);
            return new Rendered(pixels, FxUtils.toFxImage(pixels, width, height), key, def, width, height,
                    alpha);
        }, rendered -> {
            displayed = rendered.def().index() == 0 ? engine.source()
                    : ImageData.opaque(rendered.width(), rendered.height(), rendered.pixels());
            renderCache.put(rendered.key(), rendered.image(), 4L * rendered.width() * rendered.height());
            viewport.setContent(rendered.pixels(), rendered.width(), rendered.height(), rendered.alpha(),
                    rendered.image(), rendered.def().label());
            updateStatusBar();
        }, error -> status("Rendering transform failed: " + error));
    }

    private record Rendered(int[] pixels, WritableImage image, String key, TransformDef def, int width,
            int height, boolean alpha) {
    }

    private void previousTransform() {
        document.previousTransform();
    }

    private void nextTransform() {
        document.nextTransform();
    }

    private void previousTransformInGroup() {
        document.previousTransformInGroup();
    }

    private void nextTransformInGroup() {
        document.nextTransformInGroup();
    }

    private void saveDisplayedImage() {
        if (!document.isOpen() && previewImage == null) {
            status("Nothing to save");
            return;
        }
        ImageData data = displayedImage();
        String suggested = document.isOpen()
                ? stripExtension(document.fileName()) + "-" + sanitise(document.transform().label()) + ".png"
                : "solved.png";
        if (data == null) return;
        FxUtils.chooseFileToSave(stage, "Save displayed image", suggested).ifPresent(path ->
            saveImage(data, path));
    }

    private void copyDisplayedImage() {
        ImageData data = displayedImage();
        if (data == null) {
            status("Nothing to copy");
            return;
        }
        FxUtils.copyImage(data);
        status("Copied the displayed image to the clipboard");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitise(String label) {
        return label.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    // =============================================================== paging / view mode

    private void toggleDock() {
        setDockVisible(!toolDock.isVisible());
    }

    private void setDockVisible(boolean visible) {
        toolDock.setVisible(visible);
        toolDock.setManaged(visible);
        dockToggle.setSelected(visible);
        if (!visible) {
            split.setDividerPositions(1.0);
        } else {
            split.setDividerPositions(0.68);
        }
    }

    private void focusPane(ToolPane pane) {
        if (!toolDock.isVisible()) {
            setDockVisible(true);
        }
        for (int i = 0; i < panes.size(); i++) {
            if (panes.get(i) == pane) {
                toolDock.getSelectionModel().select(i);
            }
        }
    }

    private void showShortcuts() {
        FxUtils.info(stage, "Keyboard shortcuts", """
                Left / Right      previous / next transform
                Up / Down         previous / next transform inside the current group
                Ctrl+O            open an image
                Ctrl+S            save the displayed image
                Ctrl+C            copy the displayed image
                Ctrl+B            scan the displayed image for barcodes
                Ctrl+Shift+B      scan the selected region
                Ctrl+0 / Ctrl+1   fit to window / actual size
                Ctrl+plus/minus   zoom in / out
                Ctrl+T            show or hide the tool dock
                Mouse wheel       zoom at the cursor
                Mouse drag        pan (or select a region while selection mode is on)
                Double click      toggle fit / 1:1
                Esc               leave selection mode
                F5                reload the image
                F11               full screen
                """);
    }

    /**
     * Runs every engine layer once on the current image and shows the result. Useful to confirm that a
     * downloaded build really works, and to report a problem with useful detail.
     */
    private void runSelfTest() {
        ImageData image = documentImage();
        if (image == null) {
            status("Open an image first");
            return;
        }
        Path file = document.path();
        status("Running the self test...");
        workRunner.submit("self test", () -> io.github.jacek4yang.stegsolver.selfcheck.SelfTest
                .run(image, file), report -> {
                    status("Self test: " + (report.ok() ? "all steps succeeded" : "some steps failed"));
                    javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(report.toText());
                    area.setEditable(false);
                    area.setWrapText(false);
                    area.getStyleClass().add("mono");
                    area.setPrefColumnCount(90);
                    area.setPrefRowCount(16);
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            report.ok() ? javafx.scene.control.Alert.AlertType.INFORMATION
                                    : javafx.scene.control.Alert.AlertType.WARNING);
                    alert.initOwner(stage);
                    alert.setTitle("StegSolver self test");
                    alert.setHeaderText(report.ok()
                            ? "Every engine layer worked on this image"
                            : "Some engine layers failed on this image");
                    alert.getDialogPane().setContent(area);
                    alert.setResizable(true);
                    alert.showAndWait();
                },
                error -> FxUtils.error(window(), "Self test failed", String.valueOf(error), error));
    }

    private void showAbout() {
        FxUtils.info(stage, "About StegSolver", """
                StegSolver %s
                A steganography analysis tool for images.

                Bit plane transforms, data extraction, stereogram solving, image combining,
                structural file analysis and binary safe barcode/QR payload extraction.

                This is a Java 21 / JavaFX rebuild of the original StegSolve by Caesum,
                keeping its transform numbering and extraction conventions.

                Decoded barcodes and QR codes are never opened, extracted or executed:
                payloads are only classified and saved where you ask.

                Java %s \u00b7 JavaFX %s
                """.formatted(Launcher.version(), System.getProperty("java.version"),
                System.getProperty("javafx.version", "21")));
    }

    // =============================================================== barcode scanning

    /**
     * Starts a barcode scan in the background. A {@code null} region scans the displayed image.
     * No dialog is shown for a background scan; the result badge and the overlay are updated instead.
     */
    @Override
    public void scanRegion(Roi region, boolean thorough) {
        if (document.isOpen() || previewImage != null) {
            ImageData image = displayedImage();
            if (image == null) {
                return;
            }
            Roi area = region == null || region.isEmpty() ? Roi.whole(image.width(), image.height()) : region;
            ScanOptions options = barcodePane.options(thorough);
            long sequence = ++lastScanSequence;
            status("Scanning " + (region == null ? "the image" : "the selection") + "...");
            scanRunner.submit("scan", () -> scanner.scan(image, area, options), result -> {
                if (sequence == lastScanSequence) {
                    reportScan(result);
                }
            }, error -> status("Barcode scan failed: " + error));
        } else {
            status("Open an image first");
        }
    }

    private void startBackgroundBarcodeScan() {
        if (!backgroundScanItem.isSelected() || !document.isOpen()) {
            return;
        }
        ImageData image = document.image();
        long sequence = ++lastScanSequence;
        // A quick scan of the original image, off the UI thread. Deliberately silent: findings are
        // announced with a badge, never with a dialog.
        scanRunner.submit("background scan", () -> scanner.scan(image, ScanOptions.quick()), result -> {
            if (sequence != lastScanSequence) {
                return;
            }
            barcodePane.addResults(result, true);
            if (!result.isEmpty()) {
                status("Background scan: " + result.count() + " symbol(s) found, see the Barcode tab");
                updateBarcodeBadge(result);
            }
        }, error -> {
            // A failed background scan must never bother the user.
        });
    }

    @Override
    public void reportScan(ScanResult result) {
        barcodePane.addResults(result, false);
        updateBarcodeBadge(result);
        status(result.summary());
        if (!result.isEmpty()) {
            viewport.setBarcodeHits(result.hits());
            focusPane(barcodePane);
        }
    }

    @Override
    public ScanResult lastScan() {
        return barcodePane.accumulated();
    }

    private void updateBarcodeBadge(ScanResult result) {
        boolean visible = result != null && !result.isEmpty();
        barcodeBadge.setVisible(visible);
        barcodeBadge.setManaged(visible);
        if (visible) {
            long binary = result.hits().stream().filter(hit -> hit.hasBinaryPayload()).count();
            barcodeBadge.setText("BARCODE " + result.count() + (binary > 0 ? " (" + binary + " binary)" : ""));
        }
    }

    // =============================================================== status

    private void onPixelHoverX(Integer x) {
        hoveredX = x;
        updatePixelLabel();
    }

    private void onPixelHoverY(Integer y) {
        hoveredY = y;
        updatePixelLabel();
    }

    private void onPixelExit() {
        hoveredX = -1;
        hoveredY = -1;
        pixelLabel.setText(" ");
        channelLabel.setText(" ");
        colorSwatch.setVisible(false);
        colorSwatch.setManaged(false);
    }

    private void updatePixelLabel() {
        if (hoveredX < 0 || hoveredY < 0 || !viewport.hasContent()) {
            pixelLabel.setText(" ");
            channelLabel.setText(" ");
            colorSwatch.setVisible(false);
            colorSwatch.setManaged(false);
            return;
        }
        int viewPixel = viewport.viewPixelAt(hoveredX, hoveredY);
        int a = (viewPixel >>> 24) & 0xFF;
        int r = (viewPixel >>> 16) & 0xFF;
        int g = (viewPixel >>> 8) & 0xFF;
        int b = viewPixel & 0xFF;

        colorSwatch.setStyle(String.format(java.util.Locale.ROOT,
                "-fx-background-color: rgb(%d,%d,%d); -fx-opacity: %.2f;", r, g, b, a / 255.0));
        colorSwatch.setVisible(true);
        colorSwatch.setManaged(true);

        pixelLabel.setText(String.format("(%d, %d) %s", hoveredX, hoveredY, FxUtils.argbHex(viewPixel)));
        if (document.isOpen() && !isShowingPreview()) {
            int original = document.image().argbAt(hoveredX, hoveredY);
            channelLabel.setText("src " + FxUtils.argbHex(original) + "  " + FxUtils.channels(original));
        } else {
            channelLabel.setText(FxUtils.channels(viewPixel));
        }
    }

    private void updateStatusBar() {
        if (messageLabel.getText() != null && statusOverride != null) {
            messageLabel.setText(statusOverride);
        }
        zoomLabel.setText(viewport.hasContent() ? FxUtils.percent(viewport.zoom()) : "-");
        double sliderValue = viewport.hasContent() ? viewport.zoom() * 100 : 100;
        if (Math.abs(sliderValue - zoomSlider.getValue()) > 0.05) {
            zoomSlider.setValue(sliderValue);
        }
        stage.setTitle(document.isOpen()
                ? "StegSolver — " + document.fileName() + " — " + document.transform().label()
                        + (isShowingPreview() ? " (viewing " + previewLabel + ")" : "")
                : "StegSolver");
        boolean preview = isShowingPreview();
        viewBadge.setVisible(preview);
        viewBadge.setManaged(preview);
        backToDocumentButton.setVisible(preview);
        backToDocumentButton.setManaged(preview);
        if (preview) {
            viewBadge.setText("VIEWING " + previewLabel + " (document unchanged)");
            transformStepBadge.setText("[VIEW]");
            previousButton.setDisable(true);
            nextButton.setDisable(true);
            transformChoice.setDisable(true);
        } else if (document.isOpen()) {
            transformStepBadge.setText(String.format("[%02d/42]", document.transformIndex() + 1));
            previousButton.setDisable(false);
            nextButton.setDisable(false);
            transformChoice.setDisable(false);
        } else {
            transformStepBadge.setText("[--/42]");
            previousButton.setDisable(true);
            nextButton.setDisable(true);
            transformChoice.setDisable(true);
        }

        Roi sel = viewport.selection();
        if (sel != null && sel.isNotEmpty()) {
            selectionBadge.setText(String.format("ROI: %d\u00d7%d @ (%d, %d)", sel.width(), sel.height(), sel.x(), sel.y()));
            selectionBadge.setVisible(true);
            selectionBadge.setManaged(true);
        } else {
            selectionBadge.setText("ROI: none");
            selectionBadge.setVisible(false);
            selectionBadge.setManaged(false);
        }

        infoPane.refresh();
        extractPane.onSelectionChanged(viewport.selection());
    }

    /** Selects a tool dock tab by index; used by the smoke test to document a particular panel. */
    public void selectToolTab(int index) {
        if (index >= 0 && index < panes.size()) {
            if (!toolDock.isVisible()) {
                toggleDock();
            }
            toolDock.getSelectionModel().select(index);
        }
    }

    /** The viewport image node, for rendering diagnostics. */
    public javafx.scene.Node imageViewNode() {
        return viewport.imageViewNode();
    }

    /** Layout diagnostics for the smoke test. */
    public String layoutDiagnostics() {
        return viewport.diagnostics();
    }

    /** The text currently shown in the status bar; used by diagnostics and the smoke test. */
    public String statusMessage() {
        return messageLabel.getText();
    }

    @Override
    public void status(String message) {
        statusOverride = message;
        messageLabel.setText(message == null ? "" : message);
    }

    // =============================================================== PreviewHost

    @Override
    public ImageData displayedImage() {
        if (previewImage != null) {
            return previewImage;
        }
        return displayed;
    }

    @Override
    public ImageData documentImage() {
        return document.image();
    }

    @Override
    public Roi selection() {
        return viewport.selection();
    }

    @Override
    public void showPreview(ImageData data, String label) {
        renderRunner.cancel();
        if (data == null) {
            showDocument();
            return;
        }
        previewImage = data;
        previewLabel = label;
        viewport.setBarcodeHits(List.of());
        WritableImage image = FxUtils.toFxImage(data);
        viewport.setContent(data.pixels(), data.width(), data.height(), data.hasAlpha(), image, label);
        for (ToolPane pane : panes) {
            pane.onViewChanged();
        }
        updateStatusBar();
        updatePixelLabel();
    }

    @Override
    public void showDocument() {
        previewImage = null;
        previewLabel = null;
        for (ToolPane pane : panes) {
            pane.onViewChanged();
        }
        if (document.isOpen()) {
            ScanResult scan = barcodePane.accumulated();
            viewport.setBarcodeHits(scan == null ? List.of() : scan.hits());
        }
        renderCurrentTransform();
        updateStatusBar();
    }

    @Override
    public boolean isShowingPreview() {
        return previewImage != null;
    }

    @Override
    public Window window() {
        return stage;
    }

    /** The document session, for panels that need transform state. */
    public DocumentSession document() {
        return document;
    }

    /** The barcode panel, so that other panels can trigger a scan or reset the overlay. */
    public BarcodePane barcodePane() {
        return barcodePane;
    }

    /** The viewport, for panels that need selections or zoom. */
    public ImageViewport viewport() {
        return viewport;
    }

    /** Image column under the cursor, or -1 when the cursor is outside the image. */
    public int hoveredImageX() {
        return hoveredX;
    }

    /** Image row under the cursor, or -1 when the cursor is outside the image. */
    public int hoveredImageY() {
        return hoveredY;
    }

    /** A short description of the displayed content, used in reports. */
    public String displayedLabel() {
        return isShowingPreview() ? previewLabel : document.isOpen() ? document.transform().label() : "no image";
    }

    /** Convenience for panels: asks the user for an image file. */
    public Optional<Path> chooseImage(String title) {
        return FxUtils.chooseImageToOpen(stage, title);
    }
}
