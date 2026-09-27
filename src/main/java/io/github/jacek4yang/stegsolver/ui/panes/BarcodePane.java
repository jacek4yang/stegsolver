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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Barcode and QR handling: scanning the whole image, a dragged region or an arbitrary screen region, and
 * turning the result into a payload the analyst can inspect and save.
 *
 * <p>The panel keeps the three representations of a decoded symbol strictly apart — the payload bytes
 * from {@code BYTE_SEGMENTS}, the decoded text and ZXing's raw codewords — and never rebuilds the payload
 * from the text. Payloads are classified, previewed and saved, but never opened, extracted or executed.</p>
 */
public final class BarcodePane implements ToolPane {

    /** Which representation the preview area shows. */
    private enum Representation {
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
    private final TextArea preview = new TextArea();
    private final Label summaryLabel = new Label("No scan yet. Scanning is always in the background.");
    private final Label detailLabel = new Label();
    private final Label safetyLabel = new Label("Decoded payloads are never opened, extracted or executed. "
            + "Nothing here runs or unpacks what it finds.");
    private final Label statusLabel = new Label();
    private final ComboBox<Representation> representation = new ComboBox<>();
    private final ComboBox<Integer> previewLimit = new ComboBox<>();
    private final Button savePayloadButton = new Button("Save payload...");
    private final Button saveTextButton = new Button("Save text...");
    private final Button copyTextButton = new Button("Copy text");
    private final Button copyHexButton = new Button("Copy hex");
    private final CheckBox multipleSymbols = new CheckBox("Look for several symbols");
    private final CheckBox tryInverted = new CheckBox("Also scan inverted");
    private final CheckBox tryRotated = new CheckBox("Also scan quarter turns");
    private final CheckBox deepSearch = new CheckBox("Deep search (slow)");
    private final CheckBox alsoScreen = new CheckBox("Include screen region (X11)");
    private final Button scanImageButton = new Button("Scan displayed image");
    private final Button scanSelectionButton = new Button("Scan selection");
    private final Button scanScreenButton = new Button("Scan screen region");
    private final Button mergeButton = new Button("Merge structured append");
    private final Button clearButton = new Button("Clear results");

    private ScanResult current;

