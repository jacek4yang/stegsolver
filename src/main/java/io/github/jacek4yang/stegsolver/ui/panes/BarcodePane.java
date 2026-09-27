package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.barcode.BarcodeHit;
import io.github.jacek4yang.stegsolver.barcode.HitMerge;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.barcode.ScanOptions;
import io.github.jacek4yang.stegsolver.barcode.ScanResult;
import io.github.jacek4yang.stegsolver.barcode.ScreenGrabber;
import io.github.jacek4yang.stegsolver.barcode.StructuredAppendMerger;
import io.github.jacek4yang.stegsolver.core.HexDump;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ScreenRegionOverlay;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Barcode and QR handling: scanning the whole image, a dragged region or an arbitrary screen region,
 * and turning the result into a payload the analyst can inspect and save.
 *
 * <p>The panel keeps the three representations of a decoded symbol strictly apart — the payload bytes
 * from {@code BYTE_SEGMENTS}, the decoded text and ZXing's raw codewords — and never rebuilds the payload
 * from the text. Payloads are classified, previewed and saved, but never opened, extracted or executed.</p>
 */
public final class BarcodePane implements ToolPane {

    /** Which representation the preview area shows. */
    public enum Representation {
        PAYLOAD("Decoded payload bytes"),
        TEXT("Decoded text"),
        RAW("ZXing decoder raw bytes");

        private final String label;

        Representation(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final MainWindow window;
    private final ObservableList<BarcodeHit> hits = FXCollections.observableArrayList();
    private final ListView<BarcodeHit> hitList = new ListView<>(hits);

    // Scan controls
    private final Button scanImageButton = new Button("Scan image");
    private final Button scanSelectionButton = new Button("Scan region");
    private final Button scanScreenButton = new Button("Scan screen");
    private final Button mergeButton = new Button("Merge append");
    private final Button clearButton = new Button("Clear results");

    private final CheckBox multipleSymbols = new CheckBox("Look for several symbols");
    private final CheckBox tryInverted = new CheckBox("Also scan inverted");
    private final CheckBox tryRotated = new CheckBox("Also scan quarter turns");
    private final CheckBox deepSearch = new CheckBox("Deep search (slow)");
    private final CheckBox alsoScreen = new CheckBox("Include screen region (X11)");

    private final Label summaryLabel = new Label("No scan yet. Scanning is always in the background.");
    private final Label statusLabel = new Label();
    private final Label safetyLabel = new Label("Decoded payloads are never opened, extracted or executed. "
            + "Nothing here runs or unpacks what it finds.");

    // Selected Symbol UI Components
    private final VBox selectedSymbolContainer = new VBox(8);

    // 1. Header Badges
    private final Label formatBadge = new Label();
    private final Label fileTypeBadge = new Label();
    private final Label payloadSizeBadge = new Label();
    private final Label symbolPositionLabel = new Label();

    // 2. Text Payload Section
    private final VBox textPayloadCard = new VBox(6);
    private final Label textCountBadge = new Label();
    private final TextArea textPayloadArea = new TextArea();

    // 3. Binary Payload Section
    private final VBox binaryPayloadCard = new VBox(6);
    private final Label binaryStatusBadge = new Label();
    private final GridPane binaryGrid = new GridPane();
    private final TextField sha256Field = new TextField();
    private final Label binaryNotesLabel = new Label();

    // 4. Decoder Metadata Section
    private final TitledPane metadataPane = new TitledPane();
    private final GridPane metadataGrid = new GridPane();

    // 5. Bounded Hex/ASCII Preview
    private final VBox previewCard = new VBox(6);
    private final ComboBox<Representation> representation = new ComboBox<>();
    private final ComboBox<Integer> previewLimit = new ComboBox<>();
    private final TextArea preview = new TextArea();

    // 6. Action buttons
    private final Button savePayloadButton = new Button("Save Payload");
    private final Button saveTextButton = new Button("Save Text");
    private final Button copyTextButton = new Button("Copy Text");
    private final Button copyHexButton = new Button("Copy Hex");

    private ScanResult current;

    public BarcodePane(MainWindow window) {
        this.window = window;

        configureControls();
        buildSelectedSymbolUI();
        applyDefaults(ScanOptions.defaults());
        updateButtons();
    }

    private void configureControls() {
        hitList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(BarcodeHit hit, boolean empty) {
                super.updateItem(hit, empty);
                setText(empty || hit == null ? null : hit.listLabel());
            }
        });
        hitList.getSelectionModel().selectedItemProperty().addListener((observable, old, hit) -> showHit(hit));
        hitList.setPrefHeight(130);

