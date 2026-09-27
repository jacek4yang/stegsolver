# Contributing

Thanks for taking the time to improve StegSolver. This file describes how the project is built,
tested and reviewed.

## Ground rules

* Target **JDK 21** and **JavaFX only**. No Swing components, no `javax.swing` imports; the CI job
  fails the build if any appear.
* Keep the layers separate: `core`, `transform`, `extract`, `parser`, `barcode` must not depend on
  `ui` or on JavaFX. Only `ui` may touch the scene graph.
* Preserve the legacy transform numbering and extraction conventions. If a change would alter what a
  transform or an extraction produces, say so explicitly in the pull request and update
  `docs/legacy-parity.md` and `LegacyReference` in the tests.
* Never introduce a plugin system, a dependency injection framework, a database or a web framework.
* Decoded payloads are untrusted: never execute, open, unpack or extract them, and never reconstruct
  payload bytes from decoded text (use `BYTE_SEGMENTS`).

## Workflow

1. Branch from `main`: `feature/short-description` or `fix/short-description`.
2. Keep commits focused and write them in the imperative mood with a scope prefix, for example
   `feat(barcode): keep BYTE_SEGMENTS exact for merged structured append symbols`.
3. Run `mvn -B -ntp verify` before pushing. CI runs the same on Linux and Windows.
4. Open a pull request describing the behaviour change, the tests that cover it and any parity
   implications. `main` is kept clean: every merge is a merge commit with a green build.

## Building and running

```bash
mvn -B -ntp verify                 # compile + all tests
mvn -B -ntp -Pself-test exec:exec  # run every engine layer once (no display needed)
scripts/run.sh                     # run the application from sources
scripts/run.sh --smoke             # start, render a few transforms, exit (quick sanity check)
scripts/run.sh suspicious.png
```

`scripts/run.sh` and `scripts/run.ps1` compile, resolve the runtime class path and start the
launcher, which is also the way to test a change interactively without packaging.

## Tests

* Unit and integration tests live in `src/test/java`; they must not need a display, so they never
  initialise the JavaFX toolkit. Anything that needs geometry, mapping or arithmetic must therefore
  be extractable into a plain class (see `ViewportGeometry`, `RotationMapper`, `HitMerge`).
* Image and barcode fixtures are generated in memory (`TestImages`), so the repository contains no
  binary fixtures and a reviewer can read exactly what a test feeds in.
* Malformed input matters: if you touch a parser, extend `FileAnalyzerTest`'s truncation, corruption
  and garbage cases.
* Legacy parity tests compare against `LegacyReference`, a transcription of the original algorithms.
  Add a case there rather than hard coding an expected array.
* If you add an engine layer, extend `selfcheck.SelfTest` so that the headless check (and therefore
  continuous integration) notices when it breaks. Keep the step's output short and informative: it is
  read by a human when something fails.

## Benchmarks

The JMH benchmarks live in `src/bench/java` and are only compiled by the `bench` profile, so the
normal build never sees them.

```bash
scripts/bench.sh                                  # everything
scripts/bench.sh TransformBenchmark 4096          # one benchmark, one image size
mvn -B -ntp -Pbench test-compile exec:exec -Dbench.include=AnalysisBenchmark
```

Update the numbers in a pull request description when you change a hot path: transform computation,
extraction, combination and barcode scanning are the paths that decide whether the application feels
instant.

## Pull requests

`main` is protected: every change goes through a pull request that must pass the required checks
(`Build and test` on Linux and Windows, and the Xvfb smoke test). Direct pushes to `main` are
rejected, including for maintainers, because a rule that only applies to some contributors is a rule
nobody trusts.

Please keep a pull request:

* **focused** — one behaviour change, one PR; split unrelated cleanups into their own branch,
* **documented** — what changes for the user, which tests cover it and whether legacy parity is
  affected,
* **green** — run `mvn -B -ntp verify` and `mvn -B -ntp -Pself-test exec:exec` locally first; the
  checks the CI runs are exactly those two plus the benchmark compile and the Swing guard.

Use `Fixes #123` in the description to close an issue automatically, and prefer a merge commit so the
branch history stays readable.

## Packaging

```bash
packaging/package-linux.sh
```

```powershell
powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1
```

Do not commit build output: `target/`, `dist/` and packaged artifacts are ignored. If you change the
JavaFX version, the module list or the launcher, verify the packaged application with
`--version` and with the smoke test described in [docs/packaging.md](docs/packaging.md).

## Style

* Four spaces, no tabs, UTF-8, LF line endings (`.gitattributes` enforces the last two).
* Explain *why* in comments, not *what*; the code says what it does.
* Prefer small, single purpose classes and records over deep hierarchies.
* Public API needs a javadoc sentence; internal helpers do not need ceremony.
