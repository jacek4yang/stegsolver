package io.github.jacek4yang.stegsolver.transform;

import io.github.jacek4yang.stegsolver.core.ImageData;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes and caches the pixel array of a transform for one source image.
 *
 * <p>Transform results are cached in a bounded least recently used map sized from the image, because
 * interactive navigation (holding an arrow key, or stepping back and forth between two suspicious
 * planes) recomputes the same transforms constantly while a large image costs tens of megabytes per
 * plane.</p>
 *
 * <p>Instances are safe to use from a background thread while the user interface reads from another;
 * the cache itself is only locked for short bookkeeping operations and the (expensive) computation
 * happens outside the lock.</p>
 */
public final class TransformEngine {

    /** Hard limits for the pixel cache, in bytes. */
    public static final long MIN_CACHE_BYTES = 32L * 1024 * 1024;
    public static final long MAX_CACHE_BYTES = 768L * 1024 * 1024;

    private final ImageData source;
    private final long cacheBudgetBytes;
    private final Map<Integer, int[]> cache = new LinkedHashMap<>(32, 0.75f, true);
    private long cachedBytes;

    public TransformEngine(ImageData source) {
        this(source, defaultCacheBudget(source));
    }

    public TransformEngine(ImageData source, long cacheBudgetBytes) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.source = source;
        this.cacheBudgetBytes = Math.max(MIN_CACHE_BYTES, cacheBudgetBytes);
    }

    /** A cache budget of eight images, clamped to a sane range. */
    public static long defaultCacheBudget(ImageData source) {
        long frameBytes = Math.max(1, source.estimatedBytes());
        return Math.max(MIN_CACHE_BYTES, Math.min(MAX_CACHE_BYTES, Math.min(Runtime.getRuntime().maxMemory() / 8, frameBytes * 8)));
    }

    public ImageData source() {
        return source;
    }

    public long cacheBudgetBytes() {
        return cacheBudgetBytes;
    }

    /** The pixels of a transform, computing them on first use. The result must not be modified. */
    public int[] pixelsFor(int transformIndex) {
        return pixelsFor(TransformCatalog.byIndex(transformIndex));
    }

    /** The pixels of a transform, computing them on first use. The result must not be modified. */
    public int[] pixelsFor(TransformDef def) {
        if (def.kind() == TransformKind.ORIGINAL) {
            return source.pixels();
        }
        int[] cached;
        synchronized (cache) {
            cached = cache.get(def.index());
        }
        if (cached != null) {
            return cached;
        }
        int[] computed = ImageTransforms.apply(def, source);
        cache(def.index(), computed);
        return computed;
    }

    /** The transform result as an {@link ImageData} (sharing the cached array). */
    public ImageData imageFor(TransformDef def) {
        if (def.kind() == TransformKind.ORIGINAL) {
            return source;
        }
        return ImageData.opaque(source.width(), source.height(), pixelsFor(def));
    }

    private void cache(int index, int[] pixels) {
        synchronized (cache) {
            int[] previous = cache.put(index, pixels);
            if (previous == null) {
                cachedBytes += 4L * pixels.length;
            }
            // Never evict the entry we just inserted, even when it alone exceeds the budget.
            while (cachedBytes > cacheBudgetBytes && cache.size() > 1) {
                var iterator = cache.entrySet().iterator();
                Map.Entry<Integer, int[]> eldest = iterator.next();
                if (eldest.getKey() == index) {
                    eldest = iterator.next();
                }
                iterator.remove();
                cachedBytes -= 4L * eldest.getValue().length;
            }
        }
    }

    /** Number of transforms currently held in memory. */
    public int cachedTransformCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Bytes of pixel data currently held in the cache. */
    public long cachedBytes() {
        synchronized (cache) {
            return cachedBytes;
        }
    }

    public boolean isCached(int transformIndex) {
        synchronized (cache) {
            return cache.containsKey(transformIndex);
        }
    }

    public void clearCache() {
        synchronized (cache) {
            cache.clear();
            cachedBytes = 0;
        }
    }

    @Override
    public String toString() {
        return "TransformEngine[" + source + ", cached=" + cachedTransformCount() + "]";
    }
}
