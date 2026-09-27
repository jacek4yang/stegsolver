package io.github.jacek4yang.stegsolver.parser;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ReportBoundsTest {
    @Test void manyChunksCannotExpandReportWithoutLimit() {
        var report = new FileReport("many.png", 1000000, ImageFormat.PNG);
        for (int i = 0; i < 100000; i++) {
            report.openSection("chunk");
            report.add("details");
            report.warn("invalid");
        }
        assertTrue(report.toText().contains("truncated"));
        assertTrue(report.toText().length() < 500000);
    }
    @Test void untrustedTextPreviewIsBounded() {
        byte[] text = new byte[1000000];
        java.util.Arrays.fill(text, (byte) 'A');
        assertEquals(4096, new ByteReader(text).asciiUntilNul(0, text.length).length());
    }
}
