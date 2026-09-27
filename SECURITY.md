# Security policy

## Reporting a vulnerability

Please report security issues **privately** through GitHub's
[private vulnerability reporting](https://github.com/jacek4yang/stegsolver/security/advisories/new)
rather than in a public issue.

Please include what you can: the version (or commit), your platform, the file that triggers the problem
if it is not sensitive, and what you expected to happen. A crash is interesting; a crash that reads or
writes memory outside the file it was given is much more interesting.

You can expect an initial response within about a week. This is a spare-time project, so please allow
for that; a fix will be released as soon as it is ready and you will be credited unless you prefer
otherwise.

## Supported versions

Fixes are released on the latest version. There is no long term support branch.

## Threat model

StegSolver is an analysis tool, which means its **inputs are hostile by design**. The file a user opens
is exactly the file they do not trust, and the payload hidden inside it is usually worse.

### In scope

* **Memory safety and bounds handling in the parsers.** `parser/` walks PNG chunks, JPEG segments, GIF
  blocks and BMP headers by hand. Every read must be bounds checked; a file that declares an impossible
  length, a chunk that runs past the end of the buffer or a truncated scan must produce a warning, not
  an exception, an infinite loop, or an out of bounds access.
* **Resource exhaustion on malformed input.** A file that is 10 bytes long must not be able to make the
  application allocate gigabytes, and a malformed structure must not be able to loop forever. Files
  above 512 MiB are refused up front; dumps and previews are bounded.
* **Handling of decoded payloads.** A barcode or QR payload is untrusted data. The application must
  classify and save it, never execute, open, unpack or extract it, and never reconstruct payload bytes
  from decoded text (`getText().getBytes(...)` corrupts binary data).
* **Command execution.** The application must not pass file or payload content to a shell. The only
  external processes it starts are read-only desktop queries for the theme
  (`reg query`, `gsettings get`) with fixed arguments.

### Out of scope

* A decoded payload doing something harmful **after the user saved it and opened it themselves**. The
  application warns that a payload is an archive, document or executable; what happens next is the
  user's decision.
* Anything requiring an attacker to already control the machine, the JDK or the Maven build.
* Denial of service through an image that is simply large and legitimate (bounded and reported, but a
  4000x4000 bitmap is expected to take memory and time).
* Missing hardening that upstream JavaFX, ZXing or the JDK would have to provide; report those upstream.

## What the application does to reduce risk

* Structural analysis is written against a bounds checked `ByteReader`; parsers cannot read outside the
  buffer even on malformed input, and the test suite truncates every format at every length, corrupts
  single bytes and feeds random data behind valid signatures.
* Decoded payloads and extracted data are written byte for byte, never through a text encoding.
* Payload types are shown before anything is saved, with a warning for archives, documents and
  executables.
* Files opened by the application are read only; the only writes are the ones the user explicitly asks
  for, and they go to a temporary file that is moved into place so a failure cannot corrupt an existing
  file.
* Screenshots for the "scan a screen region" feature are taken through the platform (`java.awt.Robot`)
  and only on X11, where that is what the session intends; on Wayland the feature refuses instead of
  guessing.
* Continuous integration runs the build and the tests on Linux and Windows, a code scanning analysis
  (CodeQL), Dependabot for dependency updates and secret scanning with push protection.
