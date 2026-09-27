# 1.0.0 release audit

## Evidence

- Java 21: 184 unit/integration tests, zero failures or skips. Tests include the legacy arithmetic
  oracle, malformed/truncated containers, binary QR payloads, generated Structured Append symbols,
  bounded frame/report handling, cancellation and queued callback races.
- A separate real-display check covers opening, transforms, same-size document replacement,
  cached-result invalidation, preview preservation, all 13 combine modes, GIF navigation and
  decoding a QR captured from the screen. Runs on Windows and X11/Xvfb in CI.
- `--self-test` exercises all engine layers through the bundled runtime. Packaging verifies
  JavaFX modules and native launchers. Linux packaging also renders through X11/Xvfb.
- The original import `16c2218` was reviewed directly; see [legacy parity](legacy-parity.md).
  No implemented original analysis capability was dropped.

## Measurements

Windows 11 x64, OpenJDK 21.0.2, JMH 1.37, one fork, two 1-second warmups and five 1-second
measurements, 2 GiB heap. Values are average milliseconds per operation; these are local
measurements, not universal latency promises. Raw summary files are in [benchmarks](benchmarks/).

| Operation, 1024x1024 | Before | After |
| --- | ---: | ---: |
| Extract all 32 planes | 146.109 | 22.866 |
| Extract RGB LSBs | 18.775 | 5.947 |
| Bit plane | 0.635 | 0.686 |
| Invert | 0.498 | 0.555 |
| Scan the QR fixture (640x480) | 31.336 | 32.367 |

Only the measured extraction hot loop was optimized: pack selected bits before emitting bytes
and return the filled output buffer without copying it. The transform loops were already fast.
At 4096x4096, GC profiling measured all-plane extraction at 371.006 ms / 67,111,495 bytes allocated
and RGB LSB extraction at 63.886 ms / 6,292,098 bytes: approximately the output size plus small
bookkeeping. The real-display Windows startup measurement was about 1.4?1.7 seconds with software
rendering. Removing a second image decode also removes that work from the JavaFX thread.

## Fixes verified during stabilization

Queued callbacks recheck their generation; superseded work is interrupted where cooperative.
Cached renders and tool previews invalidate earlier work. Scans read UI settings before dispatch.
Image loading decodes once; render jobs capture their document. Combining retains the first input
across mode changes. Frame readers are serialized and closed off JavaFX; thumbnails cannot
replace frame navigation jobs and are cached by size as well as frame index. Decoded dimensions
are checked before allocation, report growth is bounded, and extraction text exports stream.
Screen capture uses Robot user-space coordinates and the selected monitor's native resolution.
QR multi-detection preserves append headers; incomplete/conflicting sequences are not guessed.
Payload saving never substitutes text encoding or decoder raw codewords for BYTE_SEGMENTS.

## Limits

- Portable Windows ZIP and Linux tar.gz builds are unsigned. No installed Java/JavaFX is needed;
  Linux still needs the GTK 3/X11 libraries provided by Mint. Linux is built on Ubuntu 22.04;
  a physical Cinnamon session and mixed-DPI multi-monitor hardware were not available for testing.
- Wayland screen capture is unsupported. The overlay selects within one monitor. Screen permissions,
  other windows covering the selection and compositor timing can affect capture.
- Files are limited to 512 MiB; decoded images to `min(64 million pixels, heap bytes / 64)`
  (about 33.5 million pixels with the packaged 2 GiB heap). Decode needs transient buffers.
  ImageIO's frame counter may scan headers before the first preview, but pixels are decoded lazily.
- Frame cache: at most six frames and `min(128 MiB, heap / 16)`; thumbnails: 64 entries.
  Reports retain 10,000 entries and bound text fields. These limits are deliberate.
- Scan deadlines and cancellation are checked between ZXing passes; an individual decoder or
  ImageIO call is not forcibly terminated. Full scans of difficult large images may exceed the
  nominal budget; use an ROI.
- Structured Append retains up to 256 parts across images until Clear results. Parity is only
  eight bits, so it cannot prove sequence identity. Conflicting parts remain separate; review
  metadata before merging. Mixed QR modes expose byte-mode segments separately from decoded text.
- GIF frame access shows each ImageIO frame's stored pixels, as the legacy browser did; it is an
  analysis browser, not a disposal-aware animation player. Random maps use documented fixed seeds.