        summaryLabel.setWrapText(true);
        summaryLabel.getStyleClass().add("steg-hint");

        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("steg-hint");

        safetyLabel.setWrapText(true);
        safetyLabel.getStyleClass().add("steg-hint");

        scanImageButton.setTooltip(new Tooltip("Scan what the viewport currently shows (Ctrl+B)"));
        scanImageButton.setOnAction(event -> window.scanRegion(null, deepSearch.isSelected()));

        scanSelectionButton.setDisable(true);
        scanSelectionButton.setTooltip(new Tooltip("Enable \"Select region\" in the toolbar, drag a box, then scan it"));
        scanSelectionButton.setOnAction(event -> window.scanRegion(window.selection(), deepSearch.isSelected()));

        scanScreenButton.setTooltip(new Tooltip("Draw a box over the screen (X11 only) and scan it"));
        scanScreenButton.setOnAction(event -> scanScreenRegion());

        mergeButton.setTooltip(new Tooltip("Merge QR Structured Append parts across all scans"));
        mergeButton.setOnAction(event -> mergeStructuredAppend());

        clearButton.setOnAction(event -> clearResults());

        savePayloadButton.setTooltip(new Tooltip("Save the binary payload bytes directly to a file"));
        savePayloadButton.setOnAction(event -> savePayload());

        saveTextButton.setTooltip(new Tooltip("Save the decoded text to a .txt file"));
        saveTextButton.setOnAction(event -> saveText());

        copyTextButton.setTooltip(new Tooltip("Copy the decoded text to clipboard"));
        copyTextButton.setOnAction(event -> copyText());

        copyHexButton.setTooltip(new Tooltip("Copy payload bytes formatted as hex to clipboard"));
        copyHexButton.setOnAction(event -> copyHex());

        for (CheckBox box : List.of(multipleSymbols, tryInverted, tryRotated, deepSearch, alsoScreen)) {
            box.setOnAction(event -> updateButtons());
        }
    }