    public BarcodePane(MainWindow window) {
        this.window = window;
        hitList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(BarcodeHit hit, boolean empty) {
                super.updateItem(hit, empty);
                setText(empty || hit == null ? null : hit.listLabel());
            }
        });
        hitList.getSelectionModel().selectedItemProperty().addListener((observable, old, hit) -> showHit(hit));
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("mono");
        preview.setPrefRowCount(12);
        representation.getItems().setAll(Representation.values());
        representation.setValue(Representation.PAYLOAD);
        representation.setOnAction(event -> refreshPreview());
        previewLimit.getItems().setAll(512, 2048, 8192, 65536);
        previewLimit.setValue(2048);
        previewLimit.setOnAction(event -> refreshPreview());
        summaryLabel.setWrapText(true);
        detailLabel.setWrapText(true);
        detailLabel.getStyleClass().add("mono");
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
        mergeButton.setOnAction(event -> mergeStructuredAppend());
        clearButton.setOnAction(event -> clearResults());
        savePayloadButton.setOnAction(event -> savePayload());
        saveTextButton.setOnAction(event -> saveText());
        copyTextButton.setOnAction(event -> copyText());
        copyHexButton.setOnAction(event -> copyHex());
        for (CheckBox box : List.of(multipleSymbols, tryInverted, tryRotated, deepSearch, alsoScreen)) {
            box.setOnAction(event -> updateButtons());
        }
        applyDefaults(ScanOptions.defaults());
        updateButtons();
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
        return "Barcode / QR";
    }

    @Override
    public Node content() {
        hitList.setPrefHeight(150);
        preview.setPrefHeight(190);

        VBox column = new VBox(10,
                section("Scan"),
                new HBox(6, scanImageButton, scanSelectionButton, scanScreenButton),
                optionGrid(),
                new HBox(6, mergeButton, clearButton),
                summaryLabel,
                section("Results"),
                hitList,
                section("Selected symbol"),
                detailLabel,
                new HBox(6, new Label("Preview"), representation, previewLimit),
                preview,
                new HBox(6, savePayloadButton, saveTextButton, copyTextButton, copyHexButton),
                safetyLabel,
                statusLabel);
        column.setPadding(new Insets(10));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(0));
        return scroll;
    }

    private Node section(String title) {
        Label header = new Label(title);
        header.getStyleClass().add("steg-tool-header");
        return header;
    }

    private Node optionGrid() {
        VBox options = new VBox(3, multipleSymbols, tryInverted, tryRotated, deepSearch, alsoScreen);
        return options;
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
     *
     * <p>Reading another window's pixels requires X11: on Wayland the compositor does not expose them,
     * so the limitation is explained instead of returning a black image.</p>
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
        java.util.Optional<java.awt.Rectangle> region = ScreenRegionOverlay.pickRegion(window.window());
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

    /**
     * Adds a scan result.
     *
     * @param background true for the silent scan that follows opening an image, which starts a fresh
     *                   result set; a user initiated scan adds to the set so that several regions can be
     *                   scanned and then merged
     */
    public void addResults(ScanResult result, boolean background) {
        current = result;
        if (background) {
            hits.clear();
        }
        for (BarcodeHit hit : result.hits()) {
            HitMerge.add(hits, hit);
        }
        summaryLabel.setText(result.summary()
                + (result.notes().isEmpty() ? "" : "\n" + String.join(" \u00b7 ", result.notes())));
        if (window.document().isOpen()) {
            window.document().setBackgroundScan(result);
        }
        if (!hits.isEmpty() && hitList.getSelectionModel().isEmpty()) {
            hitList.getSelectionModel().selectFirst();
        }
        window.viewport().setBarcodeHits(List.copyOf(hits));
        updateButtons();
    }

    /** Everything found since the last clear, as a result the host can report. */
    public ScanResult accumulated() {
        return new ScanResult(List.copyOf(hits), current == null ? List.of() : current.notes(), 0,
                current == null ? Roi.EMPTY : current.scannedRegion());
    }

    public void clearResults() {
        hits.clear();
        current = null;
        summaryLabel.setText("No scan yet.");
        detailLabel.setText("");
        preview.clear();
        window.viewport().setBarcodeHits(List.of());
        updateButtons();
    }

    /** Merges the parts of QR Structured Append sequences found so far. */
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
            detailLabel.setText("");
            preview.clear();
            updateButtons();
            return;
        }
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("Symbology", hit.format().name());
        lines.put("Position", hit.bounds() == null ? "unknown" : hit.bounds().describe());
        lines.put("Rotation", hit.rotationDegrees() + "\u00b0" + (hit.inverted() ? " (inverted)" : ""));
        lines.put("Payload bytes", hit.hasBinaryPayload()
                ? hit.payloadSize() + " bytes" : "none (the symbol carries no BYTE_SEGMENTS)");
        lines.put("Decoded text", hit.text() == null ? "none"
                : hit.text().length() + " character(s)");
        lines.put("Decoder raw bytes", hit.decoderRawBytes() == null ? "none"
                : hit.decoderRawBytes().length + " bytes (codewords, not the payload)");
        if (hit.structuredAppend() != null) {
            lines.put("Structured append", hit.structuredAppend().describe());
        }
        PayloadInfo info = hit.payloadInfo();
        lines.put("Classified as", info.type().description());
        lines.put("Suggested extension", info.suggestedExtension().isEmpty() ? "(none)"
                : "." + info.suggestedExtension());
        lines.put("Entropy", String.format(java.util.Locale.ROOT, "%.2f bits/byte (%.2f normalised)", info.entropy(),
                info.normalisedEntropy()));
        lines.put("SHA-256", info.sha256());
        for (Map.Entry<String, String> entry : hit.metadata().entrySet()) {
            lines.put("meta:" + entry.getKey(), entry.getValue());
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : lines.entrySet()) {
            text.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        if (!info.notes().isEmpty()) {
            text.append('\n').append(String.join("\n", info.notes()));
        }
        detailLabel.setText(text.toString());
        refreshPreview();
        updateButtons();
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

    /** Called by the host when the viewport selection changes. */
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

    private void writeBytes(java.nio.file.Path path, byte[] data, String description) {
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
