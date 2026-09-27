package io.github.jacek4yang.stegsolver.ui;

import io.github.jacek4yang.stegsolver.barcode.ScanResult;
import io.github.jacek4yang.stegsolver.core.ImageData;
import io.github.jacek4yang.stegsolver.core.ImageIoUtil;
import io.github.jacek4yang.stegsolver.core.Roi;
import io.github.jacek4yang.stegsolver.transform.TransformCatalog;
import io.github.jacek4yang.stegsolver.transform.TransformDef;
import io.github.jacek4yang.stegsolver.transform.TransformEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The primary document: the file that was opened, its pixels, the transform that is being viewed and
 * the last background barcode scan.
 *
 * <p>Deliberately separate from the viewport: the viewport can show a tool preview (a stereogram
 * solve, a combine result, a frame) without touching the document, which is what keeps "combine" and
 * friends from corrupting the open file.</p>
 *
 * <p>Everything here runs on the JavaFX application thread; the expensive parts ({@link TransformEngine})
 * are only used from there or from the coalescing background job, which never mutates this object.</p>
 */
public final class DocumentSession {

    /** Notified when the document or the viewed transform changes. */
    @FunctionalInterface
    public interface Listener {
        void documentChanged();
    }

    private final List<Listener> listeners = new ArrayList<>();

    private Path path;
    private String displayName;
    private ImageData image;
    private TransformEngine engine;
    private int transformIndex;
    private ScanResult backgroundScan;
    private long loadedBytes;

    public boolean isOpen() {
        return image != null;
    }

    public Path path() {
        return path;
    }

    /** The file name, or the description of an in-memory document such as a single frame. */
    public String fileName() {
        if (path != null) {
            return path.getFileName().toString();
        }
        return displayName == null ? "(no file)" : displayName;
    }

    /** True when the document has no file on disk (for example a frame opened from an animation). */
    public boolean isInMemory() {
        return path == null;
    }

    public ImageData image() {
        return image;
    }

    public TransformEngine engine() {
        return engine;
    }

    public int transformIndex() {
        return transformIndex;
    }

    public TransformDef transform() {
        return TransformCatalog.byIndex(transformIndex);
    }

    public ScanResult backgroundScan() {
        return backgroundScan;
    }

    public void setBackgroundScan(ScanResult result) {
        this.backgroundScan = result;
    }

    /** Size of the decoded file, used in the status bar and the info panel. */
    public long loadedBytes() {
        return loadedBytes;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Opens a file, replacing the current document. */
    public void open(Path file) throws IOException {
        ImageData loaded = ImageIoUtil.load(file);
        this.path = file;
        this.displayName = file.getFileName().toString();
        this.image = loaded;
        this.engine = new TransformEngine(loaded);
        this.transformIndex = 0;
        this.backgroundScan = null;
        try {
            this.loadedBytes = Files.size(file);
        } catch (IOException e) {
            this.loadedBytes = 0;
        }
        notifyListeners();
    }

    /**
     * Opens an image that has no file on disk, such as one frame of an animation. Analysis of the
     * original file is unavailable for such a document, but every transform works.
     */
    public void openFromImage(ImageData data, String description) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        this.path = null;
        this.displayName = description;
        this.image = data;
        this.engine = new TransformEngine(data);
        this.transformIndex = 0;
        this.backgroundScan = null;
        this.loadedBytes = data.estimatedBytes();
        notifyListeners();
    }

    public void close() {
        path = null;
        displayName = null;
        image = null;
        engine = null;
        transformIndex = 0;
        backgroundScan = null;
        loadedBytes = 0;
        notifyListeners();
    }

    /** Sets the transform to view, clamped to the catalog. */
    public void setTransformIndex(int index) {
        int clamped = Math.max(0, Math.min(index, TransformCatalog.count() - 1));
        if (clamped != transformIndex) {
            transformIndex = clamped;
            notifyListeners();
        }
    }

    public void nextTransform() {
        setTransformIndex(TransformCatalog.next(transformIndex));
    }

    public void previousTransform() {
        setTransformIndex(TransformCatalog.previous(transformIndex));
    }

    public void nextTransformInGroup() {
        setTransformIndex(TransformCatalog.nextInGroup(transformIndex));
    }

    public void previousTransformInGroup() {
        setTransformIndex(TransformCatalog.previousInGroup(transformIndex));
    }

    /** The pixels of the transform currently being viewed. Valid until the transform changes. */
    public int[] currentPixels() {
        return engine.pixelsFor(transformIndex);
    }

    /** The transform currently being viewed as an image, used for saving and for tool input. */
    public ImageData currentImage() {
        return engine.imageFor(transform());
    }

    /** The image the user means when a tool needs "the image": the view when it is the document. */
    public Roi fullRoi() {
        return image == null ? Roi.EMPTY : Roi.whole(image.width(), image.height());
    }

    private void notifyListeners() {
        for (Listener listener : List.copyOf(listeners)) {
            listener.documentChanged();
        }
    }
}