    private void buildSelectedSymbolUI() {
        // Badges row
        formatBadge.getStyleClass().addAll("steg-badge", "steg-badge-info");
        fileTypeBadge.getStyleClass().addAll("steg-badge", "steg-badge-payload");
        payloadSizeBadge.getStyleClass().addAll("steg-badge");
        symbolPositionLabel.getStyleClass().addAll("mono", "steg-hint");

        // 1. Text Payload Card
        textPayloadCard.getStyleClass().add("steg-card");
        Label textTitle = new Label("Text Payload");
        textTitle.getStyleClass().add("steg-card-header");
        textCountBadge.getStyleClass().addAll("steg-badge", "steg-badge-info");

        Region textSpacer = new Region();
        HBox.setHgrow(textSpacer, Priority.ALWAYS);

        Button miniCopyText = new Button("Copy");
        miniCopyText.getStyleClass().add("steg-pill-btn");
        miniCopyText.setOnAction(e -> copyText());
        miniCopyText.disableProperty().bind(copyTextButton.disabledProperty());

        Button miniSaveText = new Button("Save...");
        miniSaveText.getStyleClass().add("steg-pill-btn");
        miniSaveText.setOnAction(e -> saveText());
        miniSaveText.disableProperty().bind(saveTextButton.disabledProperty());

        HBox textHeader = new HBox(6, textTitle, textCountBadge, textSpacer, miniCopyText, miniSaveText);
        textHeader.setAlignment(Pos.CENTER_LEFT);

        textPayloadArea.setEditable(false);
        textPayloadArea.setWrapText(true);
        textPayloadArea.setPrefRowCount(3);
        textPayloadArea.setMaxHeight(80);
        textPayloadCard.getChildren().addAll(textHeader, textPayloadArea);

        // 2. Binary Payload Card
        binaryPayloadCard.getStyleClass().add("steg-card");
        Label binaryTitle = new Label("Binary Payload & File Type");
        binaryTitle.getStyleClass().add("steg-card-header");
        binaryStatusBadge.getStyleClass().addAll("steg-badge");

        Region binarySpacer = new Region();
        HBox.setHgrow(binarySpacer, Priority.ALWAYS);

        Button miniSaveBin = new Button("Save .bin...");
        miniSaveBin.getStyleClass().add("steg-pill-btn");
        miniSaveBin.setOnAction(e -> savePayload());
        miniSaveBin.disableProperty().bind(savePayloadButton.disabledProperty());

        Button miniCopyHex = new Button("Copy Hex");
        miniCopyHex.getStyleClass().add("steg-pill-btn");
        miniCopyHex.setOnAction(e -> copyHex());
        miniCopyHex.disableProperty().bind(copyHexButton.disabledProperty());

        HBox binaryHeader = new HBox(6, binaryTitle, binaryStatusBadge, binarySpacer, miniSaveBin, miniCopyHex);
        binaryHeader.setAlignment(Pos.CENTER_LEFT);

        binaryGrid.getStyleClass().add("steg-meta-grid");
        sha256Field.setEditable(false);
        sha256Field.getStyleClass().addAll("mono", "steg-meta-val");
        sha256Field.setPrefColumnCount(32);

        binaryNotesLabel.setWrapText(true);
        binaryNotesLabel.getStyleClass().add("steg-hint");

        binaryPayloadCard.getChildren().addAll(binaryHeader, binaryGrid, binaryNotesLabel);

        // 3. Decoder Metadata Section
        metadataGrid.getStyleClass().add("steg-meta-grid");
        metadataPane.setText("Decoder Metadata & Codewords");
        metadataPane.setContent(metadataGrid);
        metadataPane.setExpanded(false);

        // 4. Bounded Hex/ASCII Preview
        previewCard.getStyleClass().add("steg-card");
        Label previewTitle = new Label("Hex / ASCII Preview");
        previewTitle.getStyleClass().add("steg-card-header");

        representation.getItems().setAll(Representation.values());
        representation.setValue(Representation.PAYLOAD);
        representation.setOnAction(event -> refreshPreview());

        previewLimit.getItems().setAll(512, 2048, 8192, 65536);
        previewLimit.setValue(2048);
        previewLimit.setOnAction(event -> refreshPreview());

        HBox previewHeader = new HBox(6, previewTitle, new Region(), representation, previewLimit);
        previewHeader.setAlignment(Pos.CENTER_LEFT);

        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("mono");
        preview.setPrefRowCount(10);
        previewCard.getChildren().addAll(previewHeader, preview);

        // Assemble selected symbol container
        HBox badgeBar = new HBox(6, formatBadge, fileTypeBadge, payloadSizeBadge);
        badgeBar.setAlignment(Pos.CENTER_LEFT);

        HBox actionButtonBar = new HBox(6, savePayloadButton, saveTextButton, copyTextButton, copyHexButton);
        actionButtonBar.setAlignment(Pos.CENTER_LEFT);

        selectedSymbolContainer.getChildren().addAll(
                badgeBar,
                symbolPositionLabel,
                actionButtonBar,
                textPayloadCard,
                binaryPayloadCard,
                metadataPane,
                previewCard
        );
        selectedSymbolContainer.setVisible(false);
        selectedSymbolContainer.setManaged(false);
    }

