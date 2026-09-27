package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.barcode.PayloadDetector;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.core.HexDump;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.extract.DataExtractor;
import io.github.jacek4yang.stegsolver.extract.ExtractionOptions;
import io.github.jacek4yang.stegsolver.extract.RgbOrder;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Data extraction: the bit plane selection, traversal and bit order options of the original extractor,
 * with the two features it only had as TODOs (bit inversion and a bounded preview) and with a running
 * total instead of a preview that silently produced megabytes of text.
 */
public final class ExtractPane implements ToolPane {

    private final MainWindow window;
    private final io.github.jacek4yang.stegsolver.core.CoalescingJobRunner runner =
            new io.github.jacek4yang.stegsolver.core.CoalescingJobRunner("stegsolver-extract", Platform::runLater);
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
        includeHex.setSelected(true);
        previewLimit.getItems().setAll(1024, 4096, 16384, 65536);
        previewLimit.setValue(4096);
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("mono");
        preview.setPrefRowCount(14);
        signatureLabel.setWrapText(true);
        sizeLabel.getStyleClass().add("steg-hint");
        maskLabel.getStyleClass().add("mono");
        previewButton.setDefaultButton(false);
        previewButton.setOnAction(event -> extract());
        saveBinaryButton.setOnAction(event -> saveBinary());
        saveTextButton.setOnAction(event -> saveText());
        copyHexButton.setOnAction(event -> FxUtils.copyHex(extracted, 32));
        // The controls have to exist before the default selection can be applied, so the grids are built
        // here and only assembled into the panel by content().
        planesGrid = buildPlanesGrid();
        optionsGrid = buildOptionsGrid();
        applySelection(DEFAULT_SELECTION);
        updateButtons(false);
    }

    @Override
    public String title() {
        return "Extract";
    }

    @Override
    public Node content() {
        preview.setPrefHeight(200);

        VBox planesCard = new VBox(8,
                section("Bit Planes to Extract"),
                planesGrid,
                quickSelectionRow());
        planesCard.getStyleClass().add("steg-card");

        VBox optionsCard = new VBox(8,
                section("Extraction Settings"),
                optionsGrid);
        optionsCard.getStyleClass().add("steg-card");

        HBox actionRow = new HBox(8, previewButton, saveBinaryButton, saveTextButton, copyHexButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        HBox previewControls = new HBox(8, new Label("Preview"), includeHex, previewLimit);
        previewControls.setAlignment(Pos.CENTER_LEFT);

        VBox resultsCard = new VBox(8,
                section("Extracted Data & Preview"),
                sizeLabel,
                signatureLabel,
                actionRow,
                previewControls,
                preview);
        resultsCard.getStyleClass().add("steg-card");

        VBox box = new VBox(10, planesCard, optionsCard, resultsCard);
        box.setPadding(new Insets(10));
        // The dock can be made narrow and short, so the panel scrolls instead of clipping its controls.
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(0));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private static Label section(String title) {
        Label header = new Label(title);
        header.getStyleClass().add("steg-card-header");
        return header;
    }

    private GridPane buildPlanesGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(4);
        grid.add(new Label(""), 0, 0);
        Label allHeader = new Label("All");
        allHeader.getStyleClass().add("steg-hint");
        grid.add(allHeader, 1, 0);
        for (int plane = 7; plane >= 0; plane--) {
            String colText = plane == 7 ? "7 (MSB)" : (plane == 0 ? "0 (LSB)" : String.valueOf(plane));
            Label header = new Label(colText);
            header.setMinWidth(plane == 7 || plane == 0 ? 38 : 20);
            header.setAlignment(Pos.CENTER);
            header.getStyleClass().add("steg-hint");
            grid.add(header, 2 + (7 - plane), 0);
        }
        int row = 1;
        for (Channel channel : Channel.values()) {
            Label name = new Label(channel.label());
            name.setMinWidth(46);
            String channelClass = switch (channel) {
                case ALPHA -> "steg-channel-alpha";
                case RED -> "steg-channel-red";
                case GREEN -> "steg-channel-green";
                case BLUE -> "steg-channel-blue";
            };
            name.getStyleClass().add(channelClass);
            grid.add(name, 0, row);
            CheckBox all = new CheckBox();
            all.setTooltip(new javafx.scene.control.Tooltip("Select every " + channel.label() + " plane"));
            grid.add(all, 1, row);
            CheckBox[] boxes = new CheckBox[8];
            for (int plane = 7; plane >= 0; plane--) {
                CheckBox box = new CheckBox();
                box.setTooltip(new javafx.scene.control.Tooltip(channel.label() + " plane " + plane));
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
        lsb.setTooltip(new javafx.scene.control.Tooltip("Select the least significant bit of red, green "
                + "and blue, where LSB steganography normally lives"));
        lsb.setOnAction(event -> applySelection(new int[] {1, 0, 2, 0, 3, 0}));
        Button alphaLsb = new Button("Alpha + RGB LSB");
        alphaLsb.getStyleClass().add("steg-pill-btn");
        alphaLsb.setOnAction(event -> applySelection(new int[] {0, 0, 1, 0, 2, 0, 3, 0}));
        Button msb = new Button("RGB MSB (7)");
        msb.getStyleClass().add("steg-pill-btn");
        msb.setOnAction(event -> applySelection(new int[] {1, 7, 2, 7, 3, 7}));
        Button everything = new Button("All 32");
        everything.getStyleClass().add("steg-pill-btn");
        everything.setTooltip(new javafx.scene.control.Tooltip("Select all 32 bit planes"));
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
        nothing.setOnAction(event -> applySelection(new int[0]));
        HBox row = new HBox(5, lsb, alphaLsb, msb, everything, nothing);
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
        grid.setVgap(4);

        rowFirstButton.setToggleGroup(traversalGroup);
        rowFirstButton.setSelected(true);
        columnFirstButton.setToggleGroup(traversalGroup);
        grid.add(new Label("Traverse"), 0, 0);
        grid.add(rowFirstButton, 1, 0);
        grid.add(columnFirstButton, 2, 0);

        msbFirstButton.setToggleGroup(bitOrderGroup);
        msbFirstButton.setSelected(true);
        lsbFirstButton.setToggleGroup(bitOrderGroup);
        grid.add(new Label("Bit order"), 0, 1);
        grid.add(msbFirstButton, 1, 1);
        grid.add(lsbFirstButton, 2, 1);

        orderChoice.getItems().setAll(RgbOrder.values());
        orderChoice.setValue(RgbOrder.RGB);
        grid.add(new Label("Channel order"), 0, 2);
        grid.add(orderChoice, 1, 2);
        grid.add(new Label("(alpha is always read first)"), 2, 2);

        grid.add(new Label("Options"), 0, 3);
        grid.add(invertBits, 1, 3);
        grid.add(useSelection, 2, 3);
        useSelection.setTooltip(new javafx.scene.control.Tooltip(
                "Extract only the region dragged in the viewport (selection mode)"));
        useSelection.setDisable(true);

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
        extractionSequence++;
        extracted = new byte[0];
        lastOptions = null;
        preview.clear();
        signatureLabel.setText("");
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
        ImageData image = window.displayedImage();
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
