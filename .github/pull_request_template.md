<!--
  Pull request template.

  Keep it useful rather than ceremonial: the first two sections are the ones a reviewer actually reads.
-->

## What changes for the user

<!-- One or two sentences. If nothing is visible to the user, say that. -->

## Which layer

<!-- Tick what applies. See docs/architecture.md for the layering rules. -->

- [ ] `core` (pixel model, geometry, image I/O, background jobs)
- [ ] `transform` (transform catalog, combine modes, stereogram solver)
- [ ] `extract` (bit extraction)
- [ ] `parser` (file structure analysis)
- [ ] `barcode` (scanning, payload handling)
- [ ] `ui` (JavaFX window, viewport, panes, theming)
- [ ] `selfcheck` (headless self test)
- [ ] build, packaging, CI or documentation only

## Tests

<!-- Which test covers this? New behaviour should come with a test; a bug fix should come with a
     regression test that fails without the fix. -->

## Legacy parity

<!-- Does this change what a transform, an extraction or a file analysis produces?
     If yes, say how and update docs/legacy-parity.md. If no, say "not affected". -->

Not affected / Describe:

## How it was verified

<!-- The checks that ran, and what you looked at by hand. -->

- [ ] `mvn -B -ntp verify` passes
- [ ] `mvn -B -ntp -Pself-test exec:exec` passes
- [ ] Checked by hand in the running application, where applicable

## Checklist

- [ ] No Swing component or `javax.swing` import was introduced (the UI is JavaFX only)
- [ ] Untrusted data is still treated as untrusted: decoded payloads are never opened, extracted or
      executed, and payload bytes are never reconstructed from decoded text
- [ ] Public classes and methods have a javadoc sentence explaining *why*, not *what*
- [ ] No build output, generated binary, IDE file or secret is committed
- [ ] Comments explain intent; dead code and debug output were removed
