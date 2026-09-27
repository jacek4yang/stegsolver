package io.github.jacek4yang.stegsolver.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Lazy access to the frames of a multi frame image (animated GIF, multi page TIFF, ...).
 *
 * <p>The original frame browser decoded every frame as soon as the window opened, twice: once for the
 * frame list and once for the thumbnails. A large GIF could take seconds and hundreds of megabytes
 * before the user saw anything. This source keeps a bounded number of decoded frames in memory and
 * decodes the rest on demand, which is what makes the frame browser usable on large animations.</p>
 */
public final class FrameSource implements AutoCloseable {

    /** Default number of decoded full size frames kept in memory. */
    public static final int DEFAULT_CACHE_SIZE = 6;

    private final Path path;
    private final ImageReader reader;
    private final ImageInputStream stream;
    private final int frameCount;
    private final int cacheSize;
    private final Map<Integer, ImageData> frameCache = new LinkedHashMap<>(8, 0.75f, true);
    private final Map<Integer, ImageData> thumbnailCache = new LinkedHashMap<>(32, 0.75f, true);

    private FrameSource(Path path, ImageReader reader, ImageInputStream stream, int frameCount,
            int cacheSize) {
        this.path = path;
        this.reader = reader;
        this.stream = stream;
        this.frameCount = frameCount;
        this.cacheSize = cacheSize;
    }

    /**
     * Opens the file for lazy frame access.
     *
     * @throws IOException if there is no reader for the format or the header cannot be read
     */
    public static FrameSource open(Path path) throws IOException {
        return open(path, DEFAULT_CACHE_SIZE);
    }

    public static FrameSource open(Path path, int cacheSize) throws IOException {
        if (path == null) {
            throw new IOException("No file given");
        }
        ImageInputStream stream = ImageIO.createImageInputStream(path.toFile());
        if (stream == null) {
            throw new IOException("Could not open an image input stream for " + path.getFileName());
        }
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            stream.close();
            throw new IOException("No image reader available for " + path.getFileName());
        }
        ImageReader reader = readers.next();
        reader.setInput(stream, false, false);
        int frameCount;
        try {
            // allowSearch = true, because some readers need to scan the file to count the images.
            frameCount = reader.getNumImages(true);
        } catch (IOException | RuntimeException e) {
            frameCount = -1;
        }
        return new FrameSource(path, reader, stream, frameCount, Math.max(1, cacheSize));
    }

    public Path path() {
        return path;
    }

    /** Number of frames, or {@code -1} when the reader cannot determine it. */
    public int frameCount() {
        return frameCount;
    }

    public boolean hasKnownFrameCount() {
        return frameCount > 0;
    }

    /** Width of a frame without decoding it, or {@code -1} when unavailable. */
    public int frameWidth(int index) {
        try {
            return reader.getWidth(index);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    public int frameHeight(int index) {
        try {
            return reader.getHeight(index);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Decodes a frame, using the bounded cache. */
    public ImageData frame(int index) throws IOException {
        if (index < 0) {
            throw new IOException("Frame index must not be negative: " + index);
        }
        if (frameCount > 0 && index >= frameCount) {
            throw new IOException("Frame " + index + " is outside 1.." + frameCount);
        }
        ImageData cached = frameCache.get(index);
        if (cached != null) {
            return cached;
        }
        var image = reader.read(index);
        if (image == null) {
            throw new IOException("Frame " + index + " could not be decoded");
        }
        ImageData data = ImageData.fromBufferedImage(image);
        frameCache.put(index, data);
        while (frameCache.size() > cacheSize) {
            var iterator = frameCache.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return data;
    }

    /** A cached downscaled preview of a frame, cheap enough for a thumbnail strip. */
    public ImageData thumbnail(int index, int maxSize) throws IOException {
        ImageData cached = thumbnailCache.get(index);
        if (cached != null) {
            return cached;
        }
        ImageData full = frame(index);
        int longest = Math.max(full.width(), full.height());
        ImageData thumbnail = longest <= maxSize ? full : ImageOps.scaleNearest(full, maxSize / (double) longest);
        thumbnailCache.put(index, thumbnail);
        if (thumbnailCache.size() > 64) {
            var iterator = thumbnailCache.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return thumbnail;
    }

    /** Number of full size frames currently held in memory, used in diagnostics. */
    public int cachedFrameCount() {
        return frameCache.size();
    }

    @Override
    public void close() {
        frameCache.clear();
        thumbnailCache.clear();
        try {
            reader.dispose();
        } catch (RuntimeException ignored) {
            // Disposing a reader must never mask the original failure.
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing the stream is best effort.
        }
    }
}
