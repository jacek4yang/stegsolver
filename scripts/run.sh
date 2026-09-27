#!/usr/bin/env bash
#
# Runs StegSolver straight from the sources with the normal JDK and Maven (no packaging needed).
#
#   scripts/run.sh                 start with an empty window
#   scripts/run.sh image.png       start with an image open
#   scripts/run.sh --smoke         start, render a few transforms, exit (used to verify a build)

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

SMOKE=""
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == "--smoke" ]]; then SMOKE="-Dstegsolver.smokeTest=true -Dstegsolver.smokeSteps=6"; else ARGS+=("$arg"); fi
done

mvn -B -ntp -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
CP="target/classes:$(cat target/cp.txt)"

exec java $SMOKE -cp "$CP" io.github.jacek4yang.stegsolver.Launcher "${ARGS[@]:-}"
