package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.Channel;

/**
 * One entry of the transform catalog, matching the transform index of the original StegSolve so
 * that the familiar "press right arrow and count" workflow still works.
 *
 * @param index   the legacy transform number (0 based, {@code 0..41})
 * @param kind    what the transform does
 * @param channel the channel for {@link TransformKind#PLANE} / {@link TransformKind#FULL_CHANNEL}, otherwise {@code null}
 * @param plane   the bit plane (0 = least significant) for {@link TransformKind#PLANE}, otherwise -1
 * @param group   group name used to build menus and grouped combo boxes
 * @param label   the label shown in the user interface
 */
public record TransformDef(int index, TransformKind kind, Channel channel, int plane, String group, String label) {

    public TransformDef {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        if (kind.isChannelSpecific() && channel == null) {
            throw new IllegalArgumentException(kind + " transform requires a channel");
        }
        if (kind == TransformKind.PLANE && (plane < 0 || plane > 7)) {
            throw new IllegalArgumentException("plane must be within 0..7 but was " + plane);
        }
    }

    /** The legacy bit mask for {@link TransformKind#FULL_CHANNEL} transforms. */
    public int channelMask() {
        if (kind != TransformKind.FULL_CHANNEL) {
            throw new IllegalStateException(label + " is not a full channel transform");
        }
        return channel.mask();
    }

    /** The ARGB bit position a {@link TransformKind#PLANE} transform reads. */
    public int argbBit() {
        if (kind != TransformKind.PLANE) {
            throw new IllegalStateException(label + " is not a bit plane transform");
        }
        return channel.shift() + plane;
    }

    /** Text used in the status bar and in saved metadata, e.g. {@code "Red plane 3"} — see {@link #label()}. */
    public String describe() {
        return label + " [" + (index + 1) + "/" + TransformCatalog.count() + "]";
    }

    @Override
    public String toString() {
        return index + ": " + label;
    }
}
