package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.core.HexDump;
import io.github.jacek4yang.stegsolver.core.ImageData;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Small JavaFX helpers: dialogs, file choosers, the clipboard and pixel conversion.
 *
 * <p>Deliberately free of Swing: every dialog and file chooser is a JavaFX one, so the application
 * never pulls the Swing toolkit into a packaged image.</p>
 */
public final class FxUtils {

    /** Remembers the last directory a file chooser was used in. */
    private static File lastDirectory = new File(System.getProperty("user.home", "."));

    private FxUtils() {
    }

    // ---------------------------------------------------------------- conversion

    /** Converts StegSolver's pixel array into a JavaFX image, ready for an {@code ImageView}. */
    public static WritableImage toFxImage(ImageData data) {
        return toFxImage(data.pixels(), data.width(), data.height());
    }

    public static WritableImage toFxImage(int[] pixels, int width, int height) {
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0,
                width);
        return image;
    }

    // ---------------------------------------------------------------- clipboard

    /** Copies text to the system clipboard. */
    public static void copyText(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Copies a hex rendering of bytes to the system clipboard. */
    public static void copyHex(byte[] data, int maxBytes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < data.length; i += maxBytes) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(HexDump.hex(data, i, Math.min(maxBytes, data.length - i), maxBytes));
        }
        copyText(text.toString());
    }

    /** Copies an image to the system clipboard. */
    public static void copyImage(ImageData data) {
        ClipboardContent content = new ClipboardContent();
        content.putImage(toFxImage(data));
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ---------------------------------------------------------------- dialogs

    public static void info(Window owner, String title, String message) {
        show(owner, Alert.AlertType.INFORMATION, title, message, null);
    }

    public static void warn(Window owner, String title, String message) {
        show(owner, Alert.AlertType.WARNING, title, message, null);
    }

    public static void error(Window owner, String title, String message, Throwable cause) {
        show(owner, Alert.AlertType.ERROR, title, message, cause);
    }

    private static void show(Window owner, Alert.AlertType type, String title, String message,
            Throwable cause) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setResizable(true);
        if (cause == null) {
            alert.setContentText(message);
        } else {
            TextArea details = new TextArea(message + "\n\n" + cause);
            details.setEditable(false);
            details.setWrapText(true);
            details.setPrefRowCount(10);
            VBox.setVgrow(details, Priority.ALWAYS);
            alert.getDialogPane().setContent(new VBox(6, new Label(message), details));
        }
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    /** A yes/no question; returns {@code false} when the dialog is dismissed. */
    public static boolean confirm(Window owner, String title, String question, String yes, String no) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(question);
        ButtonType yesButton = new ButtonType(yes, ButtonType.OK.getButtonData());
        ButtonType noButton = new ButtonType(no, ButtonType.CANCEL.getButtonData());
        alert.getButtonTypes().setAll(yesButton, noButton);
        if (owner != null) {
            alert.initOwner(owner);
        }
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == yesButton;
    }

    // ---------------------------------------------------------------- file choosers

    /** Shows an open dialog restricted to images. */
    public static Optional<Path> chooseImageToOpen(Window owner, String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialDirectory(lastDirectory);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.wbmp", "*.tif", "*.tiff"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(owner);
        return remember(file);
    }

    /** Shows a save dialog; {@code suggestedName} may contain the extension that selects the format. */
    public static Optional<Path> chooseFileToSave(Window owner, String title, String suggestedName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialDirectory(lastDirectory);
        chooser.setInitialFileName(suggestedName);
        List<String> extensions = io.github.jacek4yang.stegsolver.core.ImageIoUtil.writableExtensions();
        if (!extensions.isEmpty()) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images",
                    extensions.stream().map(extension -> "*." + extension).toArray(String[]::new)));
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(owner);
        return remember(file);
    }

    /** Shows a save dialog with an explicit extension and description, for binary payloads. */
    public static Optional<Path> chooseFileToSave(Window owner, String title, String suggestedName,
            String extension, String description) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialDirectory(lastDirectory);
        chooser.setInitialFileName(suggestedName);
        if (extension != null && !extension.isBlank()) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, "*." + extension));
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(owner);
        return remember(file);
    }

    private static Optional<Path> remember(File file) {
        if (file == null) {
            return Optional.empty();
        }
        File parent = file.getParentFile();
        if (parent != null && parent.isDirectory()) {
            lastDirectory = parent;
        }
        return Optional.of(file.toPath());
    }

    // ---------------------------------------------------------------- formatting

    /** Formats a byte count in a compact, human readable way. */
    public static String bytes(long count) {
        if (count < 1024) {
            return count + " B";
        }
        if (count < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", count / 1024.0);
        }
        if (count < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", count / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GiB", count / (1024.0 * 1024 * 1024));
    }

    /** Formats a percentage for the zoom label. */
    public static String percent(double fraction) {
        double percent = fraction * 100;
        if (percent >= 100) {
            return String.format(Locale.ROOT, "%.0f%%", percent);
        }
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    /** Formats an ARGB pixel as {@code #AARRGGBB}. */
    public static String argbHex(int pixel) {
        return String.format(Locale.ROOT, "#%08X", pixel);
    }

    /** Formats a pixel as separate channel values, for the pixel inspector. */
    public static String channels(int pixel) {
        return String.format(Locale.ROOT, "A %3d  R %3d  G %3d  B %3d",
                (pixel >>> 24) & 0xff, (pixel >>> 16) & 0xff, (pixel >>> 8) & 0xff, pixel & 0xff);
    }

    /** Bit planes of a pixel as eight characters per channel, most significant first. */
    public static String bitPlanes(int pixel) {
        StringBuilder text = new StringBuilder(48);
        String[] names = {"a", "r", "g", "b"};
        for (int channel = 0; channel < 4; channel++) {
            int shift = 24 - channel * 8;
            if (channel > 0) {
                text.append("  ");
            }
            text.append(names[channel]).append('=');
            for (int plane = 7; plane >= 0; plane--) {
                text.append((pixel >>> (shift + plane)) & 1);
            }
        }
        return text.toString();
    }
}
