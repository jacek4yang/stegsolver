# Changelog

## 1.1.0 — 2026-09-28

- Adds bounded Auto LSB Fast and Deep scans with ranked payload candidates.
- Applies a candidate's extraction settings directly to the manual Extract tool and saves the complete binary payload on request.
- Makes tool panes scroll and wrap so important JavaFX control text remains visible.
- Improves scan cancellation, stale-result handling, and candidate deduplication.

## 1.0.0 ? 2026-09-27

First modern release, following the repository's 1.0.0-SNAPSHOT development version.

- JavaFX desktop interface on Java 21; portable Windows and Linux distributions bundle both.
- Retains all 42 legacy transforms, extraction conventions, 13 combine modes, stereograms,
  animation frames and PNG/JPEG/GIF/BMP structural analysis.
- Whole-image, image-region and X11/Windows screen-region barcode scanning, multiple symbols,
  and explicit Structured Append merging. Exact BYTE_SEGMENTS saving stays separate from
  decoded text and raw decoder codewords; payloads are never opened or executed.
- Fixes duplicate image decoding, stale callbacks, combine input reuse, frame request collisions,
  HiDPI capture coordinates and malformed-image allocation hazards.
- Bounded frame/report memory and streamed extraction text exports. Measured packed extraction
  improves all-plane throughput about 6.4x and RGB LSB throughput about 3.2x at 1024x1024.

See [release audit](docs/release-audit.md) for test evidence, benchmark conditions and limitations.
