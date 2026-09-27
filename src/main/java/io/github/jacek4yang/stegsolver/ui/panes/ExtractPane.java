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
import java.io.IOException;
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
    private final Button saveBinaryButton = new Button("Save binary...");
    private final Button saveTextButton = new Button("Save text...");
    private final Button copyHexButton = new Button("Copy hex");

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
        updateButtons(false);
    }

    @Override
    public String title() {
        return "Extract";
    }

    @Override
    public Node content() {
        VBox box = new VBox(10,
                new Label("Bit planes to extract"),
                planesGrid(),
                quickSelectionRow(),
                optionsGrid(),
                new HBox(8, previewButton, saveBinaryButton, saveTextButton, copyHexButton),
                sizeLabel,
                signatureLabel,
                new HBox(8, new Label("Preview"), includeHex, previewLimit),
                preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(0));
        return box;
    }

    private GridPane planesGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(4);
        grid.setVgap(3);
        grid.add(new Label(""), 0, 0);
        grid.add(new Label("all"), 1, 0);
        for (int plane = 7; plane >= 0; plane--) {
            Label header = new Label(String.valueOf(plane));
            header.setMinWidth(22);
            header.setAlignment(Pos.CENTER);
            grid.add(header, 2 + (7 - plane), 0);
        }
        int row = 1;
        for (Channel channel : Channel.values()) {
            Label name = new Label(channel.label());
            name.setMinWidth(48);
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
        Button lsb = new Button("RGB least significant bits");
        lsb.setOnAction(event -> applySelection(new int[] {1, 0, 2, 0, 3, 0}));
        Button alphaLsb = new Button("Alpha + RGB LSB");
        alphaLsb.setOnAction(event -> applySelection(new int[] {0, 0, 1, 0, 2, 0, 3, 0}));
        Button everything = new Button("All 32 planes");
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
        nothing.setOnAction(event -> applySelection(new int[0]));
        HBox row = new HBox(6, lsb, alphaLsb, everything, nothing);
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

    private Node optionsGrid() {
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
        Thread.ofVirtual().name("stegsolver-extract").start(() -> {
            DataExtractor.Result result = DataExtractor.extract(image, region, options, Integer.MAX_VALUE);
            // Classification is cheap compared to the extraction and gives the analyst an immediate hint.
            PayloadInfo info = PayloadDetector.detect(result.data(), null);
            Platform.runLater(() -> {
                if (sequence != extractionSequence) {
                    return;
                }
                extracted = result.data();
                showPreview(info);
                updateButtons(!result.isEmpty());
                window.status("Extracted " + FxUtils.bytes(result.data().length) + " from "
                        + options.selectedCount() + " plane(s)");
            });
        });
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
        FxUtils.chooseFileToSave(window.window(), "Save extracted data", suggested, "bin", "Binary files")
                .ifPresent(path -> {
                    try {
                        Files.write(path, extracted);
                        window.status("Saved " + FxUtils.bytes(extracted.length) + " to " + path.getFileName());
                    } catch (IOException e) {
                        FxUtils.error(window.window(), "Could not save the data", String.valueOf(e), e);
                    }
                });
    }

    private void saveText() {
        if (extracted.length == 0) {
            return;
        }
        String suggested = window.document().isOpen()
                ? window.document().fileName() + "-extract.txt"
                : "extract.txt";
        FxUtils.chooseFileToSave(window.window(), "Save extract preview", suggested, "txt", "Text files")
                .ifPresent(path -> {
                    try {
                        String text = HexDump.format(extracted, 0, extracted.length, extracted.length,
                                includeHex.isSelected());
                        Files.writeString(path, text, StandardCharsets.UTF_8);
                        window.status("Saved the preview to " + path.getFileName());
                    } catch (IOException e) {
                        FxUtils.error(window.window(), "Could not save the preview", String.valueOf(e), e);
                    }
                });
    }

}
