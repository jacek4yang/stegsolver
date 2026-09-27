# Legacy parity: what was kept, what changed

This project is a rebuild of the original StegSolve (Swing, Java 17, package `team.stinger`). The
original sources are preserved in the first commit of this repository
(`chore: import legacy StegSolve (Swing) sources for reference`), so every statement below can be
diffed against them.

## Preserved exactly

| Legacy | Rewrite | Notes |
| --- | --- | --- |
| `Transform` transform numbers 0..41 | `TransformCatalog` | Same order: 0 original, 1 inversion, 2..9 alpha planes 7..0, 10..17 red, 18..25 green, 26..33 blue, 34..37 full alpha/red/green/blue, 38..40 random colour maps, 41 gray pixels |
| `Transform.back()` / `forward()` wrap around | `TransformCatalog.previous/next` | 41 -> 0 forward, 0 -> 41 backward |
| `transfrombit(bit)` bit plane rendering | `ImageTransforms.bitPlane` | White where the bit is set, black otherwise |
| `transmask(mask)` with its `>>>= 8` quirk | `ImageTransforms.mask` | Including "full alpha" rendering as the red channel |
| `inversion()` = `pixel XOR 0xFFFFFF` | `ImageTransforms.invert` | |
| `graybits()` | `ImageTransforms.grayPixels` | White where r == g == b |
| `random_colormap()` arithmetic | `ImageTransforms.randomComponentMap` | Same expression, verified byte for byte against `LegacyReference` |
| `random_indexmap()` palette mapping | `ImageTransforms.randomPaletteMap` | Applied to the palette, not to the resolved colours |
| `CombineTransform` 13 modes | `CombineMode` | Same per channel arithmetic, carry behaviour included |
| `CombineTransform.calcInterlace()` | `CombineMode.INTERLACE_ROWS/COLUMNS` | Same use of the smaller of the two sizes |
| `StereoTransform.calcTrans()` | `StereoTransform.shiftedXor` | Same wrap around XOR formula |
| `Extract` mask, traversal, bit order, channel order, MSB first packing | `DataExtractor`, `ExtractionOptions`, `RgbOrder` | Verified against `LegacyReference.extract` for every option combination |
| Absolute file offsets and byte values in the file analysis | `parser` package | Same fields reported |
| Transform state per transform (not per file) | `DocumentSession` | Opening an image starts at transform 0 |

`LegacyReference` (test sources) is a literal transcription of the legacy algorithms and is the oracle
for those parity tests: `TransformCatalogTest.legacyParityForAllTransforms`,
`DataExtractorTest.legacyParity`, `CombineModeTest.legacyParityForEveryMode` and
`StereoTransformTest.legacyParity`.

## Changed on purpose

| Change | Why |
| --- | --- |
| Swing/FlatLaf replaced by JavaFX | Requirement; also removes the mixed `JFrame` dialogs |
| Tools are dock tabs, results appear in the central viewport | Replaces six floating windows; the document stays untouched |
| `ImageData` (`int[]` ARGB) instead of per pixel `getRGB`/`setRGB` | The legacy per pixel `BufferedImage` calls were the dominant cost and made large images unusable |
| Background transform rendering with superseded-request dropping | Holding an arrow key no longer blocks the event thread |
| Bounded transform and render caches | Revisiting a plane is instant without unbounded memory growth |
| Data extraction preview is bounded, with the total size shown | The legacy preview built a `StringBuilder` of the whole extract and froze the UI on large images |
| Extraction gained bit inversion and region extraction | Both were legacy `TODO`s |
| Random colour maps use a fixed seed per variant | Revisiting a transform shows the same mapping, so it can be cached and compared |
| Interlace mode labels fixed | The legacy tool called the row interlacing mode "Horizontal Interlace" and the column one "Vertical Interlace"; the names now describe the geometry, the operations are unchanged |
| QR/barcode handling rewritten | See below |
| Frame browser loads frames lazily | It decoded every frame twice (list and thumbnails) before showing anything |
| `ImageIoUtil.save` falls back to a writer supported representation | BMP and JPEG silently failed for images with an alpha channel |
| Chinese UI strings translated to English | The application is documented and tested in English; no behaviour depends on it |

## Bugs found in the legacy code and fixed here

1. **PNG chunk CRC** was computed over the wrong range.
2. **JPEG segment lengths** were read one byte too early, so every segment after SOI was misparsed
   (the legacy analyser then reported the rest of the file as "additional bytes").
3. **The chunk list was walked past IEND**, which hid data appended after the end of the PNG.
4. **PNG chunk type flags** were read from the file signature (`f[4]`) instead of from the chunk name,
   so every chunk was reported as "critical, public, safe to copy" and reserved bit problems were
   never detected.
5. **Unbounded recursion/loops** in the GIF and JPEG walkers could run off the end of the buffer
   (`ArrayIndexOutOfBoundsException` on truncated files) or loop on malformed input. All traversal is
   now bounds checked and non recursive.
6. **Hex dumps were unbounded**: a comment block or an appended payload could produce megabytes of
   text. Dumps and previews have explicit limits.
7. **`getText().getBytes(...)`** was used as the QR payload in `QRcodeDecode`, which corrupts binary
   payloads. The rewrite uses `BYTE_SEGMENTS` and never reconstructs bytes from text.
8. **A hardcoded `CHARACTER_SET=UTF-8` hint** was passed to ZXing, which mangles the decoded text of
   ISO-8859-1 symbols. The hint is now left unset so ZXing's heuristics apply; the payload bytes are
   unaffected either way because they come from `BYTE_SEGMENTS`.
9. **`FileAnalysis.report` was an HTML `JEditorPane`**, so `&`, `<` and `>` in a dump were interpreted
   as markup. The report is plain text now.

## Barcode and QR handling

The legacy tool decoded a single symbol from a grayscale copy of the image and showed the text, with
no way to tell binary payloads from text. The rewrite:

* scans the whole image, a dragged region, or an X11 screen region;
* finds several symbols in one image and merges QR Structured Append sequences (within one bitmap via
  ZXing's `QRCodeMultiReader`, across scans via `StructuredAppendMerger`);
* tries `TRY_HARDER`, both polarities, quarter turns, the global histogram binarizer, a rescaled copy
  and pure barcode mode, under a time budget;
* keeps three representations strictly apart — payload bytes (`BYTE_SEGMENTS`, concatenated verbatim),
  decoded text, and ZXing's raw codewords, which for QR are the error corrected data codewords *with*
  mode and padding bits and therefore not the payload;
* classifies payloads (ZIP, 7z, gzip, RAR, tar, bzip2, xz, PNG, JPEG, GIF, BMP, WebP, PDF, ELF, PE,
  Java class files, text, base64, binary) with a suggested extension, entropy, SHA-256 and details
  such as archive entry counts or image dimensions;
* never opens, extracts or executes a payload.

## Compatibility notes for users of the original tool

* The transform sequence and numbering are identical, so "press right arrow eleven times" still lands
  on the same plane.
* The extraction options have the same meaning, including "alpha is always read first".
* The default extraction selection differs: the original started with nothing selected, the rewrite
  preselects the LSBs of red, green and blue, which is where LSB steganography normally lives.
* Preview text is the same layout (offset, hex with a gap after eight bytes, ASCII column), with an
  offset column added.
* The file analysis report is plain text instead of HTML, and its section names are English.
