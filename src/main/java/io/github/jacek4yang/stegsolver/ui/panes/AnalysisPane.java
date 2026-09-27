package io.github.jacek4yang.stegsolver.ui.panes;

import io.github.jacek4yang.stegsolver.parser.FileAnalyzer;
import io.github.jacek4yang.stegsolver.parser.FileReport;
import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import io.github.jacek4yang.stegsolver.ui.ToolPane;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Structural file analysis: the PNG/JPEG/GIF/BMP container report of the original "file format" window,
 * with strict bounds checking and without the {@code Swing} HTML pane.
 *
 * <p>The report is plain text in a monospaced area, so it can be searched, copied and saved, and the
 * warnings are listed separately at the top where they are actually noticed.</p>
 */
public final class AnalysisPane implements ToolPane {

    private final MainWindow window;
    private final TextArea reportArea = new TextArea();
    private final Label summaryLabel = new Label("Open an image, then run the analysis.");
    private final Label warningsLabel = new Label();

    public AnalysisPane(MainWindow window) {
        this.window = window;
        reportArea.setEditable(false);
        reportArea.setWrapText(false);
        reportArea.getStyleClass().add("mono");
        reportArea.setPrefRowCount(24);
        warningsLabel.setWrapText(true);
        warningsLabel.getStyleClass().add("steg-badge-warning");
        warningsLabel.setVisible(false);
        warningsLabel.setManaged(false);
    }

    @Override
    public String title() {
        return "Analysis";
    }

    @Override
    public Node content() {
        Button analyseButton = new Button("Analyse file");
        analyseButton.setOnAction(event -> analyse());
        Button copyButton = new Button("Copy report");
        copyButton.setOnAction(event -> FxUtils.copyText(reportArea.getText()));
        Button saveButton = new Button("Save report...");
        saveButton.setOnAction(event -> saveReport());

        HBox buttons = new HBox(8, analyseButton, copyButton, saveButton);
        VBox box = new VBox(8, buttons, warningsLabel, summaryLabel, reportArea);
        VBox.setVgrow(reportArea, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    @Override
    public void onDocumentChanged() {
        reportArea.clear();
        warningsLabel.setVisible(false);
        warningsLabel.setManaged(false);
        summaryLabel.setText(window.document().isOpen()
                ? "Press \"Analyse file\" to inspect the container of " + window.document().fileName()
                : "Open an image, then run the analysis.");
    }

    private void analyse() {
        Path path = window.document().path();
        if (path == null) {
            window.status("Open an image first");
            return;
        }
        window.status("Analysing " + path.getFileName() + "...");
        // The analyser reads the whole file, so it runs on the background executor.
        Thread.ofVirtual().name("stegsolver-analyse").start(() -> {
            FileReport report = FileAnalyzer.analyze(path);
            javafx.application.Platform.runLater(() -> show(report));
        });
    }

    private void show(FileReport report) {
        reportArea.setText(report.toText());
        reportArea.positionCaret(0);
        summaryLabel.setText(report.summary());
        if (report.hasWarnings()) {
            warningsLabel.setText(report.warnings().size() + " warning(s): "
                    + String.join(" | ", report.warnings()));
            warningsLabel.setVisible(true);
            warningsLabel.setManaged(true);
        } else {
            warningsLabel.setVisible(false);
            warningsLabel.setManaged(false);
        }
        window.status("Analysis finished: " + report.summary());
    }

    private void saveReport() {
        if (reportArea.getText().isEmpty()) {
            window.status("Nothing to save: run the analysis first");
            return;
        }
        String suggested = window.document().isOpen()
                ? window.document().fileName() + "-analysis.txt"
                : "analysis.txt";
        FxUtils.chooseFileToSave(window.window(), "Save analysis report", suggested, "txt", "Text files")
                .ifPresent(path -> {
                    try {
                        Files.writeString(path, reportArea.getText(), StandardCharsets.UTF_8);
                        window.status("Saved the report to " + path.getFileName());
                    } catch (IOException e) {
                        FxUtils.error(window.window(), "Could not save the report", String.valueOf(e), e);
                    }
                });
    }
}
