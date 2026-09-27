package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.*;
import io.github.jacek4yang.stegsolver.barcode.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.stage.Stage;

/** Runs with a real display or Xvfb; deliberately separate from headless unit tests. */
public final class FxIntegrationCheck {
    private static MainWindow window;
    private static Stage stage;
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (fx(condition)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("UI result did not arrive");
    }
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, values);
    }
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        Platform.startup(() -> {});
        Path temp = Files.createTempDirectory("stegsolver-fx-check-");
        try {
            fx(() -> {
                stage = new Stage();
                window = new MainWindow(stage);
                stage.setScene(window.createScene());
                stage.setAlwaysOnTop(true);
                stage.show();
                stage.toFront();
                return null;
            });
            System.out.println("FX startup ms=" + (System.nanoTime() - start) / 1_000_000);
            ImageData first = TestImages.randomRgb(320, 200, 9);
            Path file = temp.resolve("first.png");
            ImageIoUtil.save(first, file);
            fx(() -> { window.openImage(file); return null; });
            waitFor(() -> window.displayedImage() != null);
            fx(() -> {
                check(window.displayedImage().pixelAt(0, 0) == (first.pixelAt(0, 0) | 0xff000000), "opened pixels");
                window.document().setTransformIndex(1);
                return null;
            });
            waitFor(() -> window.displayedImage().pixelAt(0, 0) == ((first.pixelAt(0, 0) ^ 0x00ffffff) | 0xff000000));
            fx(() -> {
                window.document().setTransformIndex(20);
                window.document().setTransformIndex(0); // cached result must invalidate pending render
                return null;
            });
            Thread.sleep(200);
            fx(() -> { check(window.displayedImage().pixelAt(0, 0) == (first.pixelAt(0, 0) | 0xff000000), "cached render won"); return null; });
            ImageData second = TestImages.randomRgb(320, 200, 10);
            fx(() -> { window.document().openFromImage(second, "second"); return null; });
            waitFor(() -> window.displayedImage() != null && window.displayedImage().pixelAt(0, 0) == second.pixelAt(0, 0));
            // Cycle all modes using the same two inputs, including the dimension-changing interlaces.
            ImageData other = TestImages.randomRgb(320, 200, 31);
            Object combine = fx(() -> field(window, "combinePane"));
            fx(() -> { setField(combine, "primary", second); setField(combine, "second", other); return null; });
            for (var mode : io.github.jacek4yang.stegsolver.transform.CombineMode.values()) {
                ImageData expected = mode.combine(second, other);
                fx(() -> {
                    @SuppressWarnings("unchecked")
                    var choice = (javafx.scene.control.ComboBox<io.github.jacek4yang.stegsolver.transform.CombineMode>) field(combine, "modeChoice");
                    choice.setValue(mode);
                    invoke(combine, "recompute", new Class<?>[0]);
                    return null;
                });
                waitFor(() -> window.isShowingPreview() && java.util.Arrays.equals(expected.pixels(), window.viewport().viewPixels()));
            }
            // Frame navigation and thumbnails use independent requests; neither may suppress the other.
            Path animation = temp.resolve("frames.gif");
            var writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
            try (var output = javax.imageio.ImageIO.createImageOutputStream(animation.toFile())) {
                writer.setOutput(output);
                writer.prepareWriteSequence(null);
                for (int color : new int[] {0xffff0000, 0xff00ff00, 0xff0000ff}) {
                    var frame = TestImages.solid(80, 60, color).toBufferedImage();
                    writer.writeToSequence(new javax.imageio.IIOImage(frame, null, null), null);
                }
                writer.endWriteSequence();
            } finally { writer.dispose(); }
            Object frames = fx(() -> field(window, "frameBrowserPane"));
            fx(() -> { invoke(frames, "load", new Class<?>[] {Path.class}, animation); return null; });
            waitFor(() -> window.viewport().viewPixels().length == 4800 && window.viewport().viewPixels()[0] == 0xffff0000);
            fx(() -> { invoke(frames, "showFrame", new Class<?>[] {int.class}, 2); return null; });
            waitFor(() -> window.viewport().viewPixels()[0] == 0xff0000ff);
            Object barcode = fx(() -> field(window, "barcodePane"));
            for (int part = 0; part < 2; part++) {
                int index = part;
                fx(() -> {
                    window.document().openFromImage(second, "append image " + index);
                    byte[] bytes = {(byte) index};
                    var hit = new BarcodeHit(com.google.zxing.BarcodeFormat.QR_CODE, "part" + index,
                            bytes, java.util.List.of(bytes), new byte[] {9}, java.util.List.of(), Roi.EMPTY,
                            0, false, java.util.Map.of(), new StructuredAppend(index, 2, 11, (index << 4) | 1),
                            PayloadDetector.detect(bytes, null));
                    ((io.github.jacek4yang.stegsolver.ui.panes.BarcodePane) barcode).addResults(
                            new ScanResult(java.util.List.of(hit), java.util.List.of(), 0, Roi.EMPTY), false);
                    return null;
                });
            }
            fx(() -> {
                invoke(barcode, "mergeStructuredAppend", new Class<?>[0]);
                var hits = ((io.github.jacek4yang.stegsolver.ui.panes.BarcodePane) barcode).accumulated().hits();
                check(hits.stream().anyMatch(hit -> java.util.Arrays.equals(hit.payload(), new byte[] {0, 1})), "append across documents");
                return null;
            });
            ImageData qr = TestImages.qrCode("screen workflow regression", 8);
            fx(() -> {
                window.document().setTransformIndex(21);
                window.showPreview(qr, "Screen workflow test");
                return null;
            });
            Thread.sleep(300);
            java.awt.Rectangle capture = fx(() -> {
                check(window.isShowingPreview(), "preview not overwritten");
                check(window.viewport().viewPixels() == qr.pixels(), "preview pixels not overwritten");
                return new java.awt.Rectangle((int) stage.getX(), (int) stage.getY(),
                        (int) stage.getWidth(), (int) stage.getHeight());
            });
            ImageData screen = ScreenGrabber.capture(capture);
            ScanResult result = new BarcodeScanner().scan(screen, ScanOptions.defaults());
            check(result.hits().stream().anyMatch(hit -> "screen workflow regression".equals(hit.text())), "screen QR decoded");
            System.out.println("FX integration: open, transform, cached stale result, same-size replacement, preview, all 13 combine modes, GIF navigation, append across documents, screen QR PASS");
        } finally {
            fx(() -> { if (window != null) window.shutdown(); if (stage != null) stage.close(); return null; });
            Platform.exit();
            // Frame sources close on their worker so shutdown never blocks JavaFX.
            for (int retry = 0; retry < 100; retry++) {
                try { Files.deleteIfExists(temp.resolve("frames.gif")); break; }
                catch (java.io.IOException busy) { Thread.sleep(20); }
            }
            Files.deleteIfExists(temp.resolve("first.png"));
            Files.deleteIfExists(temp);
        }
    }
}
