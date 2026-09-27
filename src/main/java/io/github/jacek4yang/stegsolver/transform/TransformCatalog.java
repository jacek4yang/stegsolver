package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of transforms offered by StegSolver.
 *
 * <p>The order and the numbering reproduce the original StegSolve exactly, because analysts are used
 * to a fixed sequence of transforms and hit the previous/next key repeatedly:</p>
 * <pre>
 *   0        original image
 *   1        inversion
 *   2..9     alpha planes 7..0
 *   10..17   red planes 7..0
 *   18..25   green planes 7..0
 *   26..33   blue planes 7..0
 *   34..37   full alpha / red / green / blue
 *   38..40   random colour maps (three different seeds)
 *   41       gray pixels (r == g == b)
 * </pre>
 */
public final class TransformCatalog {

    private static final List<TransformDef> DEFS = build();

    private TransformCatalog() {
    }

    public static List<TransformDef> definitions() {
        return DEFS;
    }

    public static int count() {
        return DEFS.size();
    }

    public static TransformDef byIndex(int index) {
        if (index < 0 || index >= DEFS.size()) {
            throw new IndexOutOfBoundsException("No transform " + index);
        }
        return DEFS.get(index);
    }

    public static int indexOf(TransformDef def) {
        return def.index();
    }

    /** Next transform index, wrapping around at the end. */
    public static int next(int index) {
        return index >= DEFS.size() - 1 ? 0 : index + 1;
    }

    /** Previous transform index, wrapping around at the start. */
    public static int previous(int index) {
        return index <= 0 ? DEFS.size() - 1 : index - 1;
    }

    /**
     * Next index inside the same group, so that holding the key walks through "Red plane 7..0"
     * without leaving the red planes. Wraps within the group.
     */
    public static int nextInGroup(int index) {
        String group = byIndex(index).group();
        int candidate = index;
        for (int step = 0; step < DEFS.size(); step++) {
            candidate = next(candidate);
            if (byIndex(candidate).group().equals(group)) {
                return candidate;
            }
        }
        return index;
    }

    /** Previous index inside the same group; see {@link #nextInGroup(int)}. */
    public static int previousInGroup(int index) {
        String group = byIndex(index).group();
        int candidate = index;
        for (int step = 0; step < DEFS.size(); step++) {
            candidate = previous(candidate);
            if (byIndex(candidate).group().equals(group)) {
                return candidate;
            }
        }
        return index;
    }

    /** The catalog grouped by group name, in catalog order; used to build menus and combo boxes. */
    public static Map<String, List<TransformDef>> grouped() {
        Map<String, List<TransformDef>> groups = new LinkedHashMap<>();
        for (TransformDef def : DEFS) {
            groups.computeIfAbsent(def.group(), key -> new ArrayList<>()).add(def);
        }
        groups.replaceAll((key, value) -> Collections.unmodifiableList(value));
        return Collections.unmodifiableMap(groups);
    }

    private static List<TransformDef> build() {
        List<TransformDef> defs = new ArrayList<>(42);
        defs.add(new TransformDef(0, TransformKind.ORIGINAL, null, -1, "Original", "Original image"));
        defs.add(new TransformDef(1, TransformKind.INVERT, null, -1, "Invert", "Invert colours (XOR 0xFFFFFF)"));
        addPlanes(defs, Channel.ALPHA, "Alpha planes");
        addPlanes(defs, Channel.RED, "Red planes");
        addPlanes(defs, Channel.GREEN, "Green planes");
        addPlanes(defs, Channel.BLUE, "Blue planes");
        int next = defs.size();
        defs.add(new TransformDef(next++, TransformKind.FULL_CHANNEL, Channel.ALPHA, -1, "Channels", "Full alpha"));
        defs.add(new TransformDef(next++, TransformKind.FULL_CHANNEL, Channel.RED, -1, "Channels", "Full red"));
        defs.add(new TransformDef(next++, TransformKind.FULL_CHANNEL, Channel.GREEN, -1, "Channels", "Full green"));
        defs.add(new TransformDef(next++, TransformKind.FULL_CHANNEL, Channel.BLUE, -1, "Channels", "Full blue"));
        defs.add(new TransformDef(next++, TransformKind.RANDOM_MAP, null, -1, "Random colour maps", "Random colour map 1"));
        defs.add(new TransformDef(next++, TransformKind.RANDOM_MAP, null, -1, "Random colour maps", "Random colour map 2"));
        defs.add(new TransformDef(next++, TransformKind.RANDOM_MAP, null, -1, "Random colour maps", "Random colour map 3"));
        defs.add(new TransformDef(next, TransformKind.GRAY_BITS, null, -1, "Gray pixels", "Gray pixels only (r = g = b)"));
        return Collections.unmodifiableList(defs);
    }

    private static void addPlanes(List<TransformDef> defs, Channel channel, String group) {
        for (int plane = 7; plane >= 0; plane--) {
            defs.add(new TransformDef(defs.size(), TransformKind.PLANE, channel, plane, group,
                    channel.label() + " plane " + plane));
        }
    }
}
