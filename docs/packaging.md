# Packaging

The goal: a user downloads one artifact, unpacks or installs it, and starts StegSolver. No Java, no
JavaFX and no configuration.

Two scripts do the work, one per target platform:

```bash
packaging/package-linux.sh [--type app-image|deb|rpm] [--skip-tests] [--version X.Y.Z]
```

```powershell
powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1 [-Type app-image|msi] [-SkipTests]
```

Both follow the same three steps:

1. `mvn package` builds `target/stegsolver.jar` and copies the runtime dependencies (ZXing) into
   `target/dist/input`.
2. `jlink` creates `target/dist/runtime`, a Java runtime image that contains the JavaFX modules.
3. `jpackage` wraps the jars plus that runtime image into an application image (or an installer).

## Why it is done this way

**JavaFX inside the runtime image.** The JavaFX artifacts that Maven resolves carry a platform
classifier (`win`, `linux`, ...); the classifier jars contain both the classes and the native
libraries, and they are real JPMS modules. `jlink` can therefore link `javafx.base`, `javafx.graphics`
and `javafx.controls` straight out of the module path into the image. The plain (non classified)
artifacts are empty stubs and are deleted from the module path first, because having both would make
`jlink` fail with a duplicate module error.

**ZXing stays on the class path.** ZXing is an automatic module (`Automatic-Module-Name:
com.google.zxing`) and `jlink` refuses to link automatic modules. The application is therefore not
modular: the jars live in the app image's `app` directory and are put on the class path by
`jpackage`. The JavaFX modules are still resolved from the runtime image, because when the main class
is on the class path (the unnamed module) all modules in the image become root modules.

**A separate launcher class.** `Launcher.main` starts `StegSolverApp` through `Application.launch`.
Starting a class that extends `Application` directly from the class path fails with "JavaFX runtime
components are missing"; going through a plain class is what makes both `java -jar` and the
`jpackage` launcher work.

**Version handling.** `jpackage` accepts only numeric versions, so a development version such as
`1.0.0-SNAPSHOT` is packaged as `1.0.0`; both scripts derive that automatically and refuse to guess
if the version has no numeric prefix.

**Installers.** `--type app-image` needs nothing extra and produces a self contained folder. `deb`
additionally needs `fakeroot` and `dpkg` on the build machine, `msi` needs the WiX toolset. The
scripts default to `app-image` so that a build works anywhere.

## What a build produces

```
target/dist/
├── input/                 application jar + runtime dependencies
├── javafx-modules/        platform JavaFX modules (module path for jlink, not shipped)
├── runtime/               jlink runtime image (Java 21 + JavaFX)
└── packages/
    └── StegSolver/        the distributable
        ├── StegSolver(.exe)
        ├── app/           stegsolver.jar, zxing jars, StegSolver.cfg
        └── runtime/       the bundled runtime image
```

Measured on Windows 11 with JDK 21: runtime image 54 MB, application image 56 MB, and no Java
installation is required on the target machine.

## Verifying a packaged build

```bash
# Prints the version without starting the GUI
target/dist/packages/StegSolver/bin/StegSolver --version

# Starts the GUI, opens an image, renders a few transforms and exits with code 0
JAVA_TOOL_OPTIONS="-Dstegsolver.smokeTest=true -Dstegsolver.smokeSteps=6" \
  target/dist/packages/StegSolver/bin/StegSolver image.png
```

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dstegsolver.smokeTest=true'
& target\dist\packages\StegSolver\StegSolver.exe image.png
```

`--self-test` runs every engine layer through the bundled runtime and exits non-zero if anything
fails. It needs no display, so it also works over SSH and in container builds. From a source checkout
the same check is available as `mvn -B -ntp -Pself-test exec:exec`.

The GUI smoke test is what the CI packaging workflow uses to prove that a built artifact really starts
and renders, rather than only that it was produced.

## Platform notes

* **Windows 11** — `app-image` verified, including starting the GUI from a copy of the artifact in an
  unrelated directory with no JavaFX anywhere on the machine.
* **Linux Mint (Cinnamon), X11** — `app-image` and `deb`. Screen region capture needs X11; on Wayland
  the compositor does not expose other windows' pixels, so the feature reports that instead of
  capturing a black image.
* **macOS** — the Maven profiles select the right JavaFX classifier and the application builds, but
  the packaging scripts are only written for Windows and Linux; `jpackage --type dmg` would be the
  next step.
