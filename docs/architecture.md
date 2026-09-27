# Architecture

StegSolver is a JavaFX desktop application built from a plain Maven project, without a framework.
The code is divided into layers that can be reasoned about (and tested) independently.

```
io.github.jacek4yang.stegsolver
├── Launcher                  main() -> Application.launch (class path safe entry point)
├── StegSolverApp             JavaFX Application: window, command line file, smoke test
├── core/                     pixel model, geometry, image I/O, background jobs, frame access
│   ├── ImageData             width/height + int[] ARGB (+ palette indices for indexed sources)
│   ├── ImageOps              rotate/scale/crop on int[]
│   ├── Roi, ViewportGeometry zoom/pan/coordinate mapping, pure maths, no toolkit
│   ├── CoalescingJobRunner   background work that discards superseded requests
│   ├── ImageIoUtil           loading, atomic saving, format detection, writer fallbacks
│   ├── FrameSource           lazy multi frame access with a bounded cache
│   └── HexDump               the single hex/ASCII renderer used everywhere
├── transform/                the 42 transform catalog and the pixel operations behind it
├── extract/                  bit extraction: options, plan building, bit packing
├── parser/                   strict, bounds checked PNG/JPEG/GIF/BMP structure analysis
├── barcode/                  scanning, luminance source, rotation mapping, payload typing
└── ui/                       JavaFX only: window, viewport, tool panes, theming
```

## Data flow

```
file ──ImageIoUtil.load──► ImageData (int[] ARGB, optional palette)
                                │
                                ├─► TransformEngine ──► int[]  ──► RenderCache ──► JavaFX image
                                │        (LRU, bounded)              (LRU, bounded)
                                ├─► DataExtractor ──► byte[] ──► PayloadDetector, HexDump
                                ├─► BarcodeScanner ──► payload bytes ──► PayloadDetector
                                └─► FileAnalyzer ──► FileReport (text)
```

`ImageData` is the single pixel currency of the application: the transforms, the extraction, the
barcode scanner and the file analyser all work on flat `int[]` arrays with no per pixel object
allocation and no AWT/Swing dependency. `ImageData` is only converted to a `BufferedImage` when an
image is written with ImageIO (which is the only image codec available in the JDK).

## Threading

* Everything that touches the scene graph runs on the JavaFX Application Thread.
* `CoalescingJobRunner` provides the background executors. It keeps at most one request in flight and
  one pending request: requests that are superseded before they start are never computed, and a
  result that has been overtaken is never delivered. This is what makes holding the right arrow key
  through transforms smooth on a large image, and it is unit tested without a toolkit by injecting a
  direct executor.
* Expensive work that is not on a hot navigation path (file loading, file analysis, extraction,
  barcode scanning, screen capture) uses virtual threads and delivers through `Platform.runLater`.
* JavaFX images (`WritableImage`) are built on the background thread — the pixel copy is the expensive
  part — and only handed to the scene graph on the JavaFX thread.

## Caching

Two bounded caches, both sized from the image:

* `TransformEngine` caches transform pixel arrays (an LRU map with a byte budget of up to eight
  frames, capped by heap size). A cache hit is what stepping back to the previous plane costs.
* `RenderCache` caches the JavaFX images produced from those arrays (up to six frames, capped by heap size).

Neither cache can grow without bound, which matters because a 12 megapixel image costs roughly 48 MB
per transform.

## Why the viewport is hand written

`ImageViewport` positions an `ImageView` inside a clipped pane instead of using a `ScrollPane`. That
makes "zoom at the cursor", "fit", "1:1" and, above all, mapping a dragged rectangle back to image
pixels deterministic: all of that arithmetic lives in `ViewportGeometry`, and is unit tested without
touching the toolkit (see `ViewportGeometryTest`). Region selection, the pixel inspector and the
barcode overlay are expressed in image coordinates and projected through the same geometry.

## Why tools share the central viewport

The original application opened a separate window per tool. Here, every tool is a tab in the dock,
and tools that produce an image (stereogram solve, image combine, frame browser) show their result in
the central viewport with a clear "VIEWING … (document unchanged)" badge. The open document and its
file are never modified by a tool, which is the concrete requirement behind "image combine without
corrupting the primary document/file state": combine reads the displayed image, produces a new
`ImageData`, and leaves the document alone.

## Error handling

* The file analysers never throw: every read is bounds checked through `ByteReader`, and a structural
  problem becomes a warning in the report rather than an exception. The tests truncate every format
  at every length, corrupt single bytes and feed random data behind valid signatures.
* Loading and saving report clear messages; saving writes to a temporary file first and moves it into
  place, and falls back to an image representation the chosen writer supports (BMP and JPEG cannot
  store alpha, so a transparent image is composited over white and the user is told).
* A failing background scan or tool computation updates the status bar; it never interrupts what the
  user is doing.

## Notable decisions

* **No `module-info.java`.** ZXing is an automatic module, and `jlink` refuses to link automatic
  modules. Keeping the application non modular lets ZXing stay a normal class path library while the
  runtime image still contains the JavaFX modules (see [packaging.md](packaging.md)).
* **`java.awt.image` is used, `javax.swing` is not.** ImageIO is the only image decoder in the JDK, so
  `BufferedImage` is used at the boundary, and `java.awt.Robot` for X11 screen capture. No Swing
  component or dialog exists anywhere in the application.
* **Deterministic random colour maps.** The legacy tool drew a fresh random mapping on every visit, so
  the same transform looked different each time. The rewrite uses a fixed seed per variant, which
  makes the transform cacheable, comparable and reproducible. The mapping arithmetic itself is
  unchanged and verified against the legacy code.

## Stabilization constraints

Decode once off the JavaFX thread and install the resulting pixels. Every coalesced callback
checks its generation again on the delivery thread; cancellation interrupts cooperative workers.
Scans snapshot all control values before dispatch. Frame access is serialized, thumbnails have
independent request identities, and source disposal runs off JavaFX. Explicit file saves use a
separate queue and finish during shutdown instead of being dropped by navigation.

Images are checked before decoding against `min(64 million pixels, maximum heap / 64)`;
files are limited to 512 MiB. Full frames have both a six-entry and 128 MiB/heap-based budget.
Thumbnail caching is limited to 64 entries with a maximum requested side of 512 pixels.
Reports stop retaining detail after 10,000 entries; untrusted text fields are bounded.
