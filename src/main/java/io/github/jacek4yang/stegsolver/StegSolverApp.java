package io.github.jacek4yang.stegsolver;

import io.github.jacek4yang.stegsolver.ui.FxUtils;
import io.github.jacek4yang.stegsolver.ui.MainWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The JavaFX application: opens the main window and, when a file was given on the command line, loads it.
 *
 * <p>Started through {@link Launcher} so that the same jar works from the class path, from a
 * {@code jpackage} launcher and from an IDE.</p>
 */
public final class StegSolverApp extends Application {

    /**
     * Set with {@code -Dstegsolver.smokeTest=true} to verify that the packaged application starts: the
     * window is shown, a few transforms are rendered and the process exits with code 0.
     */
    private static final String SMOKE_TEST_PROPERTY = "stegsolver.smokeTest";

    private MainWindow window;
    private boolean scannedOnce;

    @Override
    public void start(Stage stage) {
        window = new MainWindow(stage);
        Scene scene = window.createScene();
        stage.setScene(scene);
        stage.setTitle("StegSolver " + Launcher.version());
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.setOnCloseRequest(event -> window.shutdown());

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            Platform.runLater(() -> FxUtils.error(stage, "Unexpected error", String.valueOf(error), error));
        });

        stage.show();

        List<String> arguments = getParameters().getRaw();
        for (String argument : arguments) {
            Path path = Path.of(argument);
            if (Files.isRegularFile(path)) {
                window.openImage(path);
            } else {
                window.status("Not a readable file: " + argument);
            }
        }
        if (arguments.isEmpty()) {
            window.status("Open an image with Ctrl+O, or drop one onto this window");
        }

        if (Boolean.getBoolean(SMOKE_TEST_PROPERTY)) {
            runSmokeTest();
        }
    }

    /**
     * Exercises the whole pipeline briefly: this is what proves that a packaged build really comes up,
     * including the off-thread transform rendering and the JavaFX image conversion.
     */
    private void runSmokeTest() {
        int steps = Integer.getInteger("stegsolver.smokeSteps", 8);
        double stepMillis = Double.parseDouble(System.getProperty("stegsolver.smokeStepMillis", "120"));
        javafx.animation.Timeline timeline = new javafx.animation.Timeline();
        timeline.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(stepMillis), event -> {
            if (window.document().isOpen()) {
                window.document().nextTransform();
            }
        }));
        timeline.setCycleCount(Math.max(1, steps));
        // The transforms are rendered in the background, so give the last one time to appear before the
        // smoke test inspects (or screenshots) the window.
        double settleMillis = Double.parseDouble(System.getProperty("stegsolver.smokeSettleMillis", "900"));
        timeline.setOnFinished(event -> {
            javafx.animation.PauseTransition settle =
                    new javafx.animation.PauseTransition(Duration.millis(settleMillis));
            settle.setOnFinished(settled -> finishSmokeTest(steps));
            settle.play();
        });
        timeline.play();
    }

    private void finishSmokeTest(int steps) {
        if (Boolean.getBoolean("stegsolver.selfTest")) {
            runSelfTestThenExit();
            return;
        }
        int tab = Integer.getInteger("stegsolver.smokeTab", -1);
        if (tab >= 0) {
            window.selectToolTab(tab);
        }
        if (Boolean.getBoolean("stegsolver.smokeScan") && !scannedOnce) {
            // One optional barcode scan, then a second pause so the results are visible in a screenshot.
            scannedOnce = true;
            window.scanRegion(null, false);
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(
                    Duration.millis(Double.parseDouble(System.getProperty("stegsolver.smokeScanMillis",
                            "2000"))));
            wait.setOnFinished(event -> finishSmokeTest(steps));
            wait.play();
            return;
        }
        System.out.println("StegSolver smoke test layout: " + window.layoutDiagnostics());
        if (Boolean.getBoolean("stegsolver.debugNodes")) {
            snapshotNode("target/viewport-node.png", window.viewport());
            snapshotNode("target/imageview-node.png", window.imageViewNode());
        }
        saveScreenshotIfRequested();
        String document = window.document().isOpen()
                ? window.document().fileName() + " (" + window.document().image().width() + "x"
                        + window.document().image().height() + ")"
                : "none";
        String transform = window.document().isOpen() ? window.document().transform().label() : "none";
        System.out.println("StegSolver smoke test: window shown, " + steps + " transforms rendered, last "
                + "one was '" + transform + "', JavaFX "
                + System.getProperty("javafx.version", "unknown") + ", exiting");
        System.out.println("StegSolver smoke test: document " + document + ", status '"
                + window.statusMessage() + "'");
        window.shutdown();
        Platform.exit();
    }

    /** Writes the snapshot of a single node, used to locate where rendering goes wrong. */
    private void snapshotNode(String target, javafx.scene.Node node) {
        try {
            var image = node.snapshot(null, null);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
            io.github.jacek4yang.stegsolver.core.ImageIoUtil.save(
                    io.github.jacek4yang.stegsolver.core.ImageData.of(width, height, pixels, true),
                    java.nio.file.Path.of(target));
            System.out.println("StegSolver smoke test: wrote " + target + " (" + width + "x" + height
                    + ") centre=" + Integer.toHexString(pixels[(height / 2) * width + width / 2]));
        } catch (Exception e) {
            System.err.println("Node snapshot failed: " + e);
        }
    }

    /**
     * Saves a screenshot of the whole window when {@code -Dstegsolver.screenshot=<file>} is set. Used to
     * document the user interface with an actual screenshot instead of a mock up.
     */
    private void saveScreenshotIfRequested() {
        String target = System.getProperty("stegsolver.screenshot");
        if (target == null || target.isBlank()) {
            return;
        }
        try {
            var image = window.scene().snapshot(null);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
            var path = java.nio.file.Path.of(target);
            io.github.jacek4yang.stegsolver.core.ImageIoUtil.save(
                    io.github.jacek4yang.stegsolver.core.ImageData.opaque(width, height, pixels), path);
            System.out.println("StegSolver smoke test: screenshot written to " + path.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Screenshot failed: " + e);
        }
    }

    /**
     * Runs the engine self test on the opened document and exits with the result as the exit code, which
     * is what the continuous integration and packaging checks use.
     */
    private void runSelfTestThenExit() {
        var image = window.document().isOpen() ? window.document().image() : null;
        var file = window.document().path();
        if (image == null) {
            System.err.println("StegSolver self test: no image was opened");
            window.shutdown();
            Platform.exit();
            Runtime.getRuntime().halt(1);
            return;
        }
        Thread.ofVirtual().name("stegsolver-self-test").start(() -> {
            var report = io.github.jacek4yang.stegsolver.selfcheck.SelfTest.run(image, file);
            System.out.println();
            System.out.println(report.toText());
            Platform.runLater(() -> {
                window.shutdown();
                Platform.exit();
                if (!report.ok()) {
                    Runtime.getRuntime().halt(1);
                }
            });
        });
    }

    @Override
    public void stop() {
        if (window != null) {
            window.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
