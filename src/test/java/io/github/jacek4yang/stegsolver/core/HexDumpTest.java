package io.github.jacek4yang.stegsolver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HexDumpTest {

    @Test void streamedExportMatchesFullFormatting() throws Exception {
        byte[] data = new byte[9001];
        new java.util.Random(21).nextBytes(data);
        for (boolean hex : new boolean[] {true, false}) {
            var writer = new java.io.StringWriter();
            HexDump.write(writer, data, hex);
            org.junit.jupiter.api.Assertions.assertEquals(HexDump.format(data, 0, data.length, data.length, hex), writer.toString());
        }
    }

    @Test
    @DisplayName("the dump uses the legacy layout: address, hex, gap, ASCII")
    void legacyLayout() {
        byte[] data = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        String dump = HexDump.format(data, 0, data.length, 1024, true);
        String[] lines = dump.split("\n");
        assertEquals(1, lines.length, dump);
        assertEquals("00000000  3031323334353637 3839616263646566  01234567 89abcdef", lines[0]);
    }

    @Test
    @DisplayName("unprintable bytes become dots and the last line is not padded in the ASCII column")
    void unprintableBytes() {
        byte[] data = {0, 1, 2, 0x41, (byte) 0xff, '\n', 'Z'};
        String dump = HexDump.format(data, 0, data.length, 1024, true);
        assertTrue(dump.contains("00010241ff0a5a"), dump);
        assertTrue(dump.trim().endsWith("...A..Z"), dump);
    }

    @Test
    @DisplayName("dumping is bounded and says how much was not shown")
    void boundedOutput() {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        String dump = HexDump.format(data, 0, data.length, 64, true);
        assertTrue(dump.contains("960 more bytes not shown"), dump);
        assertEquals(5, dump.split("\n").length, dump);
        assertTrue(dump.contains("00000030"), "the last line should start at offset 0x30");
    }

    @Test
    @DisplayName("an offset and length window is respected and clamped")
    void offsetAndLength() {
        byte[] data = "0123456789".getBytes(StandardCharsets.US_ASCII);
        String dump = HexDump.format(data, 4, 3, 1024, true);
        assertTrue(dump.startsWith("00000004"), dump);
        assertTrue(dump.contains("343536"), dump);
        assertTrue(dump.trim().endsWith("456"), dump);
        // Out of range windows degrade to an explicit empty result instead of throwing.
        assertEquals("(empty)", HexDump.format(new byte[0], 0, 0, 10, true));
        assertEquals("(empty)", HexDump.format(data, 100, 5, 10, true));
        assertEquals("(empty)", HexDump.format(data, 0, 0, 10, true));
        // A byte budget of zero still reports that there is something to show.
        assertTrue(HexDump.format(data, 0, data.length, 0, true).contains("10 more bytes not shown"));
    }

    @Test
    @DisplayName("hex rendering is compact, bounded and prefixed with a count when truncated")
    void hexRendering() {
        byte[] data = {0x00, 0x7f, (byte) 0x80, (byte) 0xff};
        assertEquals("00 7f 80 ff", HexDump.hex(data, 0, data.length, 100));
        assertEquals("00 7f", HexDump.hex(data, 0, 2, 100));
        assertTrue(HexDump.hex(data, 0, data.length, 2).contains("2 more bytes"));
        assertEquals("007f80ff", HexDump.hexCompact(data));
        assertEquals("", HexDump.hex(new byte[0], 0, 0, 10));
    }

    @Test
    @DisplayName("the ASCII rendering keeps printable characters only")
    void asciiRendering() {
        byte[] data = "a\u0000b\u007fc".getBytes(StandardCharsets.US_ASCII);
        assertEquals("a.b.c", HexDump.ascii(data, 0, data.length));
        assertEquals('.', HexDump.printable((byte) 0x00));
        assertEquals('.', HexDump.printable((byte) 0x80));
        assertEquals('~', HexDump.printable((byte) '~'));
    }

    @Test
    @DisplayName("the hex column can be left out for a plain ASCII view")
    void withoutHex() {
        byte[] data = "hello".getBytes(StandardCharsets.US_ASCII);
        String asciiOnly = HexDump.format(data, 0, data.length, 100, false);
        assertFalse(asciiOnly.contains("68 65"), asciiOnly);
        assertTrue(asciiOnly.contains("hello"), asciiOnly);
    }
}
