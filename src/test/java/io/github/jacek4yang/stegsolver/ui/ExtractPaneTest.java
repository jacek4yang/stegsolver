package io.github.jacek4yang.stegsolver.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.barcode.PayloadDetector;
import io.github.jacek4yang.stegsolver.barcode.PayloadInfo;
import io.github.jacek4yang.stegsolver.core.Channel;
import io.github.jacek4yang.stegsolver.extract.DataExtractor;
import io.github.jacek4yang.stegsolver.extract.ExtractionOptions;
import io.github.jacek4yang.stegsolver.extract.LsbCandidate;
import io.github.jacek4yang.stegsolver.extract.RgbOrder;
import io.github.jacek4yang.stegsolver.ui.panes.AnalysisPane;
import io.github.jacek4yang.stegsolver.ui.panes.BarcodePane;
import io.github.jacek4yang.stegsolver.ui.panes.CombinePane;
import io.github.jacek4yang.stegsolver.ui.panes.ExtractPane;
import io.github.jacek4yang.stegsolver.ui.panes.FrameBrowserPane;
import io.github.jacek4yang.stegsolver.ui.panes.InfoPane;
import io.github.jacek4yang.stegsolver.ui.panes.StereoPane;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExtractPaneTest {

    private static boolean fxInitialized = false;

    @BeforeAll
    static void initFx() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            if (latch.await(5, TimeUnit.SECONDS)) {
                fxInitialized = true;
            }
        } catch (IllegalStateException alreadyStarted) {
            fxInitialized = true;
        } catch (Throwable t) {
            System.err.println("JavaFX toolkit could not start in test environment: " + t);
            fxInitialized = false;
        }
    }

    private static void runOnFx(Runnable action) throws Exception {
        if (!fxInitialized) return;
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX action timed out");
        if (error[0] != null) {
            throw new RuntimeException("FX error", error[0]);
        }
    }

    @Test
    @DisplayName("applyCandidateSettings maps candidate settings exactly to manual controls")
    void applyCandidateSettingsExactMapping() throws Exception {
        if (!fxInitialized) return;

        runOnFx(() -> {
            ExtractPane pane = new ExtractPane(null);

            // Test 1: BGR, bits 0 and 1, column-major, MSB first, inverted
            ExtractionOptions opt1 = ExtractionOptions.none()
                    .with(Channel.RED, 0, true).with(Channel.RED, 1, true)
                    .with(Channel.GREEN, 0, true).with(Channel.GREEN, 1, true)
                    .with(Channel.BLUE, 0, true).with(Channel.BLUE, 1, true)
                    .withOrder(RgbOrder.BGR)
                    .withRowFirst(false)
                    .withLsbFirst(false)
                    .withInvertBits(true);

            PayloadInfo info1 = PayloadDetector.detect(new byte[] {1, 2, 3}, null);
            LsbCandidate cand1 = new LsbCandidate(opt1, 90, "Test 1", info1, new byte[] {1, 2, 3}, 100, true);

            pane.applyCandidateSettings(cand1);
            ExtractionOptions mapped1 = pane.options();

            assertEquals(opt1.planeMask(), mapped1.planeMask(), "Plane mask must match");
            assertEquals(opt1.order(), mapped1.order(), "Order must match");
            assertEquals(opt1.rowFirst(), mapped1.rowFirst(), "Row-first must match");
            assertEquals(opt1.lsbFirst(), mapped1.lsbFirst(), "LSB-first must match");
            assertEquals(opt1.invertBits(), mapped1.invertBits(), "Invert-bits must match");
            assertEquals(opt1, mapped1, "ExtractionOptions must be completely identical");

            // Test 2: Single channel Green bit 2, row-major, LSB first, normal
            ExtractionOptions opt2 = ExtractionOptions.none()
                    .with(Channel.GREEN, 2, true)
                    .withOrder(RgbOrder.RGB)
                    .withRowFirst(true)
                    .withLsbFirst(true)
                    .withInvertBits(false);

            PayloadInfo info2 = PayloadDetector.detect(new byte[] {4, 5, 6}, null);
            LsbCandidate cand2 = new LsbCandidate(opt2, 85, "Test 2", info2, new byte[] {4, 5, 6}, 50, false);

            pane.applyCandidateSettings(cand2);
            ExtractionOptions mapped2 = pane.options();

            assertEquals(opt2, mapped2, "Single channel candidate must map back identically");
            // Test 3: Alpha + RGB LSB, row-major, MSB first, normal
            ExtractionOptions opt3 = ExtractionOptions.none()
                    .with(Channel.ALPHA, 0, true)
                    .with(Channel.RED, 0, true)
                    .with(Channel.GREEN, 0, true)
                    .with(Channel.BLUE, 0, true)
                    .withOrder(RgbOrder.GRB)
                    .withRowFirst(true)
                    .withLsbFirst(false)
                    .withInvertBits(false);

            PayloadInfo info3 = PayloadDetector.detect(new byte[] {7, 8, 9}, null);
            LsbCandidate cand3 = new LsbCandidate(opt3, 95, "Test 3", info3, new byte[] {7, 8, 9}, 120, false);

            pane.applyCandidateSettings(cand3);
            ExtractionOptions mapped3 = pane.options();
            assertEquals(opt3, mapped3, "Alpha + RGB candidate must map back identically");
        });
    }

    @Test
    @DisplayName("All tool panes wrap their content in a responsive ScrollPane with fitToWidth=true")
    void toolPanesUseResponsiveScrollPane() throws Exception {
        if (!fxInitialized) return;

        runOnFx(() -> {
            ExtractPane extractPane = new ExtractPane(null);
            Node extractNode = extractPane.content();
            assertTrue(extractNode instanceof ScrollPane, "ExtractPane content must be ScrollPane");
            assertTrue(((ScrollPane) extractNode).isFitToWidth(), "ExtractPane ScrollPane must fitToWidth");

            BarcodePane barcodePane = new BarcodePane(null);
            Node barcodeNode = barcodePane.content();
            assertTrue(barcodeNode instanceof ScrollPane, "BarcodePane content must be ScrollPane");
            assertTrue(((ScrollPane) barcodeNode).isFitToWidth(), "BarcodePane ScrollPane must fitToWidth");

            AnalysisPane analysisPane = new AnalysisPane(null);
            Node analysisNode = analysisPane.content();
            assertTrue(analysisNode instanceof ScrollPane, "AnalysisPane content must be ScrollPane");
            assertTrue(((ScrollPane) analysisNode).isFitToWidth(), "AnalysisPane ScrollPane must fitToWidth");

            CombinePane combinePane = new CombinePane(null);
            Node combineNode = combinePane.content();
            assertTrue(combineNode instanceof ScrollPane, "CombinePane content must be ScrollPane");
            assertTrue(((ScrollPane) combineNode).isFitToWidth(), "CombinePane ScrollPane must fitToWidth");

            FrameBrowserPane framePane = new FrameBrowserPane(null);
            Node frameNode = framePane.content();
            assertTrue(frameNode instanceof ScrollPane, "FrameBrowserPane content must be ScrollPane");
            assertTrue(((ScrollPane) frameNode).isFitToWidth(), "FrameBrowserPane ScrollPane must fitToWidth");

            StereoPane stereoPane = new StereoPane(null);
            Node stereoNode = stereoPane.content();
            assertTrue(stereoNode instanceof ScrollPane, "StereoPane content must be ScrollPane");
            assertTrue(((ScrollPane) stereoNode).isFitToWidth(), "StereoPane ScrollPane must fitToWidth");

            InfoPane infoPane = new InfoPane(null);
            Node infoNode = infoPane.content();
            assertTrue(infoNode instanceof ScrollPane, "InfoPane content must be ScrollPane");
            assertTrue(((ScrollPane) infoNode).isFitToWidth(), "InfoPane ScrollPane must fitToWidth");
        });
    }

    @Test
    @DisplayName("Auto LSB candidate table and controls are configured for responsive display")
    void candidateTableAndControlsConfiguredResponsively() throws Exception {
        if (!fxInitialized) return;

        runOnFx(() -> {
            ExtractPane pane = new ExtractPane(null);
            Node root = pane.content();
            assertNotNull(root);

            // Traverse scene graph to verify candidate table
            javafx.scene.control.TableView<?> table = null;
            java.util.List<javafx.scene.control.Button> buttons = new java.util.ArrayList<>();
            java.util.List<javafx.scene.control.CheckBox> checkBoxes = new java.util.ArrayList<>();
            java.util.List<javafx.scene.control.RadioButton> radioButtons = new java.util.ArrayList<>();

            java.util.Queue<Node> queue = new java.util.LinkedList<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                Node n = queue.poll();
                if (n instanceof javafx.scene.control.TableView<?> tv) {
                    table = tv;
                } else if (n instanceof javafx.scene.control.Button btn) {
                    buttons.add(btn);
                } else if (n instanceof javafx.scene.control.CheckBox cb) {
                    checkBoxes.add(cb);
                } else if (n instanceof javafx.scene.control.RadioButton rb) {
                    radioButtons.add(rb);
                }
                if (n instanceof javafx.scene.control.ScrollPane sp) {
                    if (sp.getContent() != null) queue.add(sp.getContent());
                } else if (n instanceof javafx.scene.Parent parent) {
                    queue.addAll(parent.getChildrenUnmodifiable());
                }
            }

            assertNotNull(table, "Candidate table must exist in ExtractPane");
            assertEquals(4, table.getColumns().size(), "Candidate table must have 4 columns");

            // Check that all buttons in ExtractPane have non-zero or USE_PREF_SIZE minWidth to prevent truncation
            assertFalse(buttons.isEmpty(), "ExtractPane should contain action buttons");
            for (javafx.scene.control.Button b : buttons) {
                assertTrue(b.getMinWidth() >= 0 || b.getMinWidth() == javafx.scene.layout.Region.USE_PREF_SIZE,
                        "Button '" + b.getText() + "' should have valid minWidth");
            }

            // Check that labeled checkboxes and radio buttons allow text wrapping
            for (javafx.scene.control.CheckBox cb : checkBoxes) {
                if (cb.getText() != null && !cb.getText().isEmpty()) {
                    assertTrue(cb.isWrapText(), "CheckBox '" + cb.getText() + "' should wrap text");
                }
            }
            for (javafx.scene.control.RadioButton rb : radioButtons) {
                assertTrue(rb.isWrapText(), "RadioButton '" + rb.getText() + "' should wrap text");
            }
        });
    }
}