    private void applyDefaults(ScanOptions defaults) {
        multipleSymbols.setSelected(defaults.multipleSymbols());
        tryInverted.setSelected(defaults.tryInverted());
        tryRotated.setSelected(defaults.tryRotated());
        deepSearch.setSelected(defaults.deepSearch());
        alsoScreen.setSelected(true);
    }

    @Override
    public String title() {
        return "Barcode";
    }

    @Override
    public Node content() {
        HBox scanButtons = new HBox(6, scanImageButton, scanSelectionButton, scanScreenButton);
        scanButtons.setAlignment(Pos.CENTER_LEFT);

        VBox options = new VBox(4, multipleSymbols, tryInverted, tryRotated, deepSearch, alsoScreen);
        options.setPadding(new Insets(2, 0, 2, 4));

        HBox mergeClearRow = new HBox(6, mergeButton, clearButton);
        mergeClearRow.setAlignment(Pos.CENTER_LEFT);

        VBox scanCard = new VBox(8,
                section("Barcode & QR Scanner"),
                scanButtons,
                options,
                mergeClearRow,
                summaryLabel
        );
        scanCard.getStyleClass().add("steg-card");

        VBox column = new VBox(10,
                scanCard,
                section("Detected Symbols"),
                hitList,
                selectedSymbolContainer,
                safetyLabel,
                statusLabel
        );
        column.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(0));
        return scroll;
    }

    private static Node section(String title) {
        Label header = new Label(title);
        header.getStyleClass().add("steg-tool-header");
        return header;
    }

    /** Applies the panel's options to a scan request. */
    private ScanOptions options(boolean thorough) {
        return ScanOptions.defaults()
                .withTryHarder(true)
                .withTryInverted(tryInverted.isSelected())
                .withTryRotated(tryRotated.isSelected())
                .withMultipleSymbols(multipleSymbols.isSelected())
                .withDeepSearch(deepSearch.isSelected() || thorough)
                .withTimeBudgetMillis(deepSearch.isSelected() ? 45_000 : 15_000);
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Lets the user drag a rectangle over the screen and scans what is inside it.
     */
    public void scanScreenRegion() {
        if (ScreenGrabber.isWaylandSession()) {
            FxUtils.warn(window.window(), "Screen capture is not available",
                    "This session is " + ScreenGrabber.sessionDescription() + ".\n\n"
                            + "Reading another window's pixels requires X11; on Wayland the compositor "
                            + "does not allow it. Save the screenshot to a file and open it instead.");
            return;
        }
        if (!alsoScreen.isSelected()) {
            FxUtils.info(window.window(), "Screen scan disabled",
                    "Enable \"Include screen region (X11)\" first.");
            return;
        }
        Optional<java.awt.Rectangle> region = ScreenRegionOverlay.pickRegion(window.window());
        if (region.isEmpty()) {
            window.status("Screen scan cancelled");
            return;
        }
        java.awt.Rectangle device = region.get();
        window.status("Capturing " + device.width + "x" + device.height + " from the screen...");
        Thread.ofVirtual().name("stegsolver-capture").start(() -> {
            try {
                ImageData captured = ScreenGrabber.capture(device);
                javafx.application.Platform.runLater(() -> {
                    window.showPreview(captured, "Screen capture " + device.width + "x" + device.height);
                    scanImage(captured, "screen region");
                });
            } catch (RuntimeException e) {
                javafx.application.Platform.runLater(() -> FxUtils.error(window.window(),
                        "Screen capture failed", String.valueOf(e), e));
            }
        });
    }

    private void scanImage(ImageData image, String label) {
        window.status("Scanning the " + label + "...");
        Thread.ofVirtual().name("stegsolver-scan").start(() -> {
            ScanResult result = new io.github.jacek4yang.stegsolver.barcode.BarcodeScanner()
                    .scan(image, options(false));
            javafx.application.Platform.runLater(() -> addResults(result, false));
        });
    }

    // ------------------------------------------------------------------ results

    public void addResults(ScanResult result, boolean background) {
        current = result;
        if (background) {
            hits.clear();
        }
        for (BarcodeHit hit : result.hits()) {
            HitMerge.add(hits, hit);
        }
        summaryLabel.setText(result.summary() + summariseNotes(result.notes()));
        statusLabel.setText(result.notes().isEmpty() ? "" : String.join("\n", result.notes()));
        if (window.document().isOpen()) {
            window.document().setBackgroundScan(result);
        }
        if (!hits.isEmpty() && hitList.getSelectionModel().isEmpty()) {
            hitList.getSelectionModel().selectFirst();
        }
        window.viewport().setBarcodeHits(List.copyOf(hits));
        updateButtons();
    }

    private static String summariseNotes(List<String> notes) {
        if (notes.isEmpty()) {
            return "";
        }
        int shown = Math.min(3, notes.size());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            text.append('\n').append(notes.get(i));
        }
        if (notes.size() > shown) {
            text.append('\n').append(notes.size() - shown).append(" more note(s) below");
        }
        return text.toString();
    }

    public ScanResult accumulated() {
        return new ScanResult(List.copyOf(hits), current == null ? List.of() : current.notes(), 0,
                current == null ? Roi.EMPTY : current.scannedRegion());
    }

    public void clearResults() {
        hits.clear();
        current = null;
        summaryLabel.setText("No scan yet.");
        selectedSymbolContainer.setVisible(false);
        selectedSymbolContainer.setManaged(false);
        preview.clear();
        window.viewport().setBarcodeHits(List.of());
        updateButtons();
    }

    private void mergeStructuredAppend() {
        if (hits.isEmpty()) {
            window.status("Nothing to merge");
            return;
        }
        StructuredAppendMerger.MergeOutcome outcome = StructuredAppendMerger.merge(List.copyOf(hits));
        hits.setAll(outcome.unmerged());
        for (BarcodeHit merged : outcome.merged()) {
            HitMerge.add(hits, merged);
        }
        summaryLabel.setText("Merged " + outcome.merged().size() + " structured append sequence(s). "
                + String.join(" \u00b7 ", outcome.notes()));
        window.viewport().setBarcodeHits(List.copyOf(hits));
        updateButtons();
    }

    private BarcodeHit selected() {
        return hitList.getSelectionModel().getSelectedItem();
    }

    // ------------------------------------------------------------------ details

    private void showHit(BarcodeHit hit) {
        if (hit == null) {
            selectedSymbolContainer.setVisible(false);
            selectedSymbolContainer.setManaged(false);
            preview.clear();
            updateButtons();
            return;
        }

        selectedSymbolContainer.setVisible(true);
        selectedSymbolContainer.setManaged(true);

        // 1. Header Badges
        formatBadge.setText(hit.format().name());
        PayloadInfo info = hit.payloadInfo();
        fileTypeBadge.setText(info.type().description());
        payloadSizeBadge.setText(hit.hasBinaryPayload()
                ? hit.payloadSize() + " bytes binary"
                : (hit.text() != null ? hit.text().length() + " chars text" : "empty"));

        symbolPositionLabel.setText("Bounds: " + (hit.bounds() == null ? "unknown" : hit.bounds().describe())
                + " \u00b7 Rotation: " + hit.rotationDegrees() + "\u00b0"
                + (hit.inverted() ? " (inverted)" : ""));

        // 2. Text Payload Card
        boolean hasText = hit.text() != null && !hit.text().isEmpty();
        if (hasText) {
            textCountBadge.setText(hit.text().length() + " chars");
            textPayloadArea.setText(hit.text());
        } else {
            textCountBadge.setText("none");
            textPayloadArea.setText("(no decoded text payload in this symbol)");
        }

        // 3. Binary Payload Card
        binaryGrid.getChildren().clear();
        int row = 0;
        addMetaRow(binaryGrid, row++, "Detected File Type", info.type().description());
        addMetaRow(binaryGrid, row++, "Suggested Extension", info.suggestedExtension().isEmpty() ? "(none)"
                : "." + info.suggestedExtension());
        addMetaRow(binaryGrid, row++, "Payload Size", hit.hasBinaryPayload()
                ? hit.payloadSize() + " bytes" : "0 bytes (no BYTE_SEGMENTS)");
        addMetaRow(binaryGrid, row++, "Entropy", String.format(java.util.Locale.ROOT,
                "%.2f bits/byte (%.2f normalised)", info.entropy(), info.normalisedEntropy()));

        Label shaLabel = new Label("SHA-256");
        shaLabel.getStyleClass().add("steg-meta-key");
        sha256Field.setText(info.sha256());
        binaryGrid.add(shaLabel, 0, row);
        binaryGrid.add(sha256Field, 1, row++);

        binaryStatusBadge.setText(hit.hasBinaryPayload() ? "BINARY PAYLOAD PRESENT" : "TEXT ONLY");
        binaryStatusBadge.getStyleClass().setAll("steg-badge",
                hit.hasBinaryPayload() ? "steg-badge-payload" : "steg-badge-warning");

        if (!info.notes().isEmpty()) {
            binaryNotesLabel.setText(String.join(" \u00b7 ", info.notes()));
            binaryNotesLabel.setVisible(true);
            binaryNotesLabel.setManaged(true);
        } else {
            binaryNotesLabel.setText("");
            binaryNotesLabel.setVisible(false);
            binaryNotesLabel.setManaged(false);
        }

        // 4. Decoder Metadata
        metadataGrid.getChildren().clear();
        int metaRow = 0;
        addMetaRow(metadataGrid, metaRow++, "Symbology", hit.format().name());
        addMetaRow(metadataGrid, metaRow++, "Raw Codewords", hit.decoderRawBytes() == null ? "none"
                : hit.decoderRawBytes().length + " bytes (codewords, not payload)");
        if (hit.structuredAppend() != null) {
            addMetaRow(metadataGrid, metaRow++, "Structured Append", hit.structuredAppend().describe());
        }
        for (Map.Entry<String, String> entry : hit.metadata().entrySet()) {
            addMetaRow(metadataGrid, metaRow++, entry.getKey(), entry.getValue());
        }

        refreshPreview();
        updateButtons();
    }

    private static void addMetaRow(GridPane grid, int row, String key, String val) {
        Label k = new Label(key);
        k.getStyleClass().add("steg-meta-key");
        k.setMinWidth(120);
        Label v = new Label(val);
        v.getStyleClass().add("steg-meta-val");
        v.setWrapText(true);
        grid.add(k, 0, row);
        grid.add(v, 1, row);
    }

    private void refreshPreview() {
        BarcodeHit hit = selected();
        if (hit == null) {
            preview.clear();
            return;
        }
        int limit = previewLimit.getValue() == null ? 2048 : previewLimit.getValue();
        byte[] data = switch (representation.getValue()) {
            case PAYLOAD -> hit.payloadOrEmpty();
            case TEXT -> hit.text() == null ? new byte[0] : hit.text().getBytes(StandardCharsets.UTF_8);
            case RAW -> hit.decoderRawBytes() == null ? new byte[0] : hit.decoderRawBytes();
        };
        if (data.length == 0) {
            preview.setText(representation.getValue() == Representation.PAYLOAD
                    ? "No payload bytes: the decoder returned no BYTE_SEGMENTS for this symbol. "
                            + "Only the decoded text is available."
                    : "Nothing to show.");
            return;
        }
        String header = representation.getValue() == Representation.TEXT
                ? "Text view: this is the decoded text re-encoded as UTF-8 for display only, it is not "
                        + "the payload.\n\n"
                : "";
        preview.setText(header + HexDump.format(data, 0, data.length, limit, true));
        preview.positionCaret(0);
    }

    private void updateButtons() {
        BarcodeHit hit = selected();
        boolean hasPayload = hit != null && hit.hasBinaryPayload();
        boolean hasText = hit != null && hit.text() != null && !hit.text().isEmpty();
        savePayloadButton.setDisable(!hasPayload);
        copyHexButton.setDisable(hit == null || (hit.payloadOrEmpty().length == 0
                && (hit.decoderRawBytes() == null || hit.decoderRawBytes().length == 0)));
        saveTextButton.setDisable(!hasText);
        copyTextButton.setDisable(!hasText);
        scanSelectionButton.setDisable(window.selection() == null || window.selection().isEmpty());
        savePayloadButton.setTooltip(new Tooltip(hasPayload
                ? "Write the exact payload bytes to a file (suggested: ."
                        + hit.payloadInfo().suggestedExtension() + ")"
                : "No binary payload available"));
    }

    public void onSelectionChanged(Roi selection) {
        scanSelectionButton.setDisable(selection == null || selection.isEmpty());
    }

    // ------------------------------------------------------------------ actions

    private void savePayload() {
        BarcodeHit hit = selected();
        if (hit == null || !hit.hasBinaryPayload()) {
            return;
        }
        String extension = hit.payloadInfo().suggestedExtension();
        String suggested = suggestedName(hit, extension.isEmpty() ? "bin" : extension);
        FxUtils.chooseFileToSave(window.window(), "Save decoded payload", suggested,
                extension.isEmpty() ? "bin" : extension, hit.payloadInfo().type().description())
                .ifPresent(path -> writeBytes(path, hit.payload(),
                        "payload (" + hit.payloadInfo().type().description() + ")"));
    }

    private void saveText() {
        BarcodeHit hit = selected();
        if (hit == null || hit.text() == null || hit.text().isEmpty()) {
            return;
        }
        String suggested = suggestedName(hit, "txt");
        FxUtils.chooseFileToSave(window.window(), "Save decoded text", suggested, "txt", "Text files")
                .ifPresent(path -> {
                    try {
                        Files.writeString(path, hit.text(), StandardCharsets.UTF_8);
                        window.status("Saved the decoded text to " + path.getFileName());
                    } catch (IOException e) {
                        FxUtils.error(window.window(), "Could not save the text", String.valueOf(e), e);
                    }
                });
    }

    private void writeBytes(Path path, byte[] data, String description) {
        try {
            Files.write(path, data);
            window.status("Saved the " + description + " to " + path.getFileName()
                    + " (" + FxUtils.bytes(data.length) + ")");
        } catch (IOException e) {
            FxUtils.error(window.window(), "Could not save the payload", String.valueOf(e), e);
        }
    }

    private String suggestedName(BarcodeHit hit, String extension) {
        String base = window.document().isOpen()
                ? stripExtension(window.document().fileName()) + "-barcode" + (hits.indexOf(hit) + 1)
                : "payload";
        return base + "." + extension;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void copyText() {
        BarcodeHit hit = selected();
        if (hit != null && hit.text() != null) {
            FxUtils.copyText(hit.text());
            window.status("Copied the decoded text to the clipboard");
        }
    }

    private void copyHex() {
        BarcodeHit hit = selected();
        if (hit == null) {
            return;
        }
        byte[] data = representation.getValue() == Representation.RAW && hit.decoderRawBytes() != null
                ? hit.decoderRawBytes() : hit.payloadOrEmpty();
        if (data.length == 0) {
            FxUtils.copyText(HexDump.hex(hit.decoderRawBytes(), 0, hit.decoderRawBytes().length, 4096));
            window.status("Payload bytes are not available; copied the decoder raw bytes instead");
            return;
        }
        FxUtils.copyHex(data, 32);
        window.status("Copied " + data.length + " bytes as hex to the clipboard");
    }

    @Override
    public void onViewChanged() {
        updateButtons();
    }

    @Override
    public void onDocumentChanged() {
        clearResults();
    }
}
