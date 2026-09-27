package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.barcode.PayloadDetector;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.HexDump;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.extract.AutoLsbScanner;
import io.github.jacek4yang.stegsolver.extract.DataExtractor;
import io.github.jacek4yang.stegsolver.extract.ExtractionOptions;
import io.github.jacek4yang.stegsolver.extract.LsbCandidate;
import io.github.jacek4yang.stegsolver.extract.RgbOrder;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Data extraction: automatic LSB scanning across common CTF steganography configurations,
 * combined with the full manual bit plane selection, traversal and bit order options of the
 * original extractor.
 */
public final class ExtractPane implements ToolPane {

    private final MainWindow window;
    private final io.github.jacek4yang.stegsolver.core.CoalescingJobRunner runner =
            new io.github.jacek4yang.stegsolver.core.CoalescingJobRunner("stegsolver-extract", Platform::runLater);

    // =============================================================== Auto LSB Scan Controls
    private final Button autoScanButton = new Button("Auto LSB Scan");
    private final CheckBox deepScanBox = new CheckBox("Deep scan");
    private final Button cancelScanButton = new Button("Cancel");
    private final ProgressBar scanProgress = new ProgressBar(0);
    private final Label scanStatusLabel = new Label("Scan image for hidden LSB data across common CTF configurations.");

    private final ObservableList<LsbCandidate> candidateList = FXCollections.observableArrayList();
    private final TableView<LsbCandidate> candidateTable = new TableView<>(candidateList);

    // Selected candidate details
    private final VBox candidateDetailBox = new VBox(6);
    private final Label candidateScoreBadge = new Label();
    private final Label candidateTypeBadge = new Label();
    private final Label candidateSizeBadge = new Label();
    private final Label candidateConfigLabel = new Label();
    private final Label candidateReasonLabel = new Label();
    private final Button applyCandidateButton = new Button("Apply Settings");
    private final Button saveCandidatePayloadButton = new Button("Save Payload...");
    private final Button copyCandidateHexButton = new Button("Copy Hex");
    private final Button copyCandidateTextButton = new Button("Copy Text");
    private final TextArea candidatePreviewArea = new TextArea();

    private AutoLsbScanner.ScanTask activeScanTask;
    private int scanSequence;

    // =============================================================== Manual Extraction Controls
    private final Map<Channel, CheckBox[]> planeBoxes = new EnumMap<>(Channel.class);
    private final Map<Channel, CheckBox> allBoxes = new EnumMap<>(Channel.class);
    private final ToggleGroup traversalGroup = new ToggleGroup();
    private final ToggleGroup bitOrderGroup = new ToggleGroup();
    private final RadioButton rowFirstButton = new RadioButton("Row by row");
    private final RadioButton columnFirstButton = new RadioButton("Column by column");
    private final RadioButton msbFirstButton = new RadioButton("MSB first");
    private final RadioButton lsbFirstButton = new RadioButton("LSB first");
    private final ComboBox<RgbOrder> orderChoice = new ComboBox<>();
    private final CheckBox invertBits = new CheckBox("Invert bits");
    private final CheckBox useSelection = new CheckBox("Only the selected region");
    private final CheckBox includeHex = new CheckBox("Include hex dump");
    private final ComboBox<Integer> previewLimit = new ComboBox<>();
    private final Label sizeLabel = new Label();
    private final Label maskLabel = new Label();
    private final Label signatureLabel = new Label();
    private final TextArea preview = new TextArea();
    private final Button previewButton = new Button("Extract");
    private final Button saveBinaryButton = new Button("Save .bin...");
    private final Button saveTextButton = new Button("Save text...");
    private final Button copyHexButton = new Button("Copy hex");

    /** The bit planes selected when the panel is first shown: the RGB least significant bits. */
    private static final int[] DEFAULT_SELECTION = {1, 0, 2, 0, 3, 0};

    private final GridPane planesGrid;
    private final Node optionsGrid;
    private byte[] extracted = new byte[0];
    private ExtractionOptions lastOptions;
    private int extractionSequence;

    public ExtractPane(MainWindow window) {
        this.window = window;
        configureAutoScanControls();
        configureManualControls();

        planesGrid = buildPlanesGrid();
        optionsGrid = buildOptionsGrid();
        applySelection(DEFAULT_SELECTION);
        updateButtons(false);
    }

