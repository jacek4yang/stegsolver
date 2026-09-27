package io.github.jacek4yang.stegsolver;

import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import io.github.jacek4yang.stegsolver.selfcheck.SelfTest;
import io.github.jacek4yang.stegsolver.selfcheck.SyntheticImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;

/**
 * Entry point of the application.
 *
 * <p>This class exists so that the launcher never starts a class that extends
 * {@link javafx.application.Application} directly: the Java launcher refuses to start such a class
 * from the class path ("JavaFX runtime components are missing"), which would break the normal
 * {@code java -jar stegsolver.jar} workflow as well as the {@code jpackage} launcher. Going through a
 * plain class keeps the class path deployment working, which in turn is what allows the shaded ZXing
 * dependency to stay a normal library.</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        List<String> applicationArguments = new ArrayList<>();
        boolean versionRequested = false;
        boolean selfTestRequested = false;
        for (String argument : args) {
            if ("--version".equals(argument) || "-V".equals(argument)) {
                versionRequested = true;
            } else if ("--self-test".equals(argument)) {
                selfTestRequested = true;
            } else if (!"--help".equals(argument) && !"-h".equals(argument)) {
                applicationArguments.add(argument);
            }
        }
        if (selfTestRequested) {
            System.exit(runSelfTest(applicationArguments));
        }
        if (versionRequested) {
            System.out.println("StegSolver " + version());
            System.out.println("Java " + System.getProperty("java.version") + " on "
                    + System.getProperty("os.name"));
            return;
        }
        if (containsHelp(args)) {
            printUsage();
            return;
        }
        Application.launch(StegSolverApp.class, applicationArguments.toArray(new String[0]));
    }

    private static boolean containsHelp(String[] args) {
        for (String argument : args) {
            if ("--help".equals(argument) || "-h".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs every engine layer once and prints the result. Requires no display, which makes it the check
     * used by continuous integration and by the packaging scripts.
     *
     * @return 0 when every step succeeded, 1 otherwise
     */
    private static int runSelfTest(List<String> arguments) {
        System.out.println("StegSolver " + version());
        Path file = null;
        for (String argument : arguments) {
            Path candidate = Path.of(argument);
            if (Files.isRegularFile(candidate)) {
                file = candidate;
            }
        }
        try {
            ImageData image;
            if (file != null) {
                image = ImageIoUtil.load(file);
                System.out.println("Testing on " + file + " (" + image.width() + "x" + image.height() + ")");
            } else {
                image = SyntheticImage.create();
                System.out.println("No file given: testing on a generated image ("
                        + image.width() + "x" + image.height() + ")");
            }
            SelfTest.Report report = SelfTest.run(image, file);
            System.out.println();
            System.out.println(report.toText());
            return report.ok() ? 0 : 1;
        } catch (Exception e) {
            System.err.println("Self test could not start: " + e);
            e.printStackTrace();
            return 1;
        }
    }

    private static void printUsage() {
        System.out.println("StegSolver " + version());
        System.out.println();
        System.out.println("Usage: stegsolver [options] [image]");
        System.out.println();
        System.out.println("  image           image file to open at startup (optional)");
        System.out.println("  --self-test     run every engine layer once and exit (no display needed)");
        System.out.println("  -h, --help      show this message");
        System.out.println("  -V, --version   show the version");
    }

    /** The implementation version from the jar manifest, or a development marker. */
    public static String version() {
        String version = Launcher.class.getPackage().getImplementationVersion();
        return version == null ? "development build" : version;
    }
}
