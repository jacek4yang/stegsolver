#!/usr/bin/env bash
#
# Builds a self contained Linux application: a jlink runtime image with the JavaFX modules inside it,
# wrapped by jpackage so that the user does not need Java or JavaFX installed.
#
# Usage:
#   packaging/package-linux.sh [--type app-image|deb|rpm] [--skip-tests] [--version 1.2.3]
#
# Target: Linux Mint (Cinnamon, X11) and other Debian/Ubuntu derived desktops with glibc.
# The deb type additionally needs `fakeroot` and `dpkg` (or `rpm`) on the build machine.

set -euo pipefail

# The script is invoked as ./packaging/package-linux.sh or as `bash packaging/package-linux.sh`; both
# work, and `git update-index --chmod=+x` keeps the executable bit in the repository for the first form
# even though it was originally committed from a Windows checkout.

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

PACKAGE_TYPE="app-image"
SKIP_TESTS=""
VERSION="$(grep -m1 '<version>' pom.xml | sed -e 's/.*<version>//' -e 's/<\/version>.*//')"
APP_NAME="StegSolver"
MAIN_CLASS="io.github.jacek4yang.stegsolver.Launcher"
PLATFORM="linux"
JAVAFX_MODULES="javafx.base,javafx.graphics,javafx.controls"
JAVA_MODULES="java.base,java.desktop,java.logging,java.xml,java.prefs,java.datatransfer,java.scripting,jdk.charsets"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --type) PACKAGE_TYPE="$2"; shift 2 ;;
    --skip-tests) SKIP_TESTS="-DskipTests"; shift ;;
    --version) VERSION="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

# jpackage accepts only a numeric version, so 1.0.0-SNAPSHOT becomes 1.0.0.
PACKAGE_VERSION="${VERSION%%-*}"
if [[ ! "$PACKAGE_VERSION" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
  echo "Cannot derive a jpackage version from '$VERSION'" >&2
  exit 2
fi

OUT_DIR="target/dist"
INPUT_DIR="$OUT_DIR/input"
MODULE_DIR="$OUT_DIR/javafx-modules"
RUNTIME_DIR="$OUT_DIR/runtime"
PACKAGE_DIR="$OUT_DIR/packages"

echo "==> Cleaning $OUT_DIR"
rm -rf "$OUT_DIR"


echo "==> Building the application jar"
mvn -B -ntp $SKIP_TESTS clean package
mkdir -p "$INPUT_DIR" "$MODULE_DIR" "$PACKAGE_DIR"

echo "==> Collecting the runtime dependencies (ZXing) and the JavaFX modules"
mvn -B -ntp -q dependency:copy-dependencies \
  -DincludeScope=runtime -DexcludeGroupIds=org.openjfx -DstripVersion=true \
  -DoutputDirectory="$INPUT_DIR"
cp target/stegsolver.jar "$INPUT_DIR/"

mvn -B -ntp -q dependency:copy-dependencies \
  -DincludeScope=runtime -DincludeGroupIds=org.openjfx -DstripVersion=true \
  -DoutputDirectory="$MODULE_DIR"
# The plain JavaFX artifacts are empty stubs; only the platform ones carry classes and natives, and
# having both on the module path makes jlink fail with a duplicate module error.
find "$MODULE_DIR" -name '*.jar' ! -name "*-$PLATFORM.jar" -delete
echo "    module path: $(ls "$MODULE_DIR" | tr '\n' ' ')"

echo "==> Creating the jlink runtime image (Java $(java -version 2>&1 | head -1 | cut -d'"' -f2))"
jlink \
  --module-path "$MODULE_DIR" \
  --add-modules "$JAVA_MODULES,$JAVAFX_MODULES" \
  --output "$RUNTIME_DIR" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6

echo "==> Verifying that the runtime image contains JavaFX"
"$RUNTIME_DIR/bin/java" --list-modules | grep -E '^javafx\.(controls|graphics|base)' || {
  echo "JavaFX modules are missing from the runtime image" >&2; exit 1; }

echo "==> Packaging with jpackage (type: $PACKAGE_TYPE)"
JPACKAGE_ARGS=(
  --type "$PACKAGE_TYPE"
  --name "$APP_NAME"
  --app-version "$PACKAGE_VERSION"
  --vendor "jacek4yang"
  --description "Steganography analysis tool (bit planes, extraction, barcodes, file analysis)"
  --copyright "Copyright (c) 2025 jacek4yang; based on the original StegSolve by Caesum"
  --input "$INPUT_DIR"
  --main-jar "stegsolver.jar"
  --main-class "$MAIN_CLASS"
  --runtime-image "$RUNTIME_DIR"
  --dest "$PACKAGE_DIR"
  --java-options "-Dfile.encoding=UTF-8"
  --java-options "-Xmx2g"
)

if [[ "$PACKAGE_TYPE" != "app-image" ]]; then
  JPACKAGE_ARGS+=(--linux-shortcut --linux-menu-group "Graphics")
fi

jpackage "${JPACKAGE_ARGS[@]}"

echo "==> Verifying the packaged application starts"
if [[ "$PACKAGE_TYPE" == "app-image" ]]; then
  cp LICENSE README.md CHANGELOG.md "$PACKAGE_DIR/$APP_NAME/"
  "$PACKAGE_DIR/$APP_NAME/bin/$APP_NAME" --version
  "$PACKAGE_DIR/$APP_NAME/bin/$APP_NAME" --self-test
else
  echo "    Run the installed $APP_NAME to verify (a display is required)."
fi

echo
echo "Done. Artifacts:"
find "$PACKAGE_DIR" -maxdepth 1 -mindepth 1 -printf '  %p\n'
echo
echo "Smoke test with a display:"
echo "  $PACKAGE_DIR/$APP_NAME/bin/$APP_NAME -Dstegsolver.smokeTest=true $(pwd)/target/dist/smoke.png"
