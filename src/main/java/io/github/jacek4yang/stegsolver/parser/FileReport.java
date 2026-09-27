package io.github.jacek4yang.stegsolver.parser;

import io.github.jacek4yang.stegsolver.core.HexDump;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The result of a structural file analysis: named sections of human readable lines plus a list of
 * warnings that point at malformed, unusual or suspicious structure.
 *
 * <p>Analysers only ever append to a report, so a partially understood file still produces everything
 * that could be determined before the problem was hit.</p>
 */
public final class FileReport {

    /** Default limit for hex dumps so that a huge comment or an appended blob cannot flood the UI. */
    public static final int DEFAULT_DUMP_BYTES = 512;

    private final String fileName;
    private final long fileSize;
    private final ImageFormat format;
    private final List<Section> sections = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private List<String> currentLines;

    private record Section(String title, List<String> lines) {
    }

    public FileReport(String fileName, long fileSize, ImageFormat format) {
        this.fileName = fileName == null ? "(unknown)" : fileName;
        this.fileSize = fileSize;
        this.format = format == null ? ImageFormat.UNKNOWN : format;
        openSection("File");
    }

    public String fileName() {
        return fileName;
    }

    public long fileSize() {
        return fileSize;
    }

    public ImageFormat format() {
        return format;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /** Start a new section; subsequent {@link #add} calls go into it. */
    public void openSection(String title) {
        currentLines = new ArrayList<>();
        sections.add(new Section(title, currentLines));
    }

    public void add(String line) {
        if (currentLines == null) {
            openSection("Details");
        }
        currentLines.add(line);
    }

    public void addAll(String... lines) {
        for (String line : lines) {
            add(line);
        }
    }

    /** Adds a line of the form {@code label: value}. */
    public void field(String label, Object value) {
        add(label + ": " + value);
    }

    /** Adds a hex value with its decimal equivalent, the format used by the original analyser. */
    public void hexField(String label, long value) {
        add(label + ": 0x" + Long.toHexString(value) + " (" + value + ")");
    }

    /** Adds a line prefixed with a marker, e.g. {@code ! suspicious} or {@code * note}. */
    public void note(String message) {
        add("  * " + message);
    }

    public void warn(String message) {
        warnings.add(message);
    }

    /** Adds a warning and returns this report, for terse failure paths. */
    public FileReport withWarning(String message) {
        warn(message);
        return this;
    }

    /**
     * Appends a bounded hex/ASCII dump of a byte range.
     *
     * @param label    text placed before the dump
     * @param data     backing array
     * @param offset   start offset
     * @param length   number of bytes to dump
     * @param maxBytes upper bound of dumped bytes, {@link #DEFAULT_DUMP_BYTES} is a sensible default
     */
    public void dump(String label, byte[] data, int offset, int length, int maxBytes) {
        add(label);
        if (data == null || length <= 0) {
            add("  (nothing to dump)");
            return;
        }
        String text = HexDump.format(data, offset, length, maxBytes, true);
        for (String line : text.split("\n", -1)) {
            add("  " + line);
        }
        if (length > maxBytes) {
            add("  … " + (length - maxBytes) + " more bytes not dumped");
        }
    }

    public void dump(String label, byte[] data, int offset, int length) {
        dump(label, data, offset, length, DEFAULT_DUMP_BYTES);
    }

    /** Whole report as plain text, which is also what "Copy report" and "Save report" produce. */
    public String toText() {
        StringBuilder out = new StringBuilder(4096);
        out.append("StegSolver file analysis\n");
        out.append("=======================\n");
        for (Section section : sections) {
            if (section.lines().isEmpty() && !section.title().equals("File")) {
                continue;
            }
            out.append('\n').append('[').append(section.title()).append("]\n");
            for (String line : section.lines()) {
                out.append("  ").append(line).append('\n');
            }
        }
        out.append("\n[Warnings]\n");
        if (warnings.isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (String warning : warnings) {
                out.append("  ! ").append(warning).append('\n');
            }
        }
        return out.toString();
    }

    /** One line summary used for tooltips and the status bar. */
    public String summary() {
        return String.format(Locale.ROOT, "%s — %s, %d bytes, %d warning(s)",
                fileName, format.formatName(), fileSize, warnings.size());
    }

    @Override
    public String toString() {
        return summary();
    }
}
