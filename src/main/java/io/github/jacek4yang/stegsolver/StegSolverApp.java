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
        timeline.setOnFinished(event -> {
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
        });
        timeline.play();
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
