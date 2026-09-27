package io.github.jacek4yang.stegsolver;

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
        for (String argument : args) {
            if ("--version".equals(argument) || "-V".equals(argument)) {
                versionRequested = true;
            } else if (!"--help".equals(argument) && !"-h".equals(argument)) {
                applicationArguments.add(argument);
            }
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

    private static void printUsage() {
        System.out.println("StegSolver " + version());
        System.out.println();
        System.out.println("Usage: stegsolver [image]");
        System.out.println();
        System.out.println("  image         image file to open at startup (optional)");
        System.out.println("  -h, --help    show this message");
        System.out.println("  -V, --version show the version");
    }

    /** The implementation version from the jar manifest, or a development marker. */
    public static String version() {
        String version = Launcher.class.getPackage().getImplementationVersion();
        return version == null ? "development build" : version;
    }
}
