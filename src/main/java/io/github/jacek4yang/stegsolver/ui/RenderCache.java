package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.core.ImageData;
import javafx.scene.image.WritableImage;

/**
 * Bounded cache of rendered JavaFX images.
 *
 * <p>Stepping through transforms means converting tens of megabytes of pixels into a JavaFX image for
 * every plane, so the last few renders are kept in memory: going back to a plane that was just looked
 * at is then instant. The budget is derived from the image size, like the pixel cache of
 * {@link io.github.jacek4yang.stegsolver.transform.TransformEngine}.</p>
 */
final class RenderCache {

    static final long MIN_BUDGET_BYTES = 48L * 1024 * 1024;
    static final long MAX_BUDGET_BYTES = 512L * 1024 * 1024;

    private final long budgetBytes;
    private final java.util.LinkedHashMap<String, WritableImage> images =
            new java.util.LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    RenderCache(long budgetBytes) {
        this.budgetBytes = Math.max(MIN_BUDGET_BYTES, Math.min(MAX_BUDGET_BYTES, budgetBytes));
    }

    static long budgetFor(int width, int height) {
        long frame = 4L * width * height;
        return Math.max(MIN_BUDGET_BYTES, Math.min(MAX_BUDGET_BYTES, frame * 6));
    }

    /** Returns a cached render, or {@code null}. */
    WritableImage peek(String key) {
        return images.get(key);
    }

    /** Stores a render, evicting the least recently used entries to stay inside the budget. */
    void put(String key, WritableImage image, long imageBytes) {
        WritableImage previous = images.put(key, image);
        if (previous == null) {
            bytes += imageBytes;
        }
        while (bytes > budgetBytes && images.size() > 1) {
            var iterator = images.entrySet().iterator();
            var eldest = iterator.next();
            if (eldest.getKey().equals(key)) {
                eldest = iterator.next();
            }
            iterator.remove();
            bytes -= 4L * (long) eldest.getValue().getWidth() * (long) eldest.getValue().getHeight();
        }
    }

    /** Renders and caches an image for a key, computing it only on a miss. */
    WritableImage render(String key, ImageData data) {
        WritableImage cached = peek(key);
        if (cached != null) {
            return cached;
        }
        WritableImage image = FxUtils.toFxImage(data);
        put(key, image, 4L * data.pixelCount());
        return image;
    }

    void clear() {
        images.clear();
        bytes = 0;
    }

    int size() {
        return images.size();
    }

    long bytes() {
        return bytes;
    }
}