    private void configureAutoScanControls() {
        autoScanButton.getStyleClass().add("steg-nav-btn");
        autoScanButton.setMinWidth(Region.USE_PREF_SIZE);
        autoScanButton.setTooltip(new Tooltip("Automatically scan and rank common LSB configurations (Phase 1)"));
        autoScanButton.setOnAction(event -> startAutoScan());

        deepScanBox.setWrapText(true);
        deepScanBox.setTooltip(new Tooltip("Broader candidate enumeration including 3-bit, 4-bit, and all permutations"));

        cancelScanButton.setMinWidth(Region.USE_PREF_SIZE);
        cancelScanButton.setDisable(true);
        cancelScanButton.setOnAction(event -> cancelAutoScan());

        scanProgress.setVisible(false);
        scanProgress.setManaged(false);
        scanProgress.setPrefWidth(140);

        scanStatusLabel.setWrapText(true);
        scanStatusLabel.getStyleClass().add("steg-hint");

        buildCandidateTable();
        buildCandidateDetailUI();
    }

    private void buildCandidateTable() {
        candidateTable.setPrefHeight(150);
        candidateTable.setMinHeight(120);
        candidateTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<LsbCandidate, Integer> scoreCol = new TableColumn<>("Score");
        scoreCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().score()));
        scoreCol.setMinWidth(52);
        scoreCol.setPrefWidth(60);
        scoreCol.setMaxWidth(80);
        scoreCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Integer score, boolean empty) {
                super.updateItem(score, empty);
                if (empty || score == null) {
                    setText(null);
                    setStyle("");
                    setTooltip(null);
                } else {
                    setText(String.valueOf(score));
                    setAlignment(Pos.CENTER_RIGHT);
                    if (score >= 95) {
                        setStyle("-fx-text-fill: #4ade80; -fx-font-weight: bold;");
                    } else if (score >= 85) {
                        setStyle("-fx-text-fill: #60a5fa; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #9494a3;");
                    }
                    setTooltip(new Tooltip("Candidate score: " + score + " / 100"));
                }
            }
        });

        TableColumn<LsbCandidate, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().typeName()));
        typeCol.setMinWidth(65);
        typeCol.setPrefWidth(75);
        typeCol.setMaxWidth(100);
        typeCol.setCellFactory(col -> createTooltipCell());

        TableColumn<LsbCandidate, String> configCol = new TableColumn<>("Configuration");
        configCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().formattedConfig()));
        configCol.setMinWidth(170);
        configCol.setPrefWidth(210);
        configCol.setCellFactory(col -> createTooltipCell());

        TableColumn<LsbCandidate, String> previewCol = new TableColumn<>("Preview");
        previewCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().previewSnippet()));
        previewCol.setMinWidth(110);
        previewCol.setPrefWidth(140);
        previewCol.setCellFactory(col -> createTooltipCell());

        candidateTable.getColumns().setAll(scoreCol, typeCol, configCol, previewCol);

        candidateTable.getSelectionModel().selectedItemProperty().addListener((observable, old, candidate) ->
                showCandidateDetails(candidate));
    }

    private static TableCell<LsbCandidate, String> createTooltipCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setTooltip(new Tooltip(item));
                }
            }
        };
    }

    private void buildCandidateDetailUI() {
        candidateScoreBadge.getStyleClass().addAll("steg-badge", "steg-badge-payload");
        candidateTypeBadge.getStyleClass().addAll("steg-badge", "steg-badge-info");
        candidateSizeBadge.getStyleClass().addAll("steg-badge");

        candidateConfigLabel.getStyleClass().addAll("mono", "steg-transform-label");
        candidateReasonLabel.setWrapText(true);
        candidateReasonLabel.getStyleClass().add("steg-hint");

        applyCandidateButton.setMinWidth(Region.USE_PREF_SIZE);
        applyCandidateButton.setTooltip(new Tooltip("Transfer this candidate's exact settings into manual controls"));
        applyCandidateButton.setOnAction(event -> {
            LsbCandidate sel = candidateTable.getSelectionModel().getSelectedItem();
            if (sel != null) applyCandidateSettings(sel);
        });

        saveCandidatePayloadButton.setMinWidth(Region.USE_PREF_SIZE);
        saveCandidatePayloadButton.setTooltip(new Tooltip("Perform full extraction and save the complete payload bytes"));
        saveCandidatePayloadButton.setOnAction(event -> {
            LsbCandidate sel = candidateTable.getSelectionModel().getSelectedItem();
            if (sel != null) saveCandidatePayload(sel);
        });

        copyCandidateHexButton.setMinWidth(Region.USE_PREF_SIZE);
        copyCandidateHexButton.setTooltip(new Tooltip("Copy hex preview to clipboard"));
        copyCandidateHexButton.setOnAction(event -> {
            LsbCandidate sel = candidateTable.getSelectionModel().getSelectedItem();
            if (sel != null) copyCandidateHex(sel);
        });

        copyCandidateTextButton.setMinWidth(Region.USE_PREF_SIZE);
        copyCandidateTextButton.setTooltip(new Tooltip("Copy text payload to clipboard"));
        copyCandidateTextButton.setOnAction(event -> {
            LsbCandidate sel = candidateTable.getSelectionModel().getSelectedItem();
            if (sel != null) copyCandidateText(sel);
        });

        candidatePreviewArea.setEditable(false);
        candidatePreviewArea.setWrapText(false);
        candidatePreviewArea.getStyleClass().add("mono");
        candidatePreviewArea.setPrefRowCount(6);

        FlowPane badgeRow = new FlowPane(6, 6, candidateScoreBadge, candidateTypeBadge, candidateSizeBadge);
        badgeRow.setAlignment(Pos.CENTER_LEFT);

        FlowPane actionRow = new FlowPane(6, 6, applyCandidateButton, saveCandidatePayloadButton,
                copyCandidateHexButton, copyCandidateTextButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        candidateDetailBox.getChildren().setAll(
                badgeRow,
                candidateConfigLabel,
                candidateReasonLabel,
                actionRow,
                candidatePreviewArea
        );
        candidateDetailBox.setVisible(false);
        candidateDetailBox.setManaged(false);
    }

    private void configureManualControls() {
        includeHex.setSelected(true);
        includeHex.setWrapText(true);
        previewLimit.getItems().setAll(1024, 4096, 16384, 65536);
        previewLimit.setValue(4096);
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("mono");
        preview.setPrefRowCount(12);
        signatureLabel.setWrapText(true);
        sizeLabel.getStyleClass().add("steg-hint");
        sizeLabel.setWrapText(true);
        maskLabel.getStyleClass().add("mono");
        maskLabel.setWrapText(true);

        previewButton.setDefaultButton(false);
        previewButton.setMinWidth(Region.USE_PREF_SIZE);
        previewButton.setOnAction(event -> extract());

        saveBinaryButton.setMinWidth(Region.USE_PREF_SIZE);
        saveBinaryButton.setOnAction(event -> saveBinary());

        saveTextButton.setMinWidth(Region.USE_PREF_SIZE);
        saveTextButton.setOnAction(event -> saveText());

        copyHexButton.setMinWidth(Region.USE_PREF_SIZE);
        copyHexButton.setOnAction(event -> {
            int copied = FxUtils.copyHex(extracted, 32);
            window.status("Copied hex for " + copied + " of " + extracted.length + " bytes"
                    + (copied < extracted.length ? "; use Save binary or Save text for the complete extraction" : ""));
        });
    }

    @Override
    public String title() {
        return "Extract";
    }

    @Override
    public Node content() {
        // 1. Auto LSB Scan Card
        FlowPane scanControlRow = new FlowPane(8, 6, autoScanButton, deepScanBox, cancelScanButton, scanProgress);
        scanControlRow.setAlignment(Pos.CENTER_LEFT);

        VBox autoScanCard = new VBox(8,
                section("Auto LSB Scan (CTF Analysis)"),
                scanControlRow,
                scanStatusLabel,
                candidateTable,
                candidateDetailBox);
        autoScanCard.getStyleClass().add("steg-card");

        // 2. Manual Planes Card
        VBox planesCard = new VBox(8,
                section("Manual Bit Planes to Extract"),
                planesGrid,
                quickSelectionRow());
        planesCard.getStyleClass().add("steg-card");

        // 3. Manual Options Card
        VBox optionsCard = new VBox(8,
                section("Extraction Settings"),
                optionsGrid);
        optionsCard.getStyleClass().add("steg-card");

        // 4. Manual Results Card
        FlowPane actionRow = new FlowPane(8, 6, previewButton, saveBinaryButton, saveTextButton, copyHexButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        FlowPane previewControls = new FlowPane(8, 6, new Label("Preview"), includeHex, previewLimit);
        previewControls.setAlignment(Pos.CENTER_LEFT);

        VBox resultsCard = new VBox(8,
                section("Manual Extraction Preview"),
                sizeLabel,
                signatureLabel,
                actionRow,
                previewControls,
                preview);
        resultsCard.getStyleClass().add("steg-card");

        VBox box = new VBox(10, autoScanCard, planesCard, optionsCard, resultsCard);
        box.setPadding(new Insets(10));

        // The dock can be made narrow and short, so the panel scrolls instead of clipping its controls.
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPadding(new Insets(0));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private static Label section(String title) {
        Label header = new Label(title);
        header.getStyleClass().add("steg-card-header");
        return header;
    }

    // =============================================================== Auto LSB Scan Logic

    private void startAutoScan() {
        ImageData image = window.displayedImage();
        if (image == null) {
            window.status("Open an image first");
            return;
        }
        cancelAutoScan();

        final int sequence = ++scanSequence;
        boolean deep = deepScanBox.isSelected();
        Roi region = targetRegion(image);

        autoScanButton.setDisable(true);
        cancelScanButton.setDisable(false);
        scanProgress.setProgress(0);
        scanProgress.setVisible(true);
        scanProgress.setManaged(true);
        scanStatusLabel.setText("Starting " + (deep ? "Deep" : "Fast") + " LSB scan in background...");
        candidateList.clear();
        candidateDetailBox.setVisible(false);
        candidateDetailBox.setManaged(false);

        activeScanTask = AutoLsbScanner.startScan(image, region, deep, new AutoLsbScanner.ScanListener() {
            @Override
            public void onProgress(int completed, int total, int candidateCount) {
                Platform.runLater(() -> {
                    if (sequence != scanSequence) return;
                    double progress = total > 0 ? (double) completed / total : 0;
                    scanProgress.setProgress(progress);
                    scanStatusLabel.setText("Scanning candidate " + completed + " of " + total
                            + " \u00b7 " + candidateCount + " unique configuration(s) ranked");
                });
            }

            @Override
            public void onBatchResults(List<LsbCandidate> batch) {
                Platform.runLater(() -> {
                    if (sequence != scanSequence) return;
                    for (LsbCandidate c : batch) {
                        if (!candidateList.contains(c)) {
                            candidateList.add(c);
                        }
                    }
                    candidateList.sort(Comparator.comparingInt(LsbCandidate::score).reversed());
                    if (candidateTable.getSelectionModel().isEmpty() && !candidateList.isEmpty()) {
                        candidateTable.getSelectionModel().selectFirst();
                    }
                });
            }

            @Override
            public void onFinished(List<LsbCandidate> allRanked) {
                Platform.runLater(() -> {
                    if (sequence != scanSequence) return;
                    candidateList.setAll(allRanked);
                    if (!candidateList.isEmpty()) {
                        candidateTable.getSelectionModel().selectFirst();
                    }
                    autoScanButton.setDisable(false);
                    cancelScanButton.setDisable(true);
                    scanProgress.setVisible(false);
                    scanProgress.setManaged(false);
                    int topScore = allRanked.isEmpty() ? 0 : allRanked.get(0).score();
                    scanStatusLabel.setText("Scan finished: " + allRanked.size()
                            + " candidates ranked \u00b7 Top score: " + topScore);
                    window.status("Auto LSB Scan finished with " + allRanked.size() + " candidates");
                });
            }

            @Override
            public void onError(Throwable error) {
                Platform.runLater(() -> {
                    if (sequence != scanSequence) return;
                    autoScanButton.setDisable(false);
                    cancelScanButton.setDisable(true);
                    scanProgress.setVisible(false);
                    scanProgress.setManaged(false);
                    scanStatusLabel.setText("Scan failed: " + error.getMessage());
                    window.status("Auto LSB Scan failed: " + error);
                });
            }
        });
    }

    private void cancelAutoScan() {
        if (activeScanTask != null && !activeScanTask.isCancelled()) {
            activeScanTask.cancel();
            scanStatusLabel.setText("Scan cancelled.");
            autoScanButton.setDisable(false);
            cancelScanButton.setDisable(true);
            scanProgress.setVisible(false);
            scanProgress.setManaged(false);
        }
    }

    private void showCandidateDetails(LsbCandidate candidate) {
        if (candidate == null) {
            candidateDetailBox.setVisible(false);
            candidateDetailBox.setManaged(false);
            return;
        }
        candidateDetailBox.setVisible(true);
        candidateDetailBox.setManaged(true);

        candidateScoreBadge.setText("Score: " + candidate.score());
        candidateTypeBadge.setText(candidate.payloadInfo().description());
        candidateSizeBadge.setText(FxUtils.bytes(candidate.prefixData().length)
                + (candidate.truncated() ? " (Phase 1, full ~" + FxUtils.bytes(candidate.totalBytes()) + ")" : ""));

        candidateConfigLabel.setText(candidate.formattedConfig());
        candidateReasonLabel.setText(candidate.reason());

        copyCandidateTextButton.setDisable(!candidate.payloadInfo().isText()
                && (candidate.payloadInfo().text() == null || candidate.payloadInfo().text().isBlank()));

        // Show preview: text preview if textual, else hex dump
        candidatePreviewArea.setText(candidate.previewText(true, 4096));
        candidatePreviewArea.positionCaret(0);
    }

    /**
     * Exact Apply Settings mapping back to manual Extract controls.
     */
    public void applyCandidateSettings(LsbCandidate candidate) {
        if (candidate == null) return;
        ExtractionOptions opt = candidate.options();

        // 1. Bit planes
        for (Channel channel : Channel.values()) {
            for (int plane = 0; plane < 8; plane++) {
                planeBoxes.get(channel)[plane].setSelected(opt.isSelected(channel, plane));
            }
            allBoxes.get(channel).setSelected(allSelected(planeBoxes.get(channel)));
        }

        // 2. Traversal
        rowFirstButton.setSelected(opt.rowFirst());
        columnFirstButton.setSelected(!opt.rowFirst());

        // 3. Bit order
        lsbFirstButton.setSelected(opt.lsbFirst());
        msbFirstButton.setSelected(!opt.lsbFirst());

        // 4. Channel order
        orderChoice.setValue(opt.order());

        // 5. Inversion
        invertBits.setSelected(opt.invertBits());

        updateSizeLabel();
        if (window != null) {
            window.status("Applied: " + candidate.formattedConfig() + " to manual controls");
        }
    }

    private void saveCandidatePayload(LsbCandidate candidate) {
        if (candidate == null) return;
        ImageData image = window.displayedImage();
        if (image == null) return;

        Roi region = targetRegion(image);
        String ext = candidate.payloadInfo().suggestedExtension();
        String defaultExt = ext.isEmpty() ? "bin" : ext;
        String suggested = (window.document().isOpen() ? window.document().fileName() + "-auto" : "auto-extract")
                + "." + defaultExt;

        FxUtils.chooseFileToSave(window.window(), "Save extracted payload", suggested, defaultExt,
                candidate.payloadInfo().type().description()).ifPresent(path -> {
            window.status("Extracting complete payload...");
            runner.submit("save full payload", () -> {
                if (!candidate.truncated()) {
                    return candidate.prefixData();
                }
                return DataExtractor.extract(image, region, candidate.options(), Integer.MAX_VALUE).data();
            }, fullData -> {
                window.runFileJob(() -> {
                    Files.write(path, fullData);
                    return path;
                }, saved -> window.status("Saved " + FxUtils.bytes(fullData.length) + " to " + saved.getFileName()));
            }, error -> window.status("Extraction failed: " + error));
        });
    }

    private void copyCandidateHex(LsbCandidate candidate) {
        if (candidate == null || candidate.prefixData().length == 0) return;
        int copied = FxUtils.copyHex(candidate.prefixData(), 32);
        window.status("Copied hex for " + copied + " bytes to clipboard");
    }

    private void copyCandidateText(LsbCandidate candidate) {
        if (candidate == null) return;
        String text = candidate.payloadInfo().text();
        if (text != null && !text.isEmpty()) {
            FxUtils.copyText(text);
            window.status("Copied candidate text to clipboard");
        } else if (candidate.prefixData().length > 0) {
            FxUtils.copyText(new String(candidate.prefixData(), StandardCharsets.UTF_8));
            window.status("Copied candidate bytes as text to clipboard");
        }
    }

    // =============================================================== Manual UI Construction

    private GridPane buildPlanesGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(4);
        grid.add(new Label(""), 0, 0);
        Label allHeader = new Label("All");
        allHeader.getStyleClass().add("steg-hint");
        allHeader.setMinWidth(Region.USE_PREF_SIZE);
        grid.add(allHeader, 1, 0);

        for (int plane = 7; plane >= 0; plane--) {
            String colText = plane == 7 ? "7 (MSB)" : (plane == 0 ? "0 (LSB)" : String.valueOf(plane));
            Label header = new Label(colText);
            header.setMinWidth(Region.USE_PREF_SIZE);
            header.setAlignment(Pos.CENTER);
            header.getStyleClass().add("steg-hint");
            grid.add(header, 2 + (7 - plane), 0);
        }

        int row = 1;
        for (Channel channel : Channel.values()) {
            Label name = new Label(channel.label());
            name.setMinWidth(Region.USE_PREF_SIZE);
            String channelClass = switch (channel) {
                case ALPHA -> "steg-channel-alpha";
                case RED -> "steg-channel-red";
                case GREEN -> "steg-channel-green";
                case BLUE -> "steg-channel-blue";
            };
            name.getStyleClass().add(channelClass);
            grid.add(name, 0, row);
            CheckBox all = new CheckBox();
            all.setTooltip(new Tooltip("Select every " + channel.label() + " plane"));
            grid.add(all, 1, row);
            CheckBox[] boxes = new CheckBox[8];
            for (int plane = 7; plane >= 0; plane--) {
                CheckBox box = new CheckBox();
                box.setTooltip(new Tooltip(channel.label() + " plane " + plane));
                boxes[plane] = box;
                grid.add(box, 2 + (7 - plane), row);
            }
            all.setOnAction(event -> {
                for (CheckBox box : boxes) {
                    box.setSelected(all.isSelected());
                }
                updateSizeLabel();
            });
            for (CheckBox box : boxes) {
                box.setOnAction(event -> {
                    all.setSelected(allSelected(boxes));
                    updateSizeLabel();
                });
            }
            planeBoxes.put(channel, boxes);
            allBoxes.put(channel, all);
            row++;
        }
        return grid;
    }

    private static boolean allSelected(CheckBox[] boxes) {
        for (CheckBox box : boxes) {
            if (!box.isSelected()) {
                return false;
            }
        }
        return true;
    }

    private Node quickSelectionRow() {
        Button lsb = new Button("RGB LSB");
        lsb.getStyleClass().add("steg-pill-btn");
        lsb.setMinWidth(Region.USE_PREF_SIZE);
        lsb.setTooltip(new Tooltip("Select the least significant bit of red, green "
                + "and blue, where LSB steganography normally lives"));
        lsb.setOnAction(event -> applySelection(new int[] {1, 0, 2, 0, 3, 0}));

        Button alphaLsb = new Button("Alpha + RGB LSB");
        alphaLsb.getStyleClass().add("steg-pill-btn");
        alphaLsb.setMinWidth(Region.USE_PREF_SIZE);
        alphaLsb.setOnAction(event -> applySelection(new int[] {0, 0, 1, 0, 2, 0, 3, 0}));

        Button msb = new Button("RGB MSB (7)");
        msb.getStyleClass().add("steg-pill-btn");
        msb.setMinWidth(Region.USE_PREF_SIZE);
        msb.setOnAction(event -> applySelection(new int[] {1, 7, 2, 7, 3, 7}));

        Button everything = new Button("All 32");
        everything.getStyleClass().add("steg-pill-btn");
        everything.setMinWidth(Region.USE_PREF_SIZE);
        everything.setTooltip(new Tooltip("Select all 32 bit planes"));
        everything.setOnAction(event -> {
            for (Channel channel : Channel.values()) {
                for (int plane = 0; plane < 8; plane++) {
                    planeBoxes.get(channel)[plane].setSelected(true);
                }
                allBoxes.get(channel).setSelected(true);
            }
            updateSizeLabel();
        });

        Button nothing = new Button("Clear");
        nothing.getStyleClass().add("steg-pill-btn");
        nothing.setMinWidth(Region.USE_PREF_SIZE);
        nothing.setOnAction(event -> applySelection(new int[0]));

        FlowPane row = new FlowPane(6, 6, lsb, alphaLsb, msb, everything, nothing);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Selects exactly the given {@code channel * 10 + plane} pairs. */
    private void applySelection(int[] pairs) {
        for (Channel channel : Channel.values()) {
            for (int plane = 0; plane < 8; plane++) {
                planeBoxes.get(channel)[plane].setSelected(false);
            }
            allBoxes.get(channel).setSelected(false);
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Channel channel = Channel.values()[pairs[i]];
            planeBoxes.get(channel)[pairs[i + 1]].setSelected(true);
        }
        for (Channel channel : Channel.values()) {
            allBoxes.get(channel).setSelected(allSelected(planeBoxes.get(channel)));
        }
        updateSizeLabel();
    }

    private GridPane buildOptionsGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        rowFirstButton.setToggleGroup(traversalGroup);
        rowFirstButton.setSelected(true);
        rowFirstButton.setWrapText(true);
        columnFirstButton.setToggleGroup(traversalGroup);
        columnFirstButton.setWrapText(true);

        grid.add(new Label("Traverse"), 0, 0);
        grid.add(rowFirstButton, 1, 0);
        grid.add(columnFirstButton, 2, 0);

        msbFirstButton.setToggleGroup(bitOrderGroup);
        msbFirstButton.setSelected(true);
        msbFirstButton.setMinWidth(Region.USE_PREF_SIZE);
        msbFirstButton.setWrapText(true);
        lsbFirstButton.setToggleGroup(bitOrderGroup);
        lsbFirstButton.setMinWidth(Region.USE_PREF_SIZE);
        lsbFirstButton.setWrapText(true);

        grid.add(new Label("Bit order"), 0, 1);
        grid.add(msbFirstButton, 1, 1);
        grid.add(lsbFirstButton, 2, 1);

        orderChoice.getItems().setAll(RgbOrder.values());
        orderChoice.setValue(RgbOrder.RGB);
        orderChoice.setMinWidth(Region.USE_PREF_SIZE);
        Label alphaNote = new Label("(alpha is always read first)");
        alphaNote.setWrapText(true);
        alphaNote.getStyleClass().add("steg-hint");

        grid.add(new Label("Channel order"), 0, 2);
        grid.add(orderChoice, 1, 2);
        grid.add(alphaNote, 2, 2);

        invertBits.setWrapText(true);
        useSelection.setWrapText(true);
        useSelection.setTooltip(new Tooltip("Extract only the region dragged in the viewport (selection mode)"));
        useSelection.setDisable(true);

        grid.add(new Label("Options"), 0, 3);
        grid.add(invertBits, 1, 3);
        grid.add(useSelection, 2, 3);

        maskLabel.setWrapText(true);
        grid.add(new Label("Legacy mask"), 0, 4);
        grid.add(maskLabel, 1, 4, 2, 1);

        for (CheckBox box : List.of(invertBits, useSelection)) {
            box.setOnAction(event -> updateSizeLabel());
        }
        for (RadioButton button : List.of(rowFirstButton, columnFirstButton, msbFirstButton,
                lsbFirstButton)) {
            button.setOnAction(event -> updateSizeLabel());
        }
        orderChoice.setOnAction(event -> updateSizeLabel());
        return grid;
    }

    @Override
    public void onDocumentChanged() {
        runner.cancel();
        cancelAutoScan();
        extractionSequence++;
        extracted = new byte[0];
        lastOptions = null;
        preview.clear();
        signatureLabel.setText("");
        candidateList.clear();
        candidateDetailBox.setVisible(false);
        candidateDetailBox.setManaged(false);
        scanStatusLabel.setText("Scan image for hidden LSB data across common CTF configurations.");
        updateButtons(false);
        updateSizeLabel();
    }

    /** Called when the viewport selection changes. */
    public void onSelectionChanged(Roi selection) {
        boolean hasSelection = selection != null && selection.isNotEmpty();
        useSelection.setDisable(!hasSelection);
        if (!hasSelection && useSelection.isSelected()) {
            useSelection.setSelected(false);
        }
        updateSizeLabel();
    }

    /** The extraction options currently configured in the panel. */
    public ExtractionOptions options() {
        ExtractionOptions options = ExtractionOptions.none()
                .withOrder(orderChoice.getValue() == null ? RgbOrder.RGB : orderChoice.getValue())
                .withLsbFirst(lsbFirstButton.isSelected())
                .withRowFirst(!columnFirstButton.isSelected())
                .withInvertBits(invertBits.isSelected());
        for (Channel channel : Channel.values()) {
            for (int plane = 0; plane < 8; plane++) {
                if (planeBoxes.get(channel)[plane].isSelected()) {
                    options = options.with(channel, plane, true);
                }
            }
        }
        return options;
    }

    private Roi targetRegion(ImageData image) {
        if (useSelection.isSelected() && window.selection() != null && window.selection().isNotEmpty()) {
            return window.selection().clampTo(image.width(), image.height());
        }
        return Roi.whole(image.width(), image.height());
    }

    private void updateSizeLabel() {
        maskLabel.setText("0x" + Integer.toHexString(options().argbMask()));
        ImageData image = window != null ? window.displayedImage() : null;
        if (image == null) {
            sizeLabel.setText("No image open");
            return;
        }
        Roi region = targetRegion(image);
        long pixels = (long) region.width() * region.height();
        long bytes = options().outputBytesFor(pixels);
        sizeLabel.setText(options().selectedCount() + " plane(s) selected \u00b7 "
                + options().describe() + " \u00b7 " + (region.area() == image.width() * image.height()
                        ? "whole image" : "selection " + region.describe())
                + " \u00b7 " + FxUtils.bytes(bytes) + " of data");
    }

    private void extract() {
        ImageData image = window.displayedImage();
        if (image == null) {
            window.status("Open an image first");
            return;
        }
        ExtractionOptions options = options();
        if (options.isEmpty()) {
            window.status("Select at least one bit plane to extract");
            return;
        }
        lastOptions = options;
        Roi region = targetRegion(image);
        int sequence = ++extractionSequence;
        previewButton.setDisable(true);
        window.status("Extracting...");
        runner.submit("extract", () -> {
            DataExtractor.Result result = DataExtractor.extract(image, region, options, Integer.MAX_VALUE);
            return new Extracted(result, PayloadDetector.detect(result.data(), null));
        }, outcome -> {
            if (sequence != extractionSequence) return;
            extracted = outcome.result().data();
            showPreview(outcome.info());
            updateButtons(extracted.length > 0);
            window.status("Extracted " + FxUtils.bytes(extracted.length) + " from "
                    + options.selectedCount() + " plane(s)");
        }, error -> {
            updateButtons(false);
            window.status("Extraction failed: " + error);
        });
    }

    private record Extracted(DataExtractor.Result result, PayloadInfo info) {}

    @Override
    public void dispose() {
        cancelAutoScan();
        runner.close();
    }

    private void showPreview(PayloadInfo info) {
        int limit = previewLimit.getValue() == null ? 4096 : previewLimit.getValue();
        preview.setText(HexDump.format(extracted, 0, extracted.length, limit, includeHex.isSelected()));
        preview.positionCaret(0);
        if (extracted.length == 0) {
            signatureLabel.setText("Nothing extracted.");
            return;
        }
        String summary = "Detected content: " + info.type().description()
                + " \u00b7 suggested extension " + (info.suggestedExtension().isEmpty()
                        ? "(none)" : "." + info.suggestedExtension())
                + " \u00b7 entropy " + String.format(java.util.Locale.ROOT, "%.2f", info.entropy());
        if (!info.notes().isEmpty()) {
            summary += "\n" + String.join(" \u00b7 ", info.notes());
        }
        signatureLabel.setText(summary);
    }

    private void updateButtons(boolean enabled) {
        previewButton.setDisable(false);
        saveBinaryButton.setDisable(!enabled);
        saveTextButton.setDisable(!enabled);
        copyHexButton.setDisable(!enabled);
    }

    private void saveBinary() {
        if (extracted.length == 0) {
            return;
        }
        String suggested = window.document().isOpen()
                ? window.document().fileName() + "-extract.bin"
                : "extract.bin";
        byte[] data = extracted;
        FxUtils.chooseFileToSave(window.window(), "Save extracted data", suggested, "bin", "Binary files")
                .ifPresent(path -> window.runFileJob(() -> Files.write(path, data),
                        saved -> window.status("Saved " + FxUtils.bytes(data.length) + " to " + saved.getFileName())));
    }

    private void saveText() {
        if (extracted.length == 0) {
            return;
        }
        String suggested = window.document().isOpen()
                ? window.document().fileName() + "-extract.txt"
                : "extract.txt";
        byte[] data = extracted;
        boolean hex = includeHex.isSelected();
        FxUtils.chooseFileToSave(window.window(), "Save extract preview", suggested, "txt", "Text files")
                .ifPresent(path -> window.runFileJob(() -> {
                    try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                        HexDump.write(writer, data, hex);
                    }
                    return path;
                }, saved -> window.status("Saved the preview to " + saved.getFileName())));
    }
}
