package io.github.jacek4yang.stegsolver.selfcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jacek4yang.stegsolver.TestImages;
import io.github.jacek4yang.stegsolver.core.ImageData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The self test is the integration check that every engine layer works together, so it is itself
 * tested: it must pass on a real file, on a generated image and on an image holding a QR code.
 */
class SelfTestTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the generated image is what the self test expects: alpha, a pattern period and a payload")
    void syntheticImage() {
        ImageData image = SyntheticImage.create();
        assertTrue(image.hasAlpha());
        assertEquals(512, image.width());
        assertEquals(384, image.height());
        // The same column one period to the right must have the same red value.
        assertEquals(image.pixelAt(10, 20) & 0xff0000, image.pixelAt(10 + SyntheticImage.PATTERN_PERIOD, 20)
                & 0xff0000);
        assertFalse(SyntheticImage.hiddenMessage().isEmpty());
    }

    @Test
    @DisplayName("every engine layer succeeds on a generated image")
    void passesOnGeneratedImage() {
        SelfTest.Report report = SelfTest.run(SyntheticImage.create(), null);
        assertTrue(report.ok(), report::toText);
        assertTrue(report.toText().contains("barcode scan"), report.toText());
        assertEquals(7, report.steps().size(), report::toText);
        // The stereogram solver should find the period of the generated pattern.
        assertTrue(report.steps().stream().anyMatch(step -> step.name().equals("stereogram solver")
                        && step.detail().contains("suggested offset " + SyntheticImage.PATTERN_PERIOD)),
                report::toText);
    }

    @Test
    @DisplayName("every engine layer succeeds on a file, including the analysis and the frame reader")
    void passesOnFile() throws IOException {
        Path file = tempDir.resolve("self-test.png");
        Files.write(file, TestImages.pngBytes(TestImages.randomRgb(96, 64, 3)));
        ImageData image = io.github.jacek4yang.stegsolver.core.ImageIoUtil.load(file);
        SelfTest.Report report = SelfTest.run(image, file);
        assertTrue(report.ok(), report::toText);
        assertTrue(report.steps().size() >= 8, report::toText);
        assertTrue(report.toText().contains("frame 0 decoded"), report::toText);
        assertTrue(report.toText().contains("PNG"), report::toText);
    }

    @Test
    @DisplayName("a QR code is actually found by the self test's barcode step")
    void findsTheQrCode() {
        ImageData image = TestImages.pasteOnCanvas(TestImages.qrCode("self test", 4), 400, 300, 120, 80);
        SelfTest.Report report = SelfTest.run(image, null);
        assertTrue(report.ok(), report::toText);
        assertTrue(report.steps().stream().anyMatch(step -> step.name().equals("barcode scan")
                        && step.detail().contains("1 symbol")),
                report::toText);
    }

    @Test
    @DisplayName("a failing step is reported instead of aborting the run")
    void failuresAreReported() {
        // A one pixel wide image breaks the stereogram solver's suggestion loop (it cannot shift), and
        // every other step must still be reported.
        ImageData tiny = TestImages.solid(1, 1, 0xff000000);
        SelfTest.Report report = SelfTest.run(tiny, null);
        assertNotNull(report.toText());
        assertTrue(report.steps().size() >= 5, report::toText);
        // Everything that can work on a single pixel must still succeed.
        assertTrue(report.steps().stream().anyMatch(step -> step.name().equals("transform catalog")
                && step.ok()), report::toText);
    }

    @Test
    @DisplayName("the barcode scan can be skipped to keep the check fast")
    void barcodeStepCanBeSkipped() {
        SelfTest.Report report = SelfTest.run(SyntheticImage.create(), null, false);
        assertTrue(report.ok(), report::toText);
        assertTrue(report.steps().stream().anyMatch(step -> step.name().equals("barcode scan")
                && step.detail().equals("skipped")), report::toText);
    }

    @Test
    @DisplayName("the report is formatted as a table with a summary line")
    void reportFormatting() {
        SelfTest.Report report = SelfTest.run(SyntheticImage.create(), null, false);
        String text = report.toText();
        assertTrue(text.startsWith("StegSolver self test"), text);
        assertTrue(text.contains("step"), text);
        assertTrue(text.contains("succeeded in"), text);
        assertTrue(text.trim().endsWith("ms"), text);
    }
}
